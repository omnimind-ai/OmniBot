#!/usr/bin/env python3
"""Actual generated App UI acceptance; no synthetic bridge calls or DB writes."""
import json,os,re,subprocess,sys,time,uuid,shlex,xml.etree.ElementTree as E
from pathlib import Path
serial,out,*prior=sys.argv[1:]
assert re.fullmatch(r'[A-Za-z0-9._:-]+',serial)
out=Path(out);out.mkdir(parents=True,exist_ok=True)
marker=os.environ.get('VIBE_DEMO_NOTE') or 'VIBE_UI_'+uuid.uuid4().hex[:12]
assert re.fullmatch(r'[A-Za-z0-9 _.-]{1,100}',marker), 'Use a safe ASCII demo note'
report={'serial':serial,'physical':not serial.startswith('emulator-'),'marker':marker,'passed':False,'steps':[]}
def adb(*a):return subprocess.check_output(['adb','-s',serial,*a],timeout=30)
def nodes():
 path='/data/local/tmp/oob-vibe-ui.xml'
 assert b'dumped to:' in adb('shell','uiautomator','dump',path)
 return list(E.fromstring(adb('shell','cat',path)).iter('node'))
def wait(predicate):
 deadline=time.monotonic()+30
 while time.monotonic()<deadline:
  try:
   result=[n for n in nodes() if predicate(n)]
   if result:return result
  except (subprocess.CalledProcessError,AssertionError):pass
  time.sleep(.5)
 raise AssertionError('Expected UI not visible')
def tap(n):
 a,b,c,d=map(int,re.findall(r'\d+',n.get('bounds')))
 adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
def label(n):return n.get('text') or n.get('content-desc') or ''
def shot(name):
 (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 report['steps'].append(name)
def open_app():
 adb('shell','am','start','-n','cn.com.omnimind.bot/.activity.MainActivity','--es','route','/home/plugin_market/local.project.vibe-notes-test')
 tap(wait(lambda n: label(n) in ('Open App','打开 App') and n.get('clickable')=='true')[0])
 wait(lambda n: label(n)=='Vibe Notes Test')
try:
 open_app();shot('opened')
 if prior:
  wait(lambda n:label(n)==prior[0]);shot('prior-note-preserved-after-publish')
 field=wait(lambda n:n.get('class')=='android.widget.EditText')[0]
 tap(field);adb('shell','input','text',marker.replace(' ','%s'))
 tap(wait(lambda n:label(n)=='Save Note' and n.get('clickable')=='true')[0])
 wait(lambda n:label(n)==marker);shot('saved')
 query="select count(*) from notes where title='"+marker+"';"
 count=adb('shell',shlex.join(['run-as','cn.com.omnimind.bot','sqlite3','files/plugin-data/local.project.vibe-notes-test/project.db',query])).decode().strip()
 assert count=='1','UI save did not insert exactly one SQLite row'
 report['sqliteCount']=1
 adb('shell','am','force-stop','cn.com.omnimind.bot')
 adb('shell','am','start','-W','-n','cn.com.omnimind.bot/.activity.LauncherActivity')
 open_app();wait(lambda n:label(n)==marker);shot('reopened-with-persisted-note')
 report['passed']=True
except Exception as error:
 report['error']=str(error)
 shot('failure')
 raise
finally:
 (out/'result.json').write_text(json.dumps(report,indent=2))
 print(json.dumps(report))
