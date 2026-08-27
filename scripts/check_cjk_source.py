#!/usr/bin/env python3
"""Fail when unintended CJK characters are introduced into OmniBot-owned source/docs."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
PATTERN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

EXCLUDED_PARTS = {
    ".git", ".dart_tool", "build", "node_modules", "dist", "target",
    "generated", "ReTerminal",
}
EXCLUDED_FILES = {"README.zh-CN.md"}
INCLUDED_SUFFIXES = {
    ".kt", ".java", ".dart", ".json", ".yaml", ".yml", ".xml", ".ts", ".tsx",
    ".js", ".jsx", ".md", ".html", ".css", ".gradle", ".kts", ".properties",
}


def should_scan(path: Path) -> bool:
    if path.name in EXCLUDED_FILES:
        return False
    if path.suffix.lower() not in INCLUDED_SUFFIXES:
        return False
    return not any(part in EXCLUDED_PARTS for part in path.parts)


hits = []
for path in ROOT.rglob("*"):
    if not path.is_file() or not should_scan(path):
        continue
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    for line_no, line in enumerate(text.splitlines(), 1):
        if PATTERN.search(line):
            hits.append((path.relative_to(ROOT), line_no, line.strip()))

if hits:
    print("Unintended CJK text detected in OmniBot-owned source/documentation:")
    for path, line_no, line in hits:
        print(f"{path}:{line_no}: {line}")
    print(f"\nTotal matches: {len(hits)}")
    print("Translate the text or explicitly exclude a third-party/generated resource with a documented reason.")
    sys.exit(1)

print("CJK source audit passed: no unintended Chinese/CJK characters found.")
