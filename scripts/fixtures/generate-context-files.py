#!/usr/bin/env python3
"""Generate deterministic, synthetic inputs for the existing file-read provider."""
import binascii
import hashlib
import json
from pathlib import Path
import random
import struct
import sys
import zlib

target = Path(sys.argv[1])
target.mkdir(parents=True, exist_ok=False)
(target / 'large.html').write_text(
    '<!doctype html><html><title>OOB regression</title><body><!--'
    + '0123456789abcdef' * 1024 * 1024 + '-->HTML_END</body></html>', encoding='utf8')
(target / 'notes.txt').write_text('first line\nsecond line\n第三行 😀\nTEXT_END\n', encoding='utf8')
(target / 'sample.pdf').write_bytes(b'%PDF-1.4\n%\x00\xff\x10binary fixture\n%%EOF\n')

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind + data) & 0xffffffff)

# Incompressible RGBA pixels exercise a ~17 MB original PNG, not a tiny flat image.
rng = random.Random(20260908)
pixels = b''.join(b'\x00' + rng.randbytes(2048 * 4) for _ in range(2048))
png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 2048, 2048, 8, 6, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(pixels, level=0)) + chunk(b'IEND', b''))
(target / 'large.png').write_bytes(png)
manifest = {p.name: {'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
            for p in sorted(target.iterdir())}
(target / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest))
