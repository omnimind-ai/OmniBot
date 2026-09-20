#!/usr/bin/env python3
"""Verify real app startup preserves a synthetic skill-local learning file.
Usage: python3 scripts/verify-provider-learning-persistence.py SERIAL
Install the candidate APK first. This restarts the app but changes no provider settings.
This is a persistence check, not model/GUI acceptance.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

serial = sys.argv[1]
package = 'cn.com.omnimind.bot'
root = 'workspace/.omnibot/skills/self-improving-agent'
marker = f'{root}/data/regression-{uuid.uuid4().hex}.txt'
value = b'verified synthetic learning persistence\n'

def adb(*args, data=None):
    return subprocess.run(['adb', '-s', serial, *args], input=data,
                          check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          timeout=40).stdout

expected = hashlib.sha256(Path('app/src/main/assets/builtin_skills/self-improving-agent/scripts/check-provider.mjs').read_bytes()).hexdigest()
adb('shell', 'am', 'force-stop', package)
adb('shell', 'run-as', package, 'mkdir', '-p', f'{root}/data')
adb('shell', 'run-as', package, 'sh', '-c', f"'cat > {marker}'", data=value)
try:
    for _ in range(2):
        adb('shell', 'run-as', package, 'rm', '-f', f'{root}/scripts/check-provider.mjs')
        adb('shell', 'am', 'start', '-W', '-n', package + '/.activity.MainActivity')
        deadline = time.monotonic() + 30
        while True:
            try:
                script = adb('shell', 'run-as', package, 'cat', f'{root}/scripts/check-provider.mjs')
                if hashlib.sha256(script).hexdigest() == expected:
                    break
            except subprocess.CalledProcessError:
                pass
            if time.monotonic() > deadline:
                raise AssertionError('Updated builtin skill did not appear')
            time.sleep(0.5)
        # Asset is copied after refresh deletion, so observing its current hash
        # establishes the canary survived the actual startup refresh.
        assert adb('shell', 'run-as', package, 'cat', marker) == value
        adb('shell', 'am', 'force-stop', package)
    version = adb('shell', 'dumpsys', 'package', package).decode()
    versions = [line.strip() for line in version.splitlines() if 'versionName=' in line or 'versionCode=' in line]
    print(json.dumps({'serial': serial, 'version': versions, 'startupCycles': 2,
                      'learningPreserved': True, 'scope': 'skill persistence only'}))
finally:
    adb('shell', 'run-as', package, 'rm', '-f', marker)
    adb('shell', 'am', 'start', '-n', package + '/.activity.MainActivity')
