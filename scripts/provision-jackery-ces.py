#!/usr/bin/env python3
"""Provision an already-installed debug CES app from staff-owned local files."""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
from urllib.parse import urlparse


PACKAGE = "com.jackery.powerai.ces"


def validate_endpoint(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} is required.")
    parsed = urlparse(value)
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.fragment:
        raise ValueError(f"{label} must be a device/server HTTP(S) URL without userinfo or a fragment.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True, help="Private staff connection JSON")
    parser.add_argument("--device-image", type=Path, help="Approved final device PNG")
    parser.add_argument("--serial", help="ADB device serial when multiple phones are connected")
    parser.add_argument("--dry-run", action="store_true", help="Validate files without touching a phone")
    args = parser.parse_args()
    config_bytes = args.config.read_bytes()
    config = json.loads(config_bytes)
    if not isinstance(config, dict):
        raise ValueError("The configuration must be a JSON object.")
    validate_endpoint(config.get("modelApiBase"), "modelApiBase")
    if not isinstance(config.get("modelId"), str) or not config["modelId"].strip():
        raise ValueError("The exact modelId is required.")
    if config.get("statusUrl"):
        validate_endpoint(config["statusUrl"], "statusUrl")
    if "apiKey" in config and not isinstance(config["apiKey"], str):
        raise ValueError("apiKey must be a string.")
    search = config.get("searchMcp")
    if search is not None:
        if not isinstance(search, dict):
            raise ValueError("searchMcp must be an object.")
        validate_endpoint(search.get("endpointUrl"), "searchMcp.endpointUrl")
        if search.get("transport", "http") not in ("http", "sse", "auto"):
            raise ValueError("searchMcp.transport must be http, sse or auto.")
        if "bearerToken" in search and not isinstance(search["bearerToken"], str):
            raise ValueError("searchMcp.bearerToken must be a string.")
    image_bytes = args.device_image.read_bytes() if args.device_image else None
    if image_bytes is not None and not image_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        raise ValueError("The approved device image must be a PNG.")
    if args.dry_run:
        print("Staff files validated. No device was changed.")
        return
    adb = shutil.which("adb")
    if not adb:
        raise ValueError("adb was not found. Install Android platform-tools first.")
    prefix = [adb] + (["-s", args.serial] if args.serial else [])

    def run(*command, data=None):
        # Credentials enter via stdin only and are never printed or put into
        # process arguments. Only fixed app-owned paths enter the remote shell.
        subprocess.run(prefix + list(command), input=data, check=True, stdout=subprocess.DEVNULL)

    run("shell", "run-as", PACKAGE, "pwd")
    run("shell", "am", "force-stop", PACKAGE)
    run("shell", "run-as", PACKAGE, "mkdir", "-p", "files/jackery-ces")
    run("shell", "run-as", PACKAGE, "sh", "-c", "'umask 077; cat > files/jackery-ces/connection.json'", data=config_bytes)
    if image_bytes is not None:
        run("shell", "run-as", PACKAGE, "sh", "-c", "'umask 077; cat > files/jackery-ces/device.png'", data=image_bytes)
    print("CES staff configuration installed. Reopen Jackery Power AI, then verify the real device and web search.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError):
        # Do not echo a malformed credential-bearing JSON line or command data.
        print("Provisioning failed. Check the private JSON, approved PNG, and debug CES app/ADB connection.", file=sys.stderr)
        sys.exit(1)
