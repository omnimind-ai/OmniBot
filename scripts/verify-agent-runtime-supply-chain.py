#!/usr/bin/env python3
"""Offline, fail-closed audit for Agent runtime package installation inputs."""

from __future__ import annotations

import base64
import copy
import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit


ROOT = Path(__file__).resolve().parents[1]
ASSET_DIR = ROOT / "app/src/main/assets/agent_runtime/acp-adapters"
PACKAGE_FILE = ASSET_DIR / "package.json"
LOCK_FILE = ASSET_DIR / "package-lock.json"
MANIFEST_FILE = ASSET_DIR / "installed-manifest.json"
EXPECTED_PACKAGE_SHA512 = (
    "db1ab1969abc22649f9a9ca751acc725c9332c985434ac7b48cc4c80b2e2ac4b"
    "922f2a90416e9e13b14c441dfa6f54073c317f047635c7c0341ca3544cfba0d6"
)
EXPECTED_LOCK_SHA512 = (
    "dea567257b8063c435b7ab135e7ec7a81d56fd624443761d2b435d28d0089ce2"
    "47ae06529e651f7058c693ec9f80c070a95161d17292e22c4fe821d6eeef05e7"
)
EXPECTED_MANIFEST_SHA512 = (
    "8bf3fc30f8f055e1017440864aab2033d8fce098f8d6e5a1931540ad4961fab4"
    "8b7fc892926a270372428c41d72c03218a6d96a2de6feb93a34a4ea128412363"
)
EXPECTED_DEPENDENCIES = {
    "@agentclientprotocol/claude-agent-acp": "0.61.0",
    "@agentclientprotocol/codex-acp": "1.1.7",
}


class AuditFailure(RuntimeError):
    pass


def fail(message: str) -> None:
    raise AuditFailure(message)


def normalized_bytes(path: Path) -> bytes:
    try:
        return path.read_text(encoding="utf-8").replace("\r\n", "\n").replace("\r", "\n").encode()
    except OSError as error:
        fail(f"missing audited input: {path.name}: {error.__class__.__name__}")


def sha512_hex(data: bytes) -> str:
    return hashlib.sha512(data).hexdigest()


def audit_lock(
    package_bytes: bytes,
    lock_bytes: bytes,
    expected_package_hash: str = EXPECTED_PACKAGE_SHA512,
    expected_lock_hash: str = EXPECTED_LOCK_SHA512,
) -> None:
    if sha512_hex(package_bytes) != expected_package_hash:
        fail("package.json SHA-512 mismatch")
    if sha512_hex(lock_bytes) != expected_lock_hash:
        fail("package-lock.json SHA-512 mismatch")
    try:
        package = json.loads(package_bytes)
        lock = json.loads(lock_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("managed ACP JSON is invalid")

    if package.get("private") is not True or "scripts" in package:
        fail("managed package root must be private and script-free")
    if package.get("dependencies") != EXPECTED_DEPENDENCIES:
        fail("managed top-level package versions changed")
    if lock.get("lockfileVersion") != 3:
        fail("managed lock must use lockfileVersion 3")
    packages = lock.get("packages")
    if not isinstance(packages, dict) or len(packages) <= len(EXPECTED_DEPENDENCIES):
        fail("managed transitive package map is missing")
    if packages.get("", {}).get("dependencies") != EXPECTED_DEPENDENCIES:
        fail("lock root does not match the audited package root")

    for path, entry in packages.items():
        if path == "":
            continue
        if not path.startswith("node_modules/") or not isinstance(entry, dict):
            fail("unexpected lock package path")
        if "link" in entry or "hasInstallScript" in entry:
            fail(f"links/install scripts are forbidden: {path}")
        if not isinstance(entry.get("version"), str) or not entry["version"]:
            fail(f"exact version missing: {path}")

        resolved = entry.get("resolved")
        if not isinstance(resolved, str):
            fail(f"resolved tarball missing: {path}")
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
            fail(f"non-official or ambiguous tarball URL: {path}")

        integrity = entry.get("integrity")
        if not isinstance(integrity, str) or " " in integrity or not integrity.startswith("sha512-"):
            fail(f"single sha512 SRI missing: {path}")
        try:
            digest = base64.b64decode(integrity.removeprefix("sha512-"), validate=True)
        except ValueError:
            fail(f"invalid sha512 SRI encoding: {path}")
        if len(digest) != 64:
            fail(f"invalid sha512 SRI length: {path}")


def audit_manifest(
    manifest_bytes: bytes,
    lock_bytes: bytes,
    expected_manifest_hash: str = EXPECTED_MANIFEST_SHA512,
) -> None:
    if sha512_hex(manifest_bytes) != expected_manifest_hash:
        fail("installed-manifest.json SHA-512 mismatch")
    try:
        manifest = json.loads(manifest_bytes)
        lock = json.loads(lock_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError):
        fail("managed installed-tree manifest is invalid")
    if manifest.get("schemaVersion") != 1:
        fail("managed installed-tree manifest schema changed")
    if manifest.get("lockSha512") != EXPECTED_LOCK_SHA512:
        fail("managed installed-tree manifest is bound to the wrong lock")
    packages = manifest.get("packages")
    critical = manifest.get("criticalFiles")
    if not isinstance(packages, list) or not isinstance(critical, list):
        fail("managed installed-tree manifest lists are missing")
    lock_packages = {path: entry for path, entry in lock["packages"].items() if path}
    by_path = {}
    total_files = 0
    for item in packages:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            fail("invalid managed package tree entry")
        path = item["path"]
        if path in by_path or path not in lock_packages:
            fail("duplicate or unknown managed package tree")
        if item.get("version") != lock_packages[path].get("version"):
            fail(f"managed package tree version drifted: {path}")
        if not re.fullmatch(r"[0-9a-f]{128}", str(item.get("treeSha512", ""))):
            fail(f"managed package tree digest missing: {path}")
        file_count = item.get("fileCount")
        symlink_count = item.get("symlinkCount")
        if not isinstance(file_count, int) or file_count < 1:
            fail(f"managed package file count invalid: {path}")
        if not isinstance(symlink_count, int) or symlink_count < 0:
            fail(f"managed package symlink count invalid: {path}")
        bins = item.get("bins")
        if not isinstance(bins, dict):
            fail(f"managed package bin map missing: {path}")
        for name, target in bins.items():
            if not re.fullmatch(r"[A-Za-z0-9._-]+", name):
                fail(f"unsafe managed package bin name: {path}")
            if (
                not isinstance(target, str)
                or not target
                or target.startswith("/")
                or any(part in {"", ".", ".."} for part in target.split("/"))
                or "node_modules" in target.split("/")
            ):
                fail(f"unsafe managed package bin target: {path}")
        total_files += file_count
        by_path[path] = item
    if set(by_path) != set(lock_packages) or total_files < 6_000:
        fail("managed installed-tree manifest is incomplete")
    required_critical = {
        "node_modules/@agentclientprotocol/codex-acp/dist/index.js": "always",
        "node_modules/@agentclientprotocol/claude-agent-acp/dist/index.js": "always",
        "node_modules/@openai/codex/bin/codex.js": "always",
        "node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/bin/codex": "always",
        "node_modules/@anthropic-ai/claude-agent-sdk/sdk.mjs": "always",
        "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64/claude": "glibc",
        "node_modules/@anthropic-ai/claude-agent-sdk-linux-arm64-musl/claude": "musl",
    }
    actual_critical = {}
    for item in critical:
        if not isinstance(item, dict) or not re.fullmatch(
            r"[0-9a-f]{128}", str(item.get("sha512", ""))
        ):
            fail("managed critical file digest missing")
        if item.get("path") != "node_modules/@anthropic-ai/claude-agent-sdk/sdk.mjs" and not item.get("executable"):
            fail("managed runtime executable lost its executable bit")
        actual_critical[item.get("path")] = item.get("requiredWhen")
    if actual_critical != required_critical:
        fail("managed critical runtime set changed")


def audit_source_guards() -> None:
    manager = (
        ROOT / "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentRuntimeManager.kt"
    ).read_text(encoding="utf-8")
    managed = (
        ROOT / "app/src/main/java/cn/com/omnimind/bot/agent/runtime/ManagedAcpAdapterInstall.kt"
    ).read_text(encoding="utf-8")
    environment = (
        ROOT
        / "app/src/main/java/com/ai/assistance/operit/terminal/setup/EnvironmentSetupLogic.kt"
    ).read_text(encoding="utf-8")
    embedded = (
        ROOT / "app/src/main/java/cn/com/omnimind/bot/terminal/EmbeddedTerminalRuntime.kt"
    ).read_text(encoding="utf-8")
    pet_skill = (
        ROOT / "app/src/main/assets/builtin_skills/install-codex-pet/SKILL.md"
    ).read_text(encoding="utf-8")
    skill_discovery = (
        ROOT / "app/src/main/assets/builtin_skills/find-install-skills/SKILL.md"
    ).read_text(encoding="utf-8")
    skill_installer = (
        ROOT
        / "app/src/main/assets/builtin_skills/find-install-skills/scripts/install_exact_skill.cjs"
    ).read_text(encoding="utf-8")
    skill_installer_wrapper = (
        ROOT
        / "app/src/main/assets/builtin_skills/find-install-skills/scripts/install_with_skills_cli.sh"
    ).read_text(encoding="utf-8")

    required_managed_tokens = (
        r'mktemp -d \"\$versions_dir/.staging.',
        r'env -i HOME=\"\$npm_home\"',
        "ci --ignore-scripts --omit=dev",
        "--registry=https://registry.npmjs.org/",
        r'NPM_CONFIG_USERCONFIG=\"\$npm_user_config\"',
        r'NPM_CONFIG_GLOBALCONFIG=\"\$npm_global_config\"',
        "NPM_CONFIG_IGNORE_SCRIPTS=true",
        "NPM_CONFIG_ENGINE_STRICT=true",
        "NPM_CONFIG_UMASK=0022",
        r'test ! -e \"\$staging/.npmrc\"',
        r'test ! -e \"\$staging/npm-shrinkwrap.json\"',
        r'cd \"\$staging\"',
        "fs.readFileSync(0)",
        "fs.constants.O_EXCL | fs.constants.O_NOFOLLOW",
        "fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW",
        "readVerifiedControlFile",
        ".omnibot-installed-manifest.json",
        "invalid managed adapter tree",
        "digest !== item.treeSha512",
        "actualNames.length !== expected.size",
        "expectedTargets.has(target)",
        "if (prefix !== '') fail()",
        "info.nlink !== 1",
        r'rm -f -- \"\$staging/node_modules/.package-lock.json\"',
        ">/dev/null 2>&1",
        r"node_version=\$node_version",
        r"npm_version=\$npm_version",
    )
    for token in required_managed_tokens:
        if token not in managed:
            fail(f"managed npm guard missing: {token}")
    for forbidden in (
        "NODE_AUTH_TOKEN",
        "NPM_TOKEN",
        "/root/.npmrc",
        "/root/.npm-global",
        "npm install -g",
    ):
        if forbidden in managed:
            fail(f"managed npm command references forbidden ambient state: {forbidden}")

    install_command_start = managed.index("internal fun buildManagedAcpInstallCommand")
    install_command_end = managed.index(
        "internal fun buildManagedAcpLaunchCommand", install_command_start
    )
    install_command_body = managed[install_command_start:install_command_end]
    for forbidden in ("payload.packageJsonBase64", "payload.packageLockBase64", "payload.manifestJsonBase64"):
        if forbidden in install_command_body:
            fail(f"managed lock asset is embedded in the shell argv: {forbidden}")

    ensure_start = manager.index("private suspend fun ensureManagedAcpAdapter")
    ensure_end = manager.index("private suspend fun isManagedAcpAdapterReady", ensure_start)
    ensure_body = manager[ensure_start:ensure_end]
    if "runtime.discoveryCommand" in ensure_body or "isTerminalCommandAvailable" in ensure_body:
        fail("managed adapter install still depends on an ambient base CLI")
    for token in (
        "buildManagedAcpInstallInput(payload)",
        "process.outputStream.use",
        "stdinFailed.get()",
    ):
        if token not in ensure_body:
            fail(f"managed adapter stdin handoff guard missing: {token}")
    for raw_output in ("result.output", "result.rawOutputPreview", "result.error"):
        if raw_output in ensure_body:
            fail(f"installer output is returned to the user: {raw_output}")
    for code in (
        "AGENT_RUNTIME_ADAPTER_LOCK_INVALID",
        "AGENT_RUNTIME_ADAPTER_NPM_MISSING",
        "AGENT_RUNTIME_ADAPTER_INSTALL_FAILED",
    ):
        if code not in manager and code not in managed:
            fail(f"stable Agent runtime error code missing: {code}")

    profile_store = (
        ROOT / "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AcpAgentProfileStore.kt"
    ).read_text(encoding="utf-8")
    for token in (
        "AppSecretStore.readWithStatus",
        "desiredMetadataJson",
        "snapshots.forEach",
        "ACP_AGENT_PROFILE_PERSIST_FAILED",
    ):
        if token not in profile_store:
            fail(f"ACP profile transaction guard missing: {token}")
    local_runtime = (
        ROOT / "app/src/main/java/cn/com/omnimind/bot/agent/runtime/LocalAcpRuntime.kt"
    ).read_text(encoding="utf-8")
    if "buildManagedAcpLaunchCommand" not in local_runtime:
        fail("official managed ACP launch is not bound to the verified absolute executable")
    gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    local_release = (ROOT / "scripts/build-local-release.sh").read_text(encoding="utf-8")
    if "dependsOn(verifyAgentRuntimeSupplyChain)" not in gradle:
        fail("production Gradle release tasks do not depend on the Agent runtime audit")
    if 'python3 "$ROOT_DIR/scripts/verify-agent-runtime-supply-chain.py"' not in local_release:
        fail("local release entrypoint does not run the Agent runtime audit")

    combined_automatic = "\n".join((manager, managed, environment, embedded))
    forbidden_patterns = (
        r"npm\s+install\s+-g",
        r"@[Ll][Aa][Tt][Ee][Ss][Tt]",
        r"pip\s+install[^\n]*--upgrade",
        r"npm\s+install\s+-g\s+pnpm",
    )
    for pattern in forbidden_patterns:
        if re.search(pattern, combined_automatic):
            fail(f"floating automatic installer remains: {pattern}")
    if "AGENT_RUNTIME_MANAGED_CLI_LOCK_REQUIRED" not in environment:
        fail("optional standalone Agent CLIs are not fail-closed")
    if "AGENT_RUNTIME_UV_LOCK_REQUIRED" not in environment:
        fail("optional uv installer is not fail-closed")
    if "AGENT_RUNTIME_CODEX_PETS_LOCK_REQUIRED" not in pet_skill:
        fail("codex-pets network runner is not fail-closed")
    if "AGENT_RUNTIME_SKILLS_CLI_LOCK_REQUIRED" not in skill_discovery:
        fail("Skills CLI discovery is not fail-closed")
    for token in (
        "--confirm-exact",
        "timingSafeEqual",
        "SKILL_INSTALL_COMMIT_REQUIRED",
        "SKILL_INSTALL_EXACT_CONFIRMATION_REQUIRED",
        "parsed.hostname.toLowerCase() !== 'github.com'",
        "parsed.username !== ''",
        "parsed.search !== ''",
        "parsed.hash !== ''",
        "fetch', '--quiet', '--no-tags', '--depth=1'",
        "FETCH_HEAD^{commit}",
        "fetched !== args.commit",
        "ls-tree', '-rlz', '--full-tree'",
        "MAX_REPOSITORY_FILES",
        "MAX_REPOSITORY_BYTES",
        "stat.isSymbolicLink()",
        "stat.nlink === 1",
        "fs.constants.O_NOFOLLOW",
        "fs.constants.COPYFILE_EXCL",
        "fs.renameSync(staging, target)",
        "SKILL_INSTALL_SKILL_NOT_UNIQUE",
        "GIT_CONFIG_NOSYSTEM: '1'",
        "GIT_TERMINAL_PROMPT: '0'",
    ):
        if token not in skill_installer:
            fail(f"exact skill installer guard missing: {token}")
    for forbidden in (
        "git clone",
        "git@github.com",
        "OMNIBOT_SKILLS_ROOT",
        "WORKSPACE_ROOT:-",
        "shell: true",
        "execSync(",
        "rm -rf",
    ):
        if forbidden in skill_installer or forbidden in skill_installer_wrapper:
            fail(f"unsafe exact skill installer behavior remains: {forbidden}")
    for token in ("--commit <40-hex-commit>", "--confirm-exact", "single skill ID"):
        if token not in skill_discovery:
            fail(f"exact skill confirmation documentation missing: {token}")
    if "corepack pnpm" in embedded.lower() or "pnpm -v" in embedded.lower():
        fail("terminal readiness probe may download an unpinned package manager")

    builtin_root = ROOT / "app/src/main/assets/builtin_skills"
    text_suffixes = {".cjs", ".json", ".md", ".py", ".sh", ".yaml", ".yml"}
    for asset in builtin_root.rglob("*"):
        if not asset.is_file() or asset.suffix.lower() not in text_suffixes:
            continue
        try:
            asset_text = asset.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            fail(f"builtin skill text asset is not UTF-8: {asset.relative_to(ROOT)}")
        if re.search(r"\bnpx\b", asset_text, flags=re.IGNORECASE):
            fail(f"unversioned network package runner remains: {asset.relative_to(ROOT)}")


def run_negative_fixtures(package_bytes: bytes, lock_bytes: bytes) -> None:
    lock = json.loads(lock_bytes)
    first_path = next(path for path in lock["packages"] if path)

    mutations = []
    missing_integrity = copy.deepcopy(lock)
    missing_integrity["packages"][first_path].pop("integrity", None)
    mutations.append(missing_integrity)

    weak_integrity = copy.deepcopy(lock)
    weak_integrity["packages"][first_path]["integrity"] = "sha256-" + base64.b64encode(b"0" * 32).decode()
    mutations.append(weak_integrity)

    private_registry = copy.deepcopy(lock)
    private_registry["packages"][first_path]["resolved"] = (
        "https://private-registry.example.invalid/token-canary-do-not-leak.tgz"
    )
    mutations.append(private_registry)

    for mutation in mutations:
        mutated = json.dumps(mutation, separators=(",", ":")).encode()
        try:
            audit_lock(
                package_bytes,
                mutated,
                expected_lock_hash=sha512_hex(mutated),
            )
        except AuditFailure:
            continue
        fail("negative lock fixture bypassed the structural audit")


def run_negative_manifest_fixtures(manifest_bytes: bytes, lock_bytes: bytes) -> None:
    manifest = json.loads(manifest_bytes)
    mutations = []
    missing_tree = copy.deepcopy(manifest)
    missing_tree["packages"][0].pop("treeSha512", None)
    mutations.append(missing_tree)
    unsafe_bin = copy.deepcopy(manifest)
    unsafe_bin["packages"][0]["bins"] = {"escape": "dist/node_modules/evil.js"}
    mutations.append(unsafe_bin)
    incomplete = copy.deepcopy(manifest)
    incomplete["packages"].pop()
    mutations.append(incomplete)
    for mutation in mutations:
        mutated = json.dumps(mutation, separators=(",", ":")).encode()
        try:
            audit_manifest(
                mutated,
                lock_bytes,
                expected_manifest_hash=sha512_hex(mutated),
            )
        except AuditFailure:
            continue
        fail("negative installed-tree manifest fixture bypassed the audit")


def main() -> int:
    try:
        package_bytes = normalized_bytes(PACKAGE_FILE)
        lock_bytes = normalized_bytes(LOCK_FILE)
        manifest_bytes = normalized_bytes(MANIFEST_FILE)
        audit_lock(package_bytes, lock_bytes)
        audit_manifest(manifest_bytes, lock_bytes)
        run_negative_fixtures(package_bytes, lock_bytes)
        run_negative_manifest_fixtures(manifest_bytes, lock_bytes)
        audit_source_guards()
    except AuditFailure as error:
        print(f"Agent runtime supply-chain audit FAILED: {error}", file=sys.stderr)
        return 1
    print("Agent runtime supply-chain audit: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
