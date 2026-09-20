#!/usr/bin/env python3
"""Real-device release acceptance: enable, list Functions, restart and repeat.
Does not delete plugin data or execute a stored user Function.
"""
import argparse, json, re, subprocess, time, xml.etree.ElementTree as E
from pathlib import Path
p=argparse.ArgumentParser(); p.add_argument('--serial',required=True); p.add_argument('--out',type=Path,required=True); a=p.parse_args()
a.out.mkdir(parents=True,exist_ok=True)
report={'serial':a.serial,'physical':not a.serial.startswith('emulator-'),'passed':False,'steps':[]}
def adb(*args): return subprocess.check_output(['adb','-s',a.serial,*args],timeout=40)
def snapshot():
 adb('shell','uiautomator','dump','/data/local/tmp/omniflow-accept.xml')
 return E.fromstring(adb('shell','cat','/data/local/tmp/omniflow-accept.xml'))
def label(n): return n.get('text') or n.get('content-desc') or ''
def wait(predicate,seconds=35):
 deadline=time.monotonic()+seconds
 while time.monotonic()<deadline:
  root=snapshot(); result=predicate(root)
  if result is not None and result is not False:return result
  time.sleep(.5)
 raise AssertionError('Expected UI was not reached')
def tap(n):
 x,y,r,b=map(int,re.findall(r'\d+',n.get('bounds')))
 adb('shell','input','tap',str((x+r)//2),str((y+b)//2))
def named(s):return wait(lambda r:next((n for n in r.iter('node') if label(n)==s),None))
def record(step):
 report['steps'].append(step)
 (a.out/(step+'.png')).write_bytes(adb('exec-out','screencap','-p'))
def detail():
 adb('shell','am','start','-n','cn.com.omnimind.bot/.activity.MainActivity','--es','route','/home/plugin_market/com.omnimind.omni-vlm-lite')
 return wait(lambda r:next((n for n in r.iter('node') if n.get('class')=='android.widget.Switch'),None))
try:
 report['model']=adb('shell','getprop','ro.product.model').decode().strip()
 pkg=adb('shell','dumpsys','package','cn.com.omnimind.bot').decode()
 report['version']=re.findall(r'version(?:Code|Name)=\S+',pkg)
 report['debuggable']=bool(re.search(r'flags=\[[^\]]*DEBUGGABLE',pkg))
 assert not report['debuggable'],'Must test the minified release APK'
 switch=detail()
 if switch.get('checked')=='true':
  tap(switch)
  wait(lambda r:any(n.get('class')=='android.widget.Switch' and n.get('checked')=='false' for n in r.iter('node')))
  switch=detail()
 tap(switch)
 wait(lambda r:any(n.get('class')=='android.widget.Switch' and n.get('checked')=='true' for n in r.iter('node')))
 record('release-enabled')
 for attempt in range(2):
  if attempt:
   adb('shell','am','force-stop','cn.com.omnimind.bot')
   assert detail().get('checked')=='true','Enabled state did not survive restart'
   record('restart-enabled')
  tap(named('手动录制与复用指令'))
  def loaded(root):
   labels=[label(n) for n in root.iter('node')]
   assert '加载失败' not in labels,'OmniFlow runtime list failed'
   return any('暂无复用指令' in s for s in labels) or any('个步骤' in s and '个参数' in s for s in labels)
  wait(loaded,180)
  record('functions-loaded-'+str(attempt))
 report['passed']=True
except Exception as error:
 report['error']=str(error)
 (a.out/'failure.xml').write_bytes(E.tostring(snapshot(),encoding='utf-8'))
 raise
finally:
 (a.out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 print(json.dumps(report,ensure_ascii=False))
