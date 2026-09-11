#!/usr/bin/env python3
"""Select only maintained test-file controls in Android DocumentsUI."""
import argparse, os, re, subprocess, xml.etree.ElementTree as ET
p=argparse.ArgumentParser();p.add_argument('serial');p.add_argument('label');a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial) or os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE')=='1'
assert a.label in ('Show roots','Downloads','oob-attachment-admission.txt')
def adb(*args):return subprocess.check_output(['adb','-s',a.serial,*args],timeout=30)
assert b'dumped to:' in adb('shell','uiautomator','dump','/data/local/tmp/oob-test-document.xml')
root=ET.fromstring(adb('shell','cat','/data/local/tmp/oob-test-document.xml'))
found=[]
for n in root.iter('node'):
    if n.get('package')!='com.google.android.documentsui':continue
    if a.label not in (n.get('text'),n.get('content-desc')):continue
    # DocumentsUI ListView handles row clicks at the adapter; child labels
    # may be enabled without clickable=true. Use the observed label bounds.
    if n.get('enabled')=='true' and n not in found:found.append(n)
assert len(found)==1, 'Expected one enabled document control'
x,y,X,Y=map(int,re.findall(r'\d+',found[0].get('bounds')))
adb('shell','input','tap',str((x+X)//2),str((y+Y)//2))
print('Selected test document control:',a.label)
