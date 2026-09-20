#!/usr/bin/env python3
"""Actual UI acceptance for the model-generated Life XP demo; never writes DB."""
import json,re,shlex,subprocess,sys,time,xml.etree.ElementTree as E
from pathlib import Path
serial,out,*flags=sys.argv[1:];assert re.fullmatch(r'[A-Za-z0-9._:-]+',serial)
out=Path(out);out.mkdir(parents=True,exist_ok=True)
report={'serial':serial,'physical':not serial.startswith('emulator-'),'passed':False,'steps':[]}
def adb(*args):return subprocess.check_output(['adb','-s',serial,*args],timeout=30)
def snapshot():
 assert b'dumped to:' in adb('shell','uiautomator','dump','/data/local/tmp/life-xp-accept.xml')
 return E.fromstring(adb('shell','cat','/data/local/tmp/life-xp-accept.xml'))
def label(n):return n.get('text') or n.get('content-desc') or ''
def bounds(n):return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
def tap(n):
 a,b,c,d=bounds(n);adb('shell','input','tap',str((a+c)//2),str((b+d)//2))
def wait(find):
 end=time.monotonic()+25
 while time.monotonic()<end:
  try:r=snapshot();v=find(r)
  except (AssertionError,subprocess.CalledProcessError):
   time.sleep(.3);continue
  if v is not None and v is not False:return v
  time.sleep(.3)
 raise AssertionError('Expected UI missing')
def named(text):return wait(lambda r:next((n for n in r.iter('node') if label(n)==text),None))
def click(text):
 for _ in range(5):
  r=snapshot();matches=[n for n in r.iter('node') if label(n)==text and len(bounds(n))==4 and bounds(n)[3]>bounds(n)[1] and bounds(n)[1]>=128 and bounds(n)[3]<=2339]
  if matches:tap(matches[0]);return
  adb('shell','input','swipe','530','1850','530','750','350');time.sleep(.6)
 raise AssertionError('Missing visible button '+text)
def shot(name):
 (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'));report['steps'].append(name)
def sql(query):
 return adb('shell',shlex.join(['run-as','cn.com.omnimind.bot','sqlite3','files/plugin-data/local.project.life-xp-demo/project.db',query])).decode().strip()
def total():return int(sql('select coalesce(sum(h.xp_value),0) from check_ins c join habits h on h.id=c.habit_id;'))
def open_app():
 adb('shell','am','start','-n','cn.com.omnimind.bot/.activity.MainActivity','--es','route','/home/plugin_market/local.project.life-xp-demo')
 click('Open App');named('人生经验值')
def scroll_top():adb('shell','input','swipe','520','650','520','1700','400')
def check_button(habit):
 r=snapshot();ns=list(r.iter('node'));i=next(i for i,n in enumerate(ns) if label(n)==habit)
 return next(n for n in ns[i+1:] if n.get('class')=='android.widget.Button' and ('打卡' in label(n)))
try:
 if '--finish' not in flags:
  open_app()
  if '--resume' not in flags:
   assert total()==0,'Requires untouched zero-XP demo state';shot('initial-zero')
   click('历史');named('还没有历史记录');shot('empty-history');click('今日')
   for habit,xp in [('喝水',10),('运动',40),('读书',60)]:
    button=check_button(habit);assert button.get('enabled')=='true';tap(button)
    time.sleep(.3);shot('check-in-'+str(xp))
    deadline=time.monotonic()+15
    while total()!=xp and time.monotonic()<deadline:time.sleep(.3)
    assert total()==xp,'Check-in XP mismatch'
    report['steps'].append('sqlite-xp-'+str(xp))
  else:assert total()==60 and sql('select count(*) from check_ins;')=='3','Expected preserved three check-ins'
  scroll_top();named('60');named('2');shot('level-2')
  button=check_button('喝水');assert button.get('enabled')=='false','Completed habit still enabled'
  tap(button);assert total()==60,'Duplicate check-in changed XP';shot('duplicate-blocked')
  click('历史');time.sleep(1);r=snapshot();labels=[label(n) for n in r.iter('node')];shot('history')
  if not any('升级' in x or '里程碑' in x or '等级提升' in x for x in labels):
   adb('shell','input','swipe','530','1850','530','750','350');r=snapshot();labels += [label(n) for n in r.iter('node')];shot('history-details')
  for habit in ['喝水','运动','读书']:assert any(habit in x for x in labels),'History missing '+habit
  assert any('60' in x for x in labels),'History total must be 60'
  assert all(any('+'+str(xp) in x for x in labels) for xp in [10,30,20]),'History XP mismatch'
  assert any('升级' in x or '里程碑' in x or '等级提升' in x for x in labels),'Missing level milestone'
  scroll_top();click('今日');click('+ 添加习惯')
 fields=wait(lambda r:([n for n in r.iter('node') if n.get('class')=='android.widget.EditText'] if len([n for n in r.iter('node') if n.get('class')=='android.widget.EditText'])==2 else None))
 tap(fields[0]);adb('shell','input','text','DemoWalk');fields=[n for n in snapshot().iter('node') if n.get('class')=='android.widget.EditText'];tap(fields[1]);adb('shell','input','text','15');click('添加习惯')
 deadline=time.monotonic()+10
 while sql("select count(*) from habits where name='DemoWalk' and xp_value=15;")!='1' and time.monotonic()<deadline:time.sleep(.3)
 assert sql("select count(*) from habits where name='DemoWalk' and xp_value=15;")=='1';shot('custom-habit')
 adb('shell','am','force-stop','cn.com.omnimind.bot');adb('shell','am','start','-W','-n','cn.com.omnimind.bot/.activity.LauncherActivity');open_app()
 named('60');named('2');assert total()==60;assert check_button('喝水').get('enabled')=='false';shot('reopened-progress')
 click('历史');named('累计: 60 XP');shot('reopened-history');assert sql('select count(*) from check_ins;')=='3'
 assert sql("select count(*) from habits where name='DemoWalk' and xp_value=15;")=='1'
 report['passed']=True
except Exception as error:
 report['error']=str(error);shot('failure');raise
finally:
 (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False))
