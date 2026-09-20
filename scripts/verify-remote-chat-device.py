"""Remote Codex composer/reply acceptance on the currently open device demo.

OOB_ALLOW_PHYSICAL_DEVICE=1 python3 scripts/verify-remote-chat-device.py SERIAL
  --expect OOB_MARKER --output PATH [--send 'Task ending in OOB_MARKER']
  [--restart]
Send once through the real composer; never resubmit on timeout. Without --send,
verify passive external response or reopened history. No credentials are read.
UIAutomator is unsuitable while an accessibility recording/task is active.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('serial')
p.add_argument('--expect', required=True)
p.add_argument('--output', required=True, type=Path)
p.add_argument('--send')
p.add_argument('--restart', action='store_true', help='Restart the idle app and require automatic history restoration; no navigation or resend')
a = p.parse_args()
assert re.fullmatch(r'emulator-\d+', a.serial) or os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1', 'Physical device requires explicit opt-in'
assert re.fullmatch(r'OOB_[A-Z0-9_]+', a.expect)
assert not (a.send and a.restart), 'Send and restart are separate acceptance steps'

def adb(*args):
    return subprocess.check_output(['adb', '-s', a.serial, *args], timeout=20)

def nodes():
    # Observation may fail while Android tears down a UIAutomation connection.
    # Retry only the read; never repeat composer input or a submitted request.
    for attempt in range(3):
        try:
            adb('shell', 'rm', '-f', '/sdcard/oob-remote-demo.xml')
            adb('shell', 'uiautomator', 'dump', '/sdcard/oob-remote-demo.xml')
            return list(ET.fromstring(adb('shell', 'cat', '/sdcard/oob-remote-demo.xml')).iter('node'))
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ET.ParseError):
            if attempt == 2:
                raise
            time.sleep(1)

def label(n):
    return n.get('text') or n.get('content-desc') or ''

def tap(n):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

try:
    if a.restart:
        ns = nodes()
        assert not any(label(n).split('\n')[0] in ('Stop', '停止', '停止生成') for n in ns), 'Cannot restart an active turn'
        assert any(n.get('class') != 'android.widget.EditText' and
                   label(n).strip().splitlines()[0:1] == [a.expect] for n in ns), 'Original reply must be visible before restart'
        a.output.parent.mkdir(parents=True, exist_ok=True)
        a.output.with_suffix('.before.png').write_bytes(adb('exec-out', 'screencap', '-p'))
        adb('shell', 'am', 'force-stop', 'cn.com.omnimind.bot')
        adb('shell', 'am', 'start', '-n', 'cn.com.omnimind.bot/.activity.LauncherActivity')
    if a.send:
        assert a.expect in a.send and a.send.isascii()
        ns = nodes()
        fields = [n for n in ns if n.get('class') == 'android.widget.EditText']
        assert len(fields) == 1 and not label(fields[0]), 'Require an empty demo composer'
        assert not any(a.expect in label(n) for n in ns), 'Fresh marker required'
        tap(fields[0])
        # Long single input-text events can be truncated during Flutter/IME
        # updates. Preserve the actual composer path and verify it before send.
        for character in a.send:
            adb('shell', 'input', 'text', shlex.quote('%s' if character == ' ' else character))
        adb('shell', 'input', 'keyevent', '4')
        ns = nodes()
        assert any(n.get('class') == 'android.widget.EditText' and label(n) == a.send for n in ns)
        sends = [n for n in ns if label(n) in ('发送', 'Send') and n.get('enabled') == 'true']
        assert len(sends) == 1
        tap(sends[0])
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        ns = nodes()
        replies = [n for n in ns if n.get('class') != 'android.widget.EditText'
                   and label(n).strip().splitlines()[0:1] == [a.expect]]
        if replies and not any(label(n).split('\n')[0] in ('Stop', '停止', '停止生成') for n in ns):
            break
        time.sleep(1)
    else:
        raise AssertionError('No visible assistant reply before deadline; request not resent')
    result = dict(passed=True, serial=a.serial, marker=a.expect,
                  sentFromPhone=bool(a.send), automaticRestartRestore=a.restart,
                  visibleReplies=len(replies))
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.with_suffix('.after.png').write_bytes(adb('exec-out', 'screencap', '-p'))
except Exception as e:
    result = dict(passed=False, serial=a.serial, marker=a.expect, error=str(e))
    raise
finally:
    a.output.parent.mkdir(parents=True, exist_ok=True)
    a.output.write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result))
