#!/usr/bin/env python3
"""Generate reproducible synthetic large-file inputs; never changes device data."""
import hashlib, json, random, struct, sys, zlib
from pathlib import Path
out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
def write(name, body):
    path = out / name
    if path.exists() and path.read_bytes() != body:
        raise SystemExit(f'Refusing to replace different input: {path}')
    path.write_bytes(body)
    return {'path':str(path.resolve()), 'bytes':len(body), 'sha256':hashlib.sha256(body).hexdigest()}
files = [write('large.html', ('<!doctype html><html><title>OOB regression</title><body><!--' +
    '0123456789abcdef' * 1024 * 1024 + '-->HTML_END</body></html>').encode()),
    write('notes.txt', 'first line\nsecond line\n第三行 😀\nTEXT_END\n'.encode()),
    write('sample.pdf', b'%PDF-1.4\n%\x00\xff\x10binary fixture\n%%EOF\n')]
# Valid 3000x2000 RGB PNG, deliberately incompressible; no private image input.
def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind+data))
rng = random.Random(1729)
raw = b''.join(b'\0'+rng.randbytes(3000*3) for _ in range(2000))
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR',struct.pack('>IIBBBBB',3000,2000,8,2,0,0,0)) + chunk(b'IDAT',zlib.compress(raw,0)) + chunk(b'IEND',b'')
files.append(write('large.png',png))
print(json.dumps({'files':files},indent=2))
