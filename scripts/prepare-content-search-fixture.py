#!/usr/bin/env python3
"""Prepare only a synthetic large HTML file; no conversation or runtime writes."""
import hashlib, json, re, subprocess, sys, tempfile, uuid
serial, = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+', serial)
base = ['adb', '-s', serial]
root = 'workspace/oob-content-search-regression'
path = root + '/large.html'
with tempfile.NamedTemporaryFile() as sample:
    sample.write(b'x' * 8190 + b'cross_boundary_needle')
    for _ in range(256): sample.write(b'z' * 65536)
    sample.write(b'tail_needle')
    size = sample.tell()
    sample.seek(0)
    digest = hashlib.file_digest(sample, 'sha256').hexdigest()
    exists = subprocess.run(base + ['shell', 'run-as', 'cn.com.omnimind.bot', 'test', '-e', path]).returncode == 0
    if not exists:
        subprocess.run(base + ['shell', 'run-as', 'cn.com.omnimind.bot', 'mkdir', '-p', root], check=True)
        sample.flush()
        remote = '/data/local/tmp/oob-content-search-' + uuid.uuid4().hex
        try:
            subprocess.run(base + ['push', sample.name, remote], check=True, stdout=subprocess.DEVNULL, timeout=60)
            subprocess.run(base + ['shell', 'chmod', '644', remote], check=True)
            subprocess.run(base + ['shell', 'run-as', 'cn.com.omnimind.bot', 'cp', remote, path], check=True)
        finally:
            subprocess.run(base + ['shell', 'rm', '-f', remote], check=True)
    actual = subprocess.check_output(base + ['shell', 'run-as', 'cn.com.omnimind.bot', 'sha256sum', path], text=True).split()[0]
    assert actual == digest, 'Existing fixture differs; will not overwrite it'
    print(json.dumps({'serial': serial, 'path': '/workspace/oob-content-search-regression/large.html', 'bytes': size, 'sha256': digest}))
