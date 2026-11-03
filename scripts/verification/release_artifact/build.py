#!/usr/bin/env python3
"""Build an independent audit APK without compiling or installing the target app.

The runner uses framework APIs and reflection only. No target classes or third
party libraries are compiled into the test APK, so its checks execute the
installed Release DEX. Signing uses only the standard Android debug keystore.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
TEST_PACKAGE = "cn.com.omnimind.releaseaudit"
TARGET_PACKAGE = "cn.com.omnimind.bot"
RUNNER = f"{TEST_PACKAGE}.ReleaseArtifactInstrumentation"


def default_sdk() -> Path | None:
    properties = REPO / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            match = re.match(r"\s*sdk\.dir\s*=\s*(.*?)\s*$", line)
            if match:
                return Path(match.group(1).replace(r"\:", ":").replace(r"\\", "\\"))
    value = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    return Path(value) if value else None


def latest_directory(root: Path, pattern: str) -> Path:
    candidates = []
    if root.is_dir():
        for path in root.iterdir():
            match = re.fullmatch(pattern, path.name)
            if path.is_dir() and match:
                candidates.append((tuple(int(n) for n in match.group(1).split(".")), path))
    if not candidates:
        raise ValueError(f"No installed stable SDK version found under {root}")
    return max(candidates)[1]


def java_tool(name: str) -> str:
    java_home = os.environ.get("JAVA_HOME")
    candidate = Path(java_home) / "bin" / name if java_home else None
    if candidate is not None and candidate.is_file():
        return str(candidate)
    found = shutil.which(name)
    if found is None:
        raise ValueError(f"Required JDK tool is unavailable: {name}")
    return found


def run(command: list[str | Path], label: str, *, capture: bool = False) -> subprocess.CompletedProcess:
    print(f"[release-audit] {label}", file=sys.stderr, flush=True)
    return subprocess.run(
        [str(part) for part in command],
        check=True,
        stdout=subprocess.PIPE if capture else sys.stderr,
        stderr=subprocess.PIPE if capture else sys.stderr,
    )


def apk_certificate(apksigner: Path, apk: Path) -> str:
    result = run([apksigner, "verify", "--print-certs", apk], f"Verify APK signature: {apk.name}", capture=True)
    output = result.stdout.decode("utf-8", errors="replace")
    # SDK versions label these either "Signer #1" or "V2 Signer:"/"V3 Signer:".
    # The same certificate can be printed once per signing scheme.
    digests = {
        value.lower()
        for value in re.findall(
            r"(?m)^[^\r\n]*\bcertificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*$",
            output,
        )
    }
    if len(digests) != 1:
        raise ValueError(f"Expected one APK signer, found {len(digests)}: {apk}")
    return next(iter(digests))


def build(args: argparse.Namespace) -> dict:
    if args.sdk is None:
        raise ValueError("Set sdk.dir in local.properties or pass --sdk")
    sdk = args.sdk.expanduser().resolve()
    output = args.output.expanduser()
    if not output.is_absolute():
        raise ValueError("--output must be an absolute directory, for example /tmp/omnibot-release-audit")
    output = output.resolve()
    source = args.source.expanduser().resolve()
    if not source.is_file():
        raise ValueError(f"Instrumentation source is missing: {source}")
    manifest = HERE / "AndroidManifest.xml"
    tools = latest_directory(sdk / "build-tools", r"(\d+\.\d+\.\d+)")
    platform = latest_directory(sdk / "platforms", r"android-(\d+(?:\.\d+)*)")
    android_jar = platform / "android.jar"
    for path in [android_jar, manifest, *(tools / name for name in ["d8", "aapt2", "zipalign", "apksigner"])]:
        if not path.is_file():
            raise ValueError(f"Required build input is missing: {path}")

    # This harness deliberately never reads project/global release signing properties.
    keystore = Path.home() / ".android" / "debug.keystore"
    if not keystore.is_file():
        raise ValueError(f"The existing standard debug keystore is required: {keystore}")
    certificate = run(
        [java_tool("keytool"), "-exportcert", "-keystore", keystore,
         "-alias", "androiddebugkey", "-storepass", "android"],
        "Read the standard debug signing certificate", capture=True,
    ).stdout
    certificate_sha256 = hashlib.sha256(certificate).hexdigest()
    if args.target_apk is not None:
        target_apk = args.target_apk.expanduser().resolve()
        if apk_certificate(tools / "apksigner", target_apk) != certificate_sha256:
            raise ValueError("Target APK is not signed with this debug key; no test APK was built")

    output.mkdir(parents=True, exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix="build-", dir=output))
    classes = work / "classes"
    dex = work / "dex"
    classes.mkdir()
    dex.mkdir()
    run(
        [java_tool("javac"), "--release", "8", "-encoding", "UTF-8",
         "-classpath", android_jar, "-d", classes, source],
        "Compile the framework-only instrumentation runner",
    )
    class_files = sorted(classes.rglob("*.class"))
    if not class_files:
        raise ValueError("javac produced no classes")
    class_jar = work / "classes.jar"
    with zipfile.ZipFile(class_jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in class_files:
            archive.write(path, path.relative_to(classes).as_posix())
    run([tools / "d8", "--min-api", "29", "--lib", android_jar, "--output", dex, class_jar], "Convert only harness classes to DEX")
    unsigned = work / "unsigned.apk"
    run([tools / "aapt2", "link", "-I", android_jar, "--manifest", manifest, "-o", unsigned], "Package the independent test manifest")
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(dex.glob("classes*.dex")):
            archive.write(path, path.name)
    aligned = work / "aligned.apk"
    run([tools / "zipalign", "-f", "4", unsigned, aligned], "Align the test APK")
    signed = work / "signed.apk"
    run(
        [tools / "apksigner", "sign", "--ks", keystore, "--ks-key-alias", "androiddebugkey",
         "--ks-pass", "pass:android", "--key-pass", "pass:android",
         "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
         "--out", signed, aligned],
        "Sign the test APK with the existing debug key",
    )
    if apk_certificate(tools / "apksigner", signed) != certificate_sha256:
        raise ValueError("Signed test APK certificate differs from the debug keystore")
    run([tools / "zipalign", "-c", "4", signed], "Verify final APK alignment")
    apk = output / "release-artifact-audit.apk"
    signed.replace(apk)
    report = {
        "apk": str(apk),
        "apkSha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "signerSha256": certificate_sha256,
        "source": str(source),
        "sourceSha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        "sdk": str(sdk),
        "buildTools": tools.name,
        "compilePlatform": platform.name,
        "testPackage": TEST_PACKAGE,
        "targetPackage": TARGET_PACKAGE,
        "targetProcess": f"{TARGET_PACKAGE}:release_audit",
        "instrumentation": f"{TEST_PACKAGE}/{RUNNER}",
        "intermediates": str(work),
        "installed": False,
    }
    (output / "build.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=default_sdk(), help="Android SDK; defaults to local.properties sdk.dir")
    parser.add_argument("--output", type=Path, required=True, help="Absolute output directory, normally under /tmp")
    parser.add_argument("--source", type=Path, default=HERE / "ReleaseArtifactInstrumentation.java", help="Framework-only Java instrumentation source")
    parser.add_argument("--target-apk", type=Path, help="Optional existing Release APK to check for a matching debug certificate")
    args = parser.parse_args()
    try:
        print(json.dumps(build(args), indent=2))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Build failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr.decode("utf-8", errors="replace"), file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
