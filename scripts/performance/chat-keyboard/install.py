#!/usr/bin/env python3
"""Verify the APK application ID before installing the independent test app."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile
from common import PACKAGE, output_directory, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--apkanalyzer", default="apkanalyzer")
    parser.add_argument("--mode", default="profile", choices=("profile", "debug"))
    args = parser.parse_args()
    output = output_directory(args.output)
    apk = Path(args.apk).expanduser().resolve(strict=True)
    analyzer = shutil.which(args.apkanalyzer)
    if analyzer is None:
        raise SystemExit("Pass --apkanalyzer /path/to/Android/sdk/cmdline-tools/latest/bin/apkanalyzer")
    package = subprocess.check_output([analyzer, "manifest", "application-id", str(apk)], text=True).strip()
    if package != PACKAGE:
        raise SystemExit("Refusing APK: its application ID is not the fixed keyboardperf package")
    manifest = ET.fromstring(subprocess.check_output([analyzer, "manifest", "print", str(apk)]))
    application = manifest.find("application")
    if application is None or application.get("{http://schemas.android.com/apk/res/android}debuggable") != "true":
        raise SystemExit("Expected a native-debuggable measurement APK")
    with ZipFile(apk) as archive:
        names = archive.namelist()
        has_aot = any(name.startswith("lib/") and name.endswith("/libapp.so") for name in names)
        has_kernel = "assets/flutter_assets/kernel_blob.bin" in names
        if args.mode == "profile" and (not has_aot or has_kernel):
            raise SystemExit("Expected an AOT libapp.so Profile APK, not a JIT debug APK")
        if args.mode == "debug" and (not has_kernel or has_aot):
            raise SystemExit("Expected a JIT kernel_blob.bin debug APK without AOT libapp.so")
    if not args.device.strip():
        raise SystemExit("Pass an explicit --device")
    subprocess.run(["adb", "-s", args.device, "install", "-r", str(apk)], check=True)
    write_json(output / "installed-apk.json", {
        "package": PACKAGE, "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "flutterAotLibrary": has_aot, "flutterKernelBlob": has_kernel,
        "flutterBuildMode": args.mode, "nativeDebuggable": True,
    })
    print("Installed the independently verified keyboardperf APK; app data was retained.")


if __name__ == "__main__":
    main()
