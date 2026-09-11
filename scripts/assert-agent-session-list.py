#!/usr/bin/env python3
"""Assert visible local session counters after a user enters the session list."""
import argparse, os, re, subprocess, xml.etree.ElementTree as ET, json
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('serial');p.add_argument('--running',type=int,required=True);p.add_argument('--loaded',type=int,required=True)
p.add_argument('--archived',type=int,default=0)
a=p.parse_args()
assert re.fullmatch(r'emulator-\d+',a.serial) or os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE')=='1'
def adb(*args):
    return subprocess.check_output(['adb','-s',a.serial,*args],timeout=30).decode()
path='/data/local/tmp/oob-session-counters.xml'
assert 'dumped to:' in adb('shell','uiautomator','dump',path)
root=ET.fromstring(adb('exec-out','cat',path))
labels=[n.get('content-desc','') for n in root.iter('node') if n.get('package')=='cn.com.omnimind.bot']
assert 'Local Agent Sessions' in labels, 'Session page is not visible'
status=[s for s in labels if s.startswith('Runtime\n')]
assert len(status)==1, 'Missing unique runtime statistics'
assert 'Ready · connected' in status[0]
assert f'\n{a.running}\nRunning\n{a.loaded}\nLoaded\n' in status[0],status[0]
assert f'\n{a.archived}\nArchived' in status[0],status[0]
print(json.dumps({'passed':True,'running':a.running,'loaded':a.loaded,'archived':a.archived}))
