#!/usr/bin/env python3
"""Actual PJE110 UI regression: open an isolated remote session from the list.
Coordinates for the unlabeled chat-island button were observed on this device.
Stops on unexpected state; never sends prompts or clears user data.
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

serial = sys.argv[1]
title = sys.argv[3] if len(sys.argv) > 3 else 'OOB new phone sync A6'
assert title.startswith('OOB '), 'Only isolated acceptance sessions'
def adb(*args):
    return subprocess.check_output(['adb', '-s', serial, *args], timeout=20, text=True)
assert adb('shell', 'getprop', 'ro.product.model').strip() == 'PJE110'
assert '1080x2376' in adb('shell', 'wm', 'size')
def snapshot():
    adb('shell', 'rm', '-f', '/sdcard/oob-regression.xml')
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/oob-regression.xml')
    if 'dumped to' not in result:
        return []
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/oob-regression.xml')).iter('node'))
def wait_for(predicate):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        nodes = snapshot()
        matches = [n for n in nodes if predicate(n.get('text') or n.get('content-desc') or '')]
        if matches: return matches[0]
    raise RuntimeError('Expected test UI state not reached')
adb('shell', 'monkey', '-p', 'cn.com.omnimind.bot', '-c', 'android.intent.category.LAUNCHER', '1')
wait_for(lambda text: text == '切换聊天模式')
# The initial activity can draw before its selected remote runtime is restored.
# Wait for that actual UI state before opening the session sheet.
wait_for(lambda text: '我是远程 Codex' in text or text.startswith('OOB_'))
adb('shell', 'input', 'tap', '455', '219')
row = wait_for(lambda text: text.startswith(title + '\n'))
x1, y1, x2, y2 = map(int, re.findall(r'\d+', row.get('bounds')))
adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
expected = sys.argv[2] if len(sys.argv) > 2 else 'OOB_NEW_PHONE_SYNC_A6'
assert re.fullmatch(r'OOB_[A-Z0-9_]+', expected)
wait_for(lambda text: text == expected or text.startswith(expected + '\n'))
print('PASS actual phone opened existing test session and restored reply:', title, expected)
