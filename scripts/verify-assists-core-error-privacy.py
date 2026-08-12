#!/usr/bin/env python3
"""Guard AssistsCoreManager channel failures and logs against secret-bearing exceptions."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_PATH = (
    ROOT
    / "app/src/main/java/cn/com/omnimind/bot/manager/AssistsCoreManager.kt"
)
TEST_PATH = (
    ROOT
    / "app/src/test/java/cn/com/omnimind/bot/manager/AssistsCoreChannelErrorPrivacyTest.kt"
)
EXCLUDED_FUNCTIONS = (
    "getInstalledApplications",
    "getInstalledApplicationsWithIconUpdate",
    "getDeskTopPackageName",
    "fetchProviderModels",
)
RAW_EXCEPTION_PATTERNS = (
    re.compile(r"catch\s*\([^)]*:\s*Throwable\b"),
    re.compile(r"\.onFailure\s*\{"),
    re.compile(
        r"\b(?:e|error|fallbackError|notificationError|persistenceError|"
        r"dispatchError|throwable|exception|it)\."
        r"(?:message|localizedMessage|javaClass|stackTrace)\b"
    ),
    re.compile(
        r"\b(?:e|error|fallbackError|notificationError|persistenceError|"
        r"dispatchError|throwable|exception|it)\.toString\s*\("
    ),
    re.compile(r"\bprintStackTrace\s*\("),
)
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"', re.DOTALL)


def closing_brace(source: str, open_index: int) -> int:
    depth = 0
    quote: str | None = None
    escaped = False
    line_comment = False
    block_comment = False
    index = open_index
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""
        if line_comment:
            if char == "\n":
                line_comment = False
        elif block_comment:
            if char == "*" and next_char == "/":
                block_comment = False
                index += 1
        elif quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
        elif char == "/" and next_char == "/":
            line_comment = True
            index += 1
        elif char == "/" and next_char == "*":
            block_comment = True
            index += 1
        elif char in {'"', "'"}:
            quote = char
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    raise ValueError("unterminated Kotlin block")


def mask_excluded_functions(source: str) -> tuple[str, list[str]]:
    masked = list(source)
    failures: list[str] = []
    for name in EXCLUDED_FUNCTIONS:
        match = re.search(rf"\bfun\s+{re.escape(name)}\s*\(", source)
        if match is None:
            failures.append(f"missing excluded concurrent function {name}")
            continue
        open_index = source.find("{", match.end())
        if open_index < 0:
            failures.append(f"cannot find body for excluded concurrent function {name}")
            continue
        try:
            end_index = closing_brace(source, open_index)
        except ValueError as error:
            failures.append(f"cannot mask {name}: {error}")
            continue
        for index in range(match.start(), end_index + 1):
            if masked[index] != "\n":
                masked[index] = " "
    return "".join(masked), failures


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


def split_top_level_args(call: str) -> list[str]:
    body = call[call.find("(") + 1 : -1]
    args: list[str] = []
    start = 0
    depths = {"(": 0, "[": 0, "{": 0}
    closing = {")": "(", "]": "[", "}": "{"}
    quote: str | None = None
    escaped = False
    for index, char in enumerate(body):
        if quote is not None:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue
        if char in {'"', "'"}:
            quote = char
        elif char in depths:
            depths[char] += 1
        elif char in closing:
            depths[closing[char]] -= 1
        elif char == "," and all(value == 0 for value in depths.values()):
            args.append(body[start:index].strip())
            start = index + 1
    tail = body[start:].strip()
    if tail:
        args.append(tail)
    return args


def is_string_literal(value: str) -> bool:
    return STRING_LITERAL.fullmatch(value.strip()) is not None


def check_result_errors(source: str) -> list[str]:
    failures: list[str] = []
    for call in extract_calls(source, "result.error"):
        args = split_top_level_args(call)
        if args == ["failure.code", "failure.message", "failure.details"]:
            continue
        if len(args) != 3:
            failures.append("result.error must have exactly three stable arguments")
            continue
        if not is_string_literal(args[0]):
            failures.append(f"result.error code is not a fixed literal: {args[0]!r}")
        if not is_string_literal(args[1]):
            failures.append(f"result.error message is not a fixed literal: {args[1]!r}")
        if args[2] != "null":
            failures.append(f"result.error details are not empty: {args[2]!r}")
    return failures


def approved_log_interpolation(expression: str) -> bool:
    if expression in {"isFinal", "delivered", "safeCode", "failure.code"}:
        return True
    return re.fullmatch(
        r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*\."
        r"(?:size|length|count\(\))",
        expression,
    ) is not None


def check_logs(source: str) -> list[str]:
    failures: list[str] = []
    for marker in ("OmniLog.e", "OmniLog.w", "OmniLog.i", "OmniLog.d", "OmniLog.v"):
        for call in extract_calls(source, marker):
            args = split_top_level_args(call)
            if args in (["tag", "message"],):
                continue
            if len(args) != 2:
                failures.append(f"{marker} must not receive a Throwable or extra object")
                continue
            if not is_string_literal(args[1]):
                failures.append(f"{marker} message is not a fixed template: {args[1]!r}")
                continue
            interpolations = re.findall(
                r"\$(?:\{([^}]+)\}|([A-Za-z_][A-Za-z0-9_]*))",
                args[1],
            )
            for braced, plain in interpolations:
                expression = (braced or plain).strip()
                if not approved_log_interpolation(expression):
                    failures.append(
                        f"{marker} exposes non-count/non-bool runtime data: {expression!r}"
                    )
    return failures


def main() -> int:
    failures: list[str] = []
    if not SOURCE_PATH.is_file():
        failures.append(f"missing source: {SOURCE_PATH}")
        scoped_source = ""
    else:
        source = SOURCE_PATH.read_text(encoding="utf-8")
        scoped_source, mask_failures = mask_excluded_functions(source)
        failures.extend(mask_failures)
        for pattern in RAW_EXCEPTION_PATTERNS:
            match = pattern.search(scoped_source)
            if match is not None:
                line = scoped_source.count("\n", 0, match.start()) + 1
                failures.append(
                    f"AssistsCoreManager.kt:{line}: forbidden raw exception pattern "
                    f"{pattern.pattern!r}"
                )
        failures.extend(check_result_errors(scoped_source))
        failures.extend(check_logs(scoped_source))
        for required in (
            "if (error is CancellationException) throw error",
            'reporter("channel_failure code=$safeCode")',
        ):
            if required not in scoped_source:
                failures.append(f"missing privacy helper invariant: {required}")

    if not TEST_PATH.is_file():
        failures.append(f"missing test: {TEST_PATH}")
    else:
        test_source = TEST_PATH.read_text(encoding="utf-8")
        for canary in (
            "private.example.invalid",
            "C:\\\\Users\\\\owner",
            "canary-token",
            "canary-body",
        ):
            if canary not in test_source:
                failures.append(f"privacy test is missing canary {canary!r}")
        if "CancellationException" not in test_source or "assertThrows" not in test_source:
            failures.append("privacy test does not assert cancellation propagation")

    if failures:
        print("AssistsCoreManager error privacy gate FAILED:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1

    print("AssistsCoreManager error privacy gate passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
