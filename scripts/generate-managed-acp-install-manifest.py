#!/usr/bin/env python3
"""Generate the audited managed-ACP installed-file manifest without installing npm packages."""

from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import posixpath
import re
import ssl
import tarfile
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
LOCK_FILE = ROOT / "app/src/main/assets/agent_runtime/acp-adapters/package-lock.json"

CRITICAL_FILES = {
    "node_modules/@agentclientprotocol/codex-acp/dist/index.js": (
        "package/dist/index.js",
        "always",
    ),
    "node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js": (
        "package/dist/index.js",
        "always",
    ),
    "node_modules/@openai/codex/bin/codex.js": ("package/bin/codex.js", "always"),
    (
        "node_modules/@openai/codex-linux-arm64/vendor/"
        "aarch64-unknown-linux-musl/bin/codex"
    ): ("package/vendor/aarch64-unknown-linux-musl/bin/codex", "always"),
    "node_modules/@anthropic-ai/claude-agent-sdk/sdk.mjs": (
        "package/sdk.mjs",
        "always",
    ),
    "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64/claude": (
        "package/claude",
        "glibc",
    ),
    "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64-musl/claude": (
        "package/claude",
        "musl",
    ),
}


def sha512_hex(data: bytes) -> str:
    return hashlib.sha512(data).hexdigest()


def package_path_for_critical(installed_path: str, package_paths: list[str]) -> str:
    matches = [
        package_path
        for package_path in package_paths
        if installed_path == package_path or installed_path.startswith(package_path + "/")
    ]
    if not matches:
        raise RuntimeError(f"critical file has no owning locked package: {installed_path}")
    return max(matches, key=len)


def read_regular_member(archive: tarfile.TarFile, name: str) -> tuple[bytes, int]:
    try:
        member = archive.getmember(name)
    except KeyError as error:
        raise RuntimeError(f"required tar member missing: {name}") from error
    if not member.isfile() or member.issym() or member.islnk():
        raise RuntimeError(f"required tar member is not a regular file: {name}")
    if member.size < 1 or member.size > 512 * 1024 * 1024:
        raise RuntimeError(f"required tar member has invalid size: {name}")
    stream = archive.extractfile(member)
    if stream is None:
        raise RuntimeError(f"required tar member cannot be read: {name}")
    return stream.read(), member.mode & 0o777


def hash_regular_member(archive: tarfile.TarFile, member: tarfile.TarInfo) -> str:
    stream = archive.extractfile(member)
    if stream is None:
        raise RuntimeError(f"required tar member cannot be read: {member.name}")
    digest = hashlib.sha512()
    total = 0
    while True:
        chunk = stream.read(1024 * 1024)
        if not chunk:
            break
        total += len(chunk)
        if total > 512 * 1024 * 1024:
            raise RuntimeError(f"required tar member has invalid size: {member.name}")
        digest.update(chunk)
    if total != member.size:
        raise RuntimeError(f"required tar member has truncated content: {member.name}")
    return digest.hexdigest()


def package_tree_manifest(
    archive: tarfile.TarFile,
    executable_paths: set[str],
) -> tuple[str, int, int, set[str]]:
    canonical: list[str] = []
    regular_paths: set[str] = set()
    seen: set[str] = set()
    symlink_count = 0
    for member in archive.getmembers():
        if member.name in {"package", "package/"} and member.isdir():
            continue
        if not member.name.startswith("package/"):
            raise RuntimeError(f"tar member escaped package root: {member.name}")
        relative = member.name.removeprefix("package/")
        if not relative or "\t" in relative or "\n" in relative or "\r" in relative:
            raise RuntimeError(f"invalid tar member path: {member.name}")
        if relative.startswith("/") or posixpath.normpath(relative).startswith("../"):
            raise RuntimeError(f"unsafe tar member path: {member.name}")
        if relative == "node_modules" or relative.startswith("node_modules/") or "/node_modules/" in relative:
            raise RuntimeError(f"package tarball contains embedded node_modules: {member.name}")
        if relative in seen:
            raise RuntimeError(f"duplicate tar member path: {member.name}")
        seen.add(relative)
        if member.isdir():
            continue
        if member.isfile() and not member.islnk():
            if member.size < 0 or member.size > 512 * 1024 * 1024:
                raise RuntimeError(f"tar member has invalid size: {member.name}")
            digest = hash_regular_member(archive, member)
            mode = member.mode & 0o777
            if relative in executable_paths:
                mode |= 0o111
            canonical.append(f"F\t{relative}\t{mode:o}\t{digest}\n")
            regular_paths.add(relative)
            continue
        if member.issym():
            target = member.linkname
            if (
                not target
                or target.startswith("/")
                or "\t" in target
                or "\n" in target
                or "\r" in target
            ):
                raise RuntimeError(f"unsafe package symlink: {member.name}")
            resolved = posixpath.normpath(posixpath.join(posixpath.dirname(relative), target))
            if resolved == ".." or resolved.startswith("../"):
                raise RuntimeError(f"package symlink escapes root: {member.name}")
            canonical.append(f"L\t{relative}\t{target}\n")
            symlink_count += 1
            continue
        raise RuntimeError(f"unsupported tar member type: {member.name}")
    canonical.sort(key=lambda line: line.encode("utf-8"))
    tree = hashlib.sha512("".join(canonical).encode("utf-8")).hexdigest()
    return tree, len(regular_paths), symlink_count, regular_paths


def package_bins(package_json: bytes, regular_paths: set[str]) -> dict[str, str]:
    metadata = json.loads(package_json)
    raw = metadata.get("bin", {})
    if isinstance(raw, str):
        name = metadata.get("name")
        if not isinstance(name, str) or not name:
            raise RuntimeError("string bin entry has no package name")
        raw = {name.rsplit("/", 1)[-1]: raw}
    if raw is None:
        raw = {}
    if not isinstance(raw, dict):
        raise RuntimeError("invalid package bin map")
    result: dict[str, str] = {}
    for name, target in raw.items():
        if not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9._-]+", name):
            raise RuntimeError("unsafe package bin name")
        if not isinstance(target, str):
            raise RuntimeError("invalid package bin target")
        normalized = posixpath.normpath(target.removeprefix("./"))
        if normalized.startswith("../") or normalized not in regular_paths:
            raise RuntimeError("package bin target is not a locked regular file")
        result[name] = normalized
    return dict(sorted(result.items()))


def download_verified(entry: dict[str, object]) -> bytes:
    resolved = entry.get("resolved")
    integrity = entry.get("integrity")
    if not isinstance(resolved, str) or not isinstance(integrity, str):
        raise RuntimeError("locked package is missing resolved/integrity")
    parsed = urlsplit(resolved)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "registry.npmjs.org"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or not parsed.path.endswith(".tgz")
    ):
        raise RuntimeError("locked package is not an official immutable npm tarball")
    if not integrity.startswith("sha512-") or " " in integrity:
        raise RuntimeError("locked package lacks a single sha512 integrity")
    expected = base64.b64decode(integrity.removeprefix("sha512-"), validate=True)
    request = urllib.request.Request(resolved, headers={"User-Agent": "OmniBot-lock-audit/1"})
    with urllib.request.urlopen(request, timeout=300, context=ssl.create_default_context()) as response:
        data = response.read(512 * 1024 * 1024 + 1)
    if len(data) > 512 * 1024 * 1024 or hashlib.sha512(data).digest() != expected:
        raise RuntimeError("locked npm tarball failed sha512 verification")
    return data


def generate() -> dict[str, object]:
    lock_bytes = LOCK_FILE.read_bytes().replace(b"\r\n", b"\n").replace(b"\r", b"\n")
    lock = json.loads(lock_bytes)
    packages = lock.get("packages")
    if not isinstance(packages, dict):
        raise RuntimeError("lock package map missing")
    package_paths = sorted(path for path in packages if path)
    critical_by_package: dict[str, list[tuple[str, str, str]]] = {}
    for installed_path, (member_path, required_when) in CRITICAL_FILES.items():
        owner = package_path_for_critical(installed_path, package_paths)
        critical_by_package.setdefault(owner, []).append(
            (installed_path, member_path, required_when)
        )

    package_manifest: list[dict[str, object]] = []
    critical_manifest: list[dict[str, object]] = []
    cache: dict[tuple[str, str], bytes] = {}
    for package_path in package_paths:
        entry = packages[package_path]
        if not isinstance(entry, dict):
            raise RuntimeError(f"invalid lock entry: {package_path}")
        key = (str(entry.get("resolved")), str(entry.get("integrity")))
        data = cache.get(key)
        if data is None:
            data = download_verified(entry)
            cache[key] = data
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            package_json, package_mode = read_regular_member(archive, "package/package.json")
            regular_paths = {
                member.name.removeprefix("package/")
                for member in archive.getmembers()
                if member.name.startswith("package/") and member.isfile() and not member.islnk()
            }
            bins = package_bins(package_json, regular_paths)
            tree_sha512, file_count, symlink_count, regular_paths = package_tree_manifest(
                archive,
                set(bins.values()),
            )
            package_manifest.append(
                {
                    "path": package_path,
                    "version": entry.get("version"),
                    "packageJsonSha512": sha512_hex(package_json),
                    "mode": package_mode,
                    "optional": bool(entry.get("optional", False)),
                    "os": entry.get("os", []),
                    "cpu": entry.get("cpu", []),
                    "libc": entry.get("libc", []),
                    "treeSha512": tree_sha512,
                    "fileCount": file_count,
                    "symlinkCount": symlink_count,
                    "bins": bins,
                }
            )
            for installed_path, member_path, required_when in critical_by_package.get(
                package_path, []
            ):
                contents, mode = read_regular_member(archive, member_path)
                if member_path.removeprefix("package/") in bins.values():
                    mode |= 0o111
                critical_manifest.append(
                    {
                        "path": installed_path,
                        "sha512": sha512_hex(contents),
                        "mode": mode,
                        "executable": bool(mode & 0o111),
                        "requiredWhen": required_when,
                    }
                )

    return {
        "schemaVersion": 1,
        "lockSha512": sha512_hex(lock_bytes),
        "packages": package_manifest,
        "criticalFiles": sorted(critical_manifest, key=lambda item: str(item["path"])),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    text = json.dumps(generate(), ensure_ascii=True, indent=2, sort_keys=True) + "\n"
    if args.output is None:
        print(text, end="")
    else:
        args.output.write_text(text, encoding="utf-8", newline="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
