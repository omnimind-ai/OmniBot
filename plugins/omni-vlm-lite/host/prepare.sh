#!/bin/sh
# Package-owned dependencies. The Android host only executes this entrypoint.
set -eu
if ! python3 -c 'import numpy; from PIL import Image' >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 python3-numpy python3-pil || {
      apt-get update
      DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 python3-numpy python3-pil
    }
  elif command -v apk >/dev/null 2>&1; then
    apk add --no-cache python3 py3-numpy py3-pillow || {
      apk update
      apk add --no-cache python3 py3-numpy py3-pillow
    }
  else
    echo 'Unsupported package manager: install Python, NumPy and Pillow' >&2
    exit 1
  fi
fi
python3 -c 'import numpy; from PIL import Image'
