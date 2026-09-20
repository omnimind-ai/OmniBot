#!/usr/bin/env python3
"""Run synthetic Android/Room attachment regression on an installed debug APK."""
import argparse
import json
import hashlib
import subprocess
import time
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--serial', required=True)
p.add_argument('--output', required=True)
p.add_argument('--apk', required=True)
a = p.parse_args()
package = 'cn.com.omnimind.bot'
def adb(*args):
    return subprocess.check_output(['adb', '-s', a.serial, *args], timeout=40, stderr=subprocess.STDOUT).decode()
def phase(name):
    adb('shell', 'run-as', package, 'rm', '-f', 'files/attachment-regression-result.json')
    adb('shell', 'am', 'broadcast', '-n', package + '/.debug.DebugAttachmentPersistenceReceiver', '--es', 'phase', name)
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        try:
            result = json.loads(adb('shell', 'run-as', package, 'cat', 'files/attachment-regression-result.json'))
            assert result.get('phase') == name or not result.get('passed'), result
            return result
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            time.sleep(.5)
    raise TimeoutError(name)
def installed_hash():
    paths = adb('shell', 'pm', 'path', package).splitlines()
    path = next(line.removeprefix('package:') for line in paths if line.endswith('/base.apk'))
    return adb('shell', 'sha256sum', path).split()[0]
expected = hashlib.sha256(Path(a.apk).read_bytes()).hexdigest()
assert installed_hash() == expected, 'Installed APK differs from expected build'
report = {'serial': a.serial, 'model': adb('shell', 'getprop', 'ro.product.model').strip(),
          'apkSha256': expected, 'physical': adb('shell', 'getprop', 'ro.kernel.qemu').strip() != '1', 'phases': []}
try:
    for name in ['seed', 'reload', 'missing-and-recover']:
        if name == 'reload':
            adb('shell', 'am', 'force-stop', package)
            adb('shell', 'am', 'start', '-W', '-n', package + '/.activity.LauncherActivity')
        result = phase(name)
        report['phases'].append(result)
        assert result['passed'], result
finally:
    try: report['cleanup'] = phase('cleanup')
    except Exception as error: report['cleanup'] = {'passed': False, 'error': str(error)}
    report['installedApkUnchanged'] = installed_hash() == expected
    Path(a.output).parent.mkdir(parents=True, exist_ok=True)
    Path(a.output).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(report, ensure_ascii=False))
assert report['cleanup']['passed']
assert report['installedApkUnchanged']
