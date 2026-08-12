#!/usr/bin/env python3
"""Fail closed if selected native channels expose raw failures or sensitive details."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHANNEL_DIR = ROOT / "app/src/main/java/cn/com/omnimind/bot/ui/channel"
SCOPED_FILES = (
    "AccountChannel.kt",
    "AgentRuntimeChannel.kt",
    "AppStateChannel.kt",
    "AppUpdateChannel.kt",
    "BrowserSessionChannel.kt",
    "CacheChannel.kt",
    "DeviceInfoChannel.kt",
    "FileSaveChannel.kt",
    "HideFromRecentsChannel.kt",
    "HttpChannel.kt",
    "McpServerChannel.kt",
    "OverlayChannel.kt",
    "PdfPreviewChannel.kt",
)

RAW_EXCEPTION_PATTERNS = (
    re.compile(r"catch\s*\([^)]*:\s*Throwable\b"),
    re.compile(r"\brunCatching\s*\{"),
    re.compile(r"\b(?:e|error|t|it)\.(?:message|localizedMessage)\b"),
    re.compile(r"\b(?:e|error|t|it)\.toString\s*\("),
    re.compile(r"\b(?:e|error|t|it)\.javaClass\b"),
    re.compile(r"\bprintStackTrace\s*\("),
)


def extract_calls(source: str, marker: str) -> list[str]:
    calls: list[str] = []
    start = 0
    while True:
        marker_index = source.find(marker, start)
        if marker_index < 0:
            return calls
        open_index = source.find("(", marker_index + len(marker))
        if open_index < 0:
            return calls
        depth = 0
        quote: str | None = None
        escaped = False
        index = open_index
        while index < len(source):
            char = source[index]
            if quote is not None:
                if escaped:
                    escaped = False
                elif char == "\\":
                    escaped = True
                elif char == quote:
                    quote = None
            elif char in {'"', "'"}:
                quote = char
            elif char == "(":
                depth += 1
            elif char == ")":
                depth -= 1
                if depth == 0:
                    calls.append(source[marker_index : index + 1])
                    start = index + 1
                    break
            index += 1
        else:
            calls.append(source[marker_index:])
            return calls


def strip_string_literals(value: str) -> str:
    return re.sub(r'"(?:\\.|[^"\\])*"', '""', value, flags=re.DOTALL)


def check_file(path: Path) -> list[str]:
    source = path.read_text(encoding="utf-8")
    failures: list[str] = []

    for pattern in RAW_EXCEPTION_PATTERNS:
        if pattern.search(source):
            failures.append(f"{path.name}: forbidden raw exception pattern {pattern.pattern!r}")

    for call in extract_calls(source, "result.error"):
        if call == "result.error(failure.code, failure.message, failure.details)":
            continue
        code_only = strip_string_literals(call[call.find("(") + 1 : -1])
        if re.search(
            r"\b(?:sourcePath|path|url|uri|token|body|response|content|mimeType|safeMimeType)\b",
            code_only,
            flags=re.IGNORECASE,
        ):
            failures.append(f"{path.name}: result.error contains a sensitive runtime field")
        if re.search(r"\b(?:e|error|t|it)\b", code_only):
            failures.append(f"{path.name}: result.error contains an exception object")

    for marker in ("OmniLog.e", "OmniLog.w", "OmniLog.i", "OmniLog.d", "OmniLog.v"):
        for call in extract_calls(source, marker):
            code_only = strip_string_literals(call[call.find("(") + 1 : -1])
            if re.search(r",\s*(?:e|error|t|it)\s*[,)]", code_only):
                failures.append(f"{path.name}: log call contains an exception object")
            interpolations = re.findall(r"\$(?:\{([^}]+)\}|([A-Za-z_][A-Za-z0-9_]*))", call)
            for braced, plain in interpolations:
                expression = (braced or plain).strip()
                if expression not in {"exclude", "maxRetries", "failure.code", "safeCode"}:
                    failures.append(
                        f"{path.name}: log interpolation is not an approved bool/count/code: {expression}"
                    )

    return failures


def main() -> int:
    failures: list[str] = []
    for name in SCOPED_FILES:
        path = CHANNEL_DIR / name
        if not path.is_file():
            failures.append(f"missing scoped channel: {path}")
            continue
        failures.extend(check_file(path))

    test_path = (
        ROOT
        / "app/src/test/java/cn/com/omnimind/bot/ui/channel/NativeChannelErrorPrivacyTest.kt"
    )
    if not test_path.is_file():
        failures.append("missing NativeChannelErrorPrivacyTest.kt")
    else:
        test_source = test_path.read_text(encoding="utf-8")
        for canary in ("private.example.invalid", "private.txt", "canary-token"):
            if canary not in test_source:
                failures.append(f"privacy test is missing canary {canary!r}")

    if failures:
        print("Native channel error privacy gate FAILED:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print(f"Native channel error privacy gate passed ({len(SCOPED_FILES)} channels).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
