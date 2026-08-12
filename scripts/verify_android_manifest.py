#!/usr/bin/env python3
"""Fail-closed identity and permission checks for a decoded Android manifest."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ANDROID = "{http://schemas.android.com/apk/res/android}"
EDITION_PATTERN = re.compile(r"^[a-z0-9_]+$")


def fail(message: str) -> "NoReturn":
    print(message, file=sys.stderr)
    raise SystemExit(1)


def read_permission_baseline(path: Path) -> set[str]:
    if not path.is_file():
        fail(f"Permission baseline was not found: {path}")
    return {
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--package", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True)
    parser.add_argument("--edition", required=True)
    parser.add_argument("--permission-baseline", required=True, type=Path)
    parser.add_argument("--forbid-permission", action="append", default=[])
    args = parser.parse_args()

    if not EDITION_PATTERN.fullmatch(args.edition):
        fail(f"Invalid expected edition: {args.edition}")
    try:
        root = ET.parse(args.manifest).getroot()
    except (ET.ParseError, OSError) as error:
        fail(f"Unable to parse final Android manifest: {error}")

    actual_package = root.get("package", "")
    actual_version_name = root.get(f"{ANDROID}versionName", "")
    actual_version_code = root.get(f"{ANDROID}versionCode", "")
    if actual_package != args.package:
        fail(f"Package mismatch: expected {args.package}, got {actual_package or '<missing>'}")
    if actual_version_name != args.version_name:
        fail(
            "versionName mismatch: "
            f"expected {args.version_name}, got {actual_version_name or '<missing>'}"
        )
    if actual_version_code != args.version_code:
        fail(
            "versionCode mismatch: "
            f"expected {args.version_code}, got {actual_version_code or '<missing>'}"
        )

    applications = root.findall("application")
    if len(applications) != 1:
        fail(f"Expected exactly one application node, found {len(applications)}")
    application = applications[0]
    if application.get(f"{ANDROID}debuggable", "false").lower() == "true":
        fail("Release artifact is debuggable")
    if application.get(f"{ANDROID}testOnly", "false").lower() == "true":
        fail("Release artifact is testOnly")

    marker_prefix = "cn.com.omnimind.bot.EDITION."
    expected_marker = f"{marker_prefix}{args.edition}"
    edition_markers = [
        (
            node.get(f"{ANDROID}name", ""),
            node.get(f"{ANDROID}value", "").lower(),
        )
        for node in application.findall("meta-data")
        if node.get(f"{ANDROID}name", "").startswith(marker_prefix)
    ]
    if edition_markers != [(expected_marker, "true")]:
        fail(
            "Final manifest must contain exactly one true edition marker: "
            f"{expected_marker}"
        )

    allowed_permission_tags = {
        "uses-permission",
        "uses-permission-sdk-23",
        "uses-permission-sdk-m",
    }
    permission_nodes = [node for node in root if node.tag.startswith("uses-permission")]
    unexpected_tags = sorted(
        {node.tag for node in permission_nodes if node.tag not in allowed_permission_tags}
    )
    if unexpected_tags:
        fail("Unsupported permission manifest tag: " + ",".join(unexpected_tags))

    actual_permissions = {
        "|".join(
            (
                node.tag,
                node.get(f"{ANDROID}name", ""),
                node.get(f"{ANDROID}maxSdkVersion", ""),
                node.get(f"{ANDROID}usesPermissionFlags", ""),
            )
        )
        for node in permission_nodes
        if node.get(f"{ANDROID}name", "")
    }
    expected_permissions = read_permission_baseline(args.permission_baseline)
    missing = sorted(expected_permissions - actual_permissions)
    unexpected = sorted(actual_permissions - expected_permissions)
    if missing or unexpected:
        details: list[str] = []
        if missing:
            details.append("missing=" + ",".join(missing))
        if unexpected:
            details.append("unexpected=" + ",".join(unexpected))
        fail("Final manifest permission set differs from reviewed baseline: " + " ".join(details))

    actual_permission_names = {entry.split("|", 2)[1] for entry in actual_permissions}
    forbidden = sorted(set(args.forbid_permission) & actual_permission_names)
    if forbidden:
        fail("Forbidden permission present: " + ",".join(forbidden))

    print("Android manifest identity and permissions: PASS")


if __name__ == "__main__":
    main()
