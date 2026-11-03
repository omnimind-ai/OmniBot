#!/usr/bin/env python3
"""Build one frozen Flutter variant and retain its APK; no device access."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--variant", required=True, choices=("baseline", "after"))
    parser.add_argument("--mode", default="profile", choices=("profile", "debug"))
    args = parser.parse_args()
    output = Path(args.output).resolve()
    manifest = json.loads((output / "keyboard-perf.json").read_text())
    if manifest.get("package") != "cn.com.omnimind.bot.keyboardperf":
        raise SystemExit("Not a keyboard performance workspace")
    source = output / args.variant / "ui"
    suffix = "-debug" if args.mode == "debug" else ""
    apk_output = output / args.variant / f"keyboardperf{suffix}.apk"
    if apk_output.exists():
        raise SystemExit("APK exists; choose a new workspace to retain reproducibility")
    command = [str(ROOT / "gradlew"), "--no-daemon", "-I", str(Path(__file__).with_name("profile.init.gradle")),
               f":app:assembleDevelopStandard{args.mode.capitalize()}", f"-PkeyboardPerfSource={source}",
               f"-Ptarget={source / 'lib/keyboard_perf_main.dart'}", "-Ptarget-platform=android-arm64"]
    subprocess.run(command, cwd=ROOT, check=True)
    built = ROOT / f"app/build/outputs/apk/developStandard/{args.mode}/app-develop-standard-{args.mode}.apk"
    shutil.copyfile(built, apk_output)
    (apk_output.parent / f"apk{suffix}-sha256.txt").write_text(hashlib.sha256(apk_output.read_bytes()).hexdigest() + "\n")
    print(f"Retained {apk_output}; no device access.")


if __name__ == "__main__":
    main()
