#!/bin/sh
set -eu
printf 'kernel=%s\n' "$(uname -s)"
printf 'architecture=%s\n' "$(uname -m)"
if [ -r /etc/os-release ]; then
  cat /etc/os-release
fi
for executable in apk apt-get node npm git curl wget; do
  if command -v "$executable" >/dev/null 2>&1; then
    printf '%s=available\n' "$executable"
  else
    printf '%s=missing\n' "$executable"
  fi
done
