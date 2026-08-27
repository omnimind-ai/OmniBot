#!/usr/bin/env bash
set -euo pipefail

# Detect unintended CJK ideographs in source/documentation files.
# Generated/build/dependency directories are intentionally excluded.
ROOT="${1:-.}"
PATTERN='[\x{3400}-\x{4DBF}\x{4E00}-\x{9FFF}\x{F900}-\x{FAFF}]'

MATCHES=$(grep -RInP "$PATTERN" "$ROOT" \
  --exclude-dir=.git \
  --exclude-dir=.gradle \
  --exclude-dir=build \
  --exclude-dir=.dart_tool \
  --exclude-dir=.idea \
  --exclude-dir=node_modules \
  --exclude-dir=.next \
  --exclude-dir=dist \
  --exclude-dir=generated \
  --exclude='*.lock' \
  --exclude='*.png' \
  --exclude='*.jpg' \
  --exclude='*.jpeg' \
  --exclude='*.gif' \
  --exclude='*.webp' \
  --exclude='*.so' \
  --exclude='*.aar' \
  --exclude='*.jar' \
  --exclude='*.apk' \
  --exclude='*.aab' \
  2>/dev/null || true)

if [[ -n "$MATCHES" ]]; then
  echo "Unexpected CJK characters found in source/documentation:"
  printf '%s\n' "$MATCHES"
  echo
  echo "Translate OmniBot-owned UI/docs/comments or explicitly exclude a justified third-party/generated file."
  exit 1
fi

echo "CJK localization audit passed: no unintended CJK ideographs found."
