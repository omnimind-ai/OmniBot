#!/usr/bin/env python3
"""Start with the computer sidebar open and select an unavailable session.

Usage: OOB_ALLOW_PHYSICAL_DEVICE=1 python3 scripts/verify-phone-remote-load-failure.py SERIAL TITLE
This read-only regression never sends a prompt or changes connection settings.
The backend must reject loading the selected session; a successful load is not
a valid execution of this failure-path test.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

assert os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1'
serial, title = sys.argv[1:]

def adb(*args):
    return subprocess.check_output(
        [os.environ.get('ADB', 'adb'), '-s', serial, *args],
        text=True, timeout=30,
    )

dump_path = '/sdcard/oob-load-failure.xml'
assert 'dumped to' in adb('shell', 'uiautomator', 'dump', dump_path)
nodes = ET.fromstring(adb('shell', 'cat', dump_path)).iter('node')
matches = [n for n in nodes if
           (n.get('text') or n.get('content-desc') or '').split('\n')[0] == title
           and n.get('clickable') == 'true']
assert len(matches) == 1, 'Open the computer sidebar with the target row visible'
pid = adb('shell', 'pidof', 'cn.com.omnimind.bot').strip()
started = adb('shell', 'date', '+%m-%dT%H:%M:%S.000').strip().replace('T', ' ')
a, b, c, d = map(int, re.findall(r'\d+', matches[0].get('bounds')))
adb('shell', 'input', 'tap', str((a+c)//2), str((b+d)//2))
deadline = time.monotonic() + 40
while True:
    log = adb('logcat', '-d', '--pid=' + pid, '-T', started)
    if 'ACP request failed method=session/load' in log:
        break
    assert time.monotonic() < deadline, 'Expected session/load rejection was not observed'
    time.sleep(1)
# Cover the previously observed configuration requests 1–2 seconds after failure.
time.sleep(5)
log = adb('logcat', '-d', '--pid=' + pid, '-T', started)
after = log.split('ACP request failed method=session/load', 1)[1]
assert 'config/read' not in after and 'model/list' not in after, \
    'Failed admission continued with configuration/model requests'
assert 'ACP request failed method=session/prompt' not in after
assert adb('shell', 'pidof', 'cn.com.omnimind.bot').strip() == pid
print(json.dumps({'result': 'PASS', 'device': serial,
                  'scope': 'Physical session load rejection stops follow-up configuration; no successful session access claimed'}))
