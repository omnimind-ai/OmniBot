"""Shared guards for the isolated, synthetic keyboard benchmark."""
from pathlib import Path
import json
import subprocess

PACKAGE = "cn.com.omnimind.bot.keyboardperf"
ACTIVITY = f"{PACKAGE}/cn.com.omnimind.bot.activity.MainActivity"
ROOT = Path(__file__).resolve().parents[3]


def output_directory(value, *, prepared=True):
    path = Path(value).expanduser().resolve()
    try:
        relative = path.relative_to(ROOT)
    except ValueError:
        raise SystemExit("--output must be inside this checkout and ignored by Git")
    if not relative.parts or subprocess.run(
        ["git", "check-ignore", "--no-index", "-q", "--", str(relative)], cwd=ROOT
    ).returncode:
        raise SystemExit("--output must be a Git-ignored directory, for example build/performance/replay")
    if prepared:
        try:
            metadata = json.loads((path / "keyboard-perf.json").read_text())
        except (OSError, ValueError):
            raise SystemExit("Run prepare.py with this --output first")
        if metadata.get("package") != PACKAGE:
            raise SystemExit("Output does not belong to the isolated keyboard benchmark")
    return path


def adb_for(device):
    if not device or not device.strip():
        raise SystemExit("Pass an explicit --device; no default device is selected")
    adb = ["adb", "-s", device]
    if subprocess.check_output(adb + ["get-state"], text=True).strip() != "device":
        raise SystemExit("The requested ADB device is unavailable")
    subprocess.run(adb + ["shell", "pm", "path", PACKAGE], check=True, capture_output=True)
    subprocess.run(adb + ["shell", "run-as", PACKAGE, "id"], check=True, capture_output=True)
    return adb


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
