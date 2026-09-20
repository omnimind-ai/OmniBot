#!/usr/bin/env python3
"""Attach a synthetic test attachment through the real Android file picker; no send.
Usage: python3 scripts/prepare-chat-image.py SERIAL
Requires an idle chat and the system file picker showing Downloads/Recent.
"""
import re
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path

serial = sys.argv[1]
assert re.fullmatch(r'[A-Za-z0-9._:-]+', serial)
name = sys.argv[2] if len(sys.argv) > 2 else 'chat-upload-colors.png'
assert name in ('chat-upload-colors.png', 'chat-upload-colors-second.png', 'chat-upload-record.txt', 'chat-upload-invoice.pdf', 'chat-upload-office.docx', 'chat-upload-office.xlsx', 'chat-upload-broken.pdf', 'chat-upload-scan.pdf', 'chat-upload-password.pdf')

def adb(*args):
    return subprocess.check_output(['adb', '-s', serial, *args], timeout=30)

def nodes():
    path = '/data/local/tmp/oob-upload-' + uuid.uuid4().hex + '.xml'
    try:
        result = adb('shell', 'uiautomator', 'dump', path)
        assert b'dumped to:' in result, 'Fresh UI hierarchy unavailable'
        return list(ET.fromstring(adb('shell', 'cat', path)).iter('node'))
    finally:
        adb('shell', 'rm', '-f', path)

def tap(node):
    a,b,c,d = map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((a+c)//2),str((b+d)//2))

initial = nodes()
assert not any(n.get('content-desc') in ('Stop','停止') for n in initial), 'Chat must be idle'
buttons = [n for n in initial if n.get('content-desc') in ('Add attachment','添加附件')]
assert len(buttons)==1, 'Open chat with attachment button first'
adb('push', str(Path(__file__).parent/'fixtures'/name), '/sdcard/Download/'+name)
adb('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE',
    '-d','file:///sdcard/Download/'+name)
tap(buttons[0])
deadline=time.monotonic()+20
image_filter_applied = False
while True:
    current = nodes()
    matches=[n for n in current if n.get('text')==name]
    if len(matches)==1:
        tap(matches[0]); break
    # Recent documents can push an older synthetic image off-screen. Select the
    # real picker category once; still require the exact filename before tapping.
    if name.endswith('.png') and not image_filter_applied:
        categories = [n for n in current if n.get('text') in ('Images', '图片')]
        if len(categories) == 1:
            tap(categories[0]); image_filter_applied = True
            continue
    assert time.monotonic()<deadline, 'Synthetic attachment not visible in file picker; no fallback click'
    time.sleep(.5)
assert any(n.get('content-desc') in ('Send','发送') for n in nodes()), 'Picker did not return to chat'
print('Synthetic attachment selected through file picker; not sent')
