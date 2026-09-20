#!/usr/bin/env python3
"""Physical sidebar regression. Start with the Xiaowan drawer open.
Switch lists twice, open a selected synthetic remote session, and verify history.
No prompts are sent; no application data or device connections are changed.
"""
import json, os, re, subprocess, sys, time, xml.etree.ElementTree as ET
assert os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1'
serial, title, expected = sys.argv[1:]
assert title.startswith('OOB '), 'Use an isolated acceptance session'
def adb(*args):
    return subprocess.check_output(['adb', '-s', serial, *args], text=True, timeout=15)
def snapshot():
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/oob-sidebar-regression.xml')
    if 'dumped to' not in result: return []
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/oob-sidebar-regression.xml')).iter('node'))
def text(n): return n.get('text') or n.get('content-desc') or ''
def wait(predicate):
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        nodes = snapshot()
        match = next((n for n in nodes if predicate(text(n))), None)
        if match is not None: return match
    raise AssertionError('Expected sidebar/history state did not appear')
def tap(node):
    a,b,c,d = map(int,re.findall(r'\d+',node.get('bounds')))
    adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
for _ in range(2):
    tap(wait(lambda t:t=='本机'))
    assert not any((text(n)==title or text(n).startswith(title+'\n')) for n in snapshot()), 'Remote list remained on phone tab'
    tap(wait(lambda t:t=='电脑'))
    wait(lambda t:(t==title or t.startswith(title+'\n')))
row = wait(lambda t:(t==title or t.startswith(title+'\n')))
a,b,c,d = map(int,re.findall(r'\d+',row.get('bounds')))
# Tap toward the row's right edge, including blank space beyond the title.
adb('shell','input','tap',str(c-12),str((b+d)//2))
wait(lambda t:expected in t)
assert not any(text(n)=='电脑' for n in snapshot()), 'Drawer did not close after selecting session'
version = re.findall(r'version(?:Code|Name)=[^\s]+',adb('shell','dumpsys','package','cn.com.omnimind.bot'))
print(json.dumps({'result':'PASS','device':serial,'version':version,'sessionTitle':title,
 'scope':'physical drawer phone/computer switch twice and selected remote history; no prompt or native desktop sync tested'},ensure_ascii=False))
