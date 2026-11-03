#!/usr/bin/env python3
"""Forward only the currently running keyboardperf process's Dart VM service."""
import argparse
import json
import os
import re
import subprocess
from urllib.parse import urlparse
from common import PACKAGE, adb_for, output_directory


def remove_previous(adb, device, path):
    if not path.exists():
        return
    previous = json.loads(path.read_text())
    if previous.get("package") != PACKAGE or previous.get("device") != device:
        raise SystemExit("Existing private connection belongs to another benchmark device")
    lines = subprocess.check_output(adb + ["forward", "--list"], text=True).splitlines()
    mapping = [device, f"tcp:{previous['forwardPort']}", f"tcp:{previous['remotePort']}"]
    if any(line.split() == mapping for line in lines):
        subprocess.run(adb + ["forward", "--remove", f"tcp:{previous['forwardPort']}"], check=True, capture_output=True)
    path.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--disconnect", action="store_true")
    args = parser.parse_args()
    output = output_directory(args.output)
    adb = adb_for(args.device)
    private = output / "vm-connection-private.json"
    remove_previous(adb, args.device, private)
    if args.disconnect:
        print("Removed this workspace's VM forwarding and private connection file.")
        return
    pid = subprocess.check_output(adb + ["shell", "pidof", PACKAGE], text=True).strip()
    if not pid.isdigit():
        raise SystemExit("Launch exactly one keyboardperf main process first")
    logs = subprocess.check_output(adb + ["logcat", f"--pid={pid}", "-d"], text=True, errors="replace")
    lines = [line for line in logs.splitlines() if re.search(r"Dart VM [Ss]ervice is listening", line)]
    matches = re.findall(r"https?://127\.0\.0\.1:\d+/[A-Za-z0-9_=\-/]+", lines[-1]) if lines else []
    if not matches:
        raise SystemExit(
            "No retained Dart VM service announcement for the current keyboardperf PID. "
            "Manually restart the independent test package, then run connect.py again; "
            "this script will not restart it or change a warm baseline."
        )
    uri = urlparse(matches[-1])
    forward = int(subprocess.check_output(adb + ["forward", "tcp:0", f"tcp:{uri.port}"], text=True).strip())
    descriptor = os.open(private, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump({"package": PACKAGE, "device": args.device, "pid": int(pid),
                   "remotePort": uri.port, "forwardPort": forward,
                   "ws": f"ws://127.0.0.1:{forward}{uri.path}ws"}, stream)
    print("Private VM connection ready for the isolated process; address and token are not printed.")


if __name__ == "__main__":
    main()
