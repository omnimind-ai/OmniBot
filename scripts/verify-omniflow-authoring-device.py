"""Real UI authoring regression; no generated Function/arguments are injected.
Uses existing Journey/device readers. Physical-device opt-in uses the same env.
Phases record, author, verify, replay, restart. Only searches Settings; no changes.
"""
import argparse, importlib.util, json, time, os
from pathlib import Path
from types import SimpleNamespace
from agent_test_database import agent_database_snapshot
spec=importlib.util.spec_from_file_location('journey',Path(__file__).with_name('verify-execution-center-device.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
j=m.Journey(SimpleNamespace())
p=argparse.ArgumentParser();p.add_argument('--phase',required=True,choices=['record','author','verify','replay','replay-local','replay-source','restart']);a=p.parse_args()
# The same semantic UI journey supports the English emulator and Chinese phone.
labels = {'手动录制':'Manual recording', '开始':'Start', '搜索设置项':'Search settings',
          '动作':'Action', '输入文字':'Enter text', '输入':'Enter', '完成':'Finish',
          '增强':'Enhance', '执行':'Run', '开始执行':'Run', '取消':'Cancel'}
def label(value):
 return labels.get(value,value) if os.environ.get('OOB_GUI_LANGUAGE')=='en' else value
def center():
 j.device.adb('shell','am','start','-n',j.device.package+'/.activity.MainActivity','--es','route','/task/omniflow')
 j.device.wait(lambda:j.matching(label('手动录制')),bool)
def settings_home():
 j.device.adb('shell','am','start','-a','android.settings.SETTINGS');time.sleep(.7)
 for _ in range(3):
  cancel=[n for n in j.nodes() if (n.get('package')=='com.android.settings' and j.label(n)==label('取消')) or (n.get('package')=='com.google.android.settings.intelligence' and j.label(n)=='Back')]
  if not cancel:break
  j.tap_node(cancel[0]);time.sleep(.5)
 assert j.matching(label('搜索设置项'))
def save(name,value):return j.device.persist(name+'.json',value)
def read(name):return json.loads((j.device.output/(name+'.json')).read_text())
try:
 if a.phase=='record':
  j.launch();settings_home();center();j.tap(label('手动录制'))
  status=j.device.wait(j.device.recording_status,lambda s:s.get('recording_active'))
  run_id=status['run_id'];j.tap(label('开始'))
  j.device.adb('shell','am','start','-a','android.settings.SETTINGS');time.sleep(.7)
  j.tap(label('搜索设置项'));time.sleep(.7)
  # Use the recorder's real semantic input dialog, rather than bypassing it
  # with a shell key injection into Settings (which is not recorded input).
  j.tap(label('动作'));j.tap(label('输入文字'))
  fields=j.device.wait(lambda:[n for n in j.nodes() if n.get('class')=='android.widget.EditText' and n.get('package')==j.device.package and n.get('focused')=='true'],bool)
  assert len(fields)==1
  # The native dialog already owns focus; keyboard appearance moves its bounds.
  # Do not tap a stale pre-IME rectangle and dismiss the dialog.
  j.device.adb('shell','input','text','battery')
  submit=j.device.wait(lambda:[n for n in j.nodes() if n.get('package')==j.device.package and n.get('class')=='android.widget.Button' and j.label(n)==label('输入')],lambda nodes:len(nodes)==1)
  j.tap_node(submit[0])
  j.device.wait(lambda:j.device.recording_status(),lambda s:s.get('action_count',0)>=2)
  assert any(j.label(n)=='battery' for n in j.nodes())
  j.tap(label('完成'));j.device.wait(j.device.recording_status,lambda s:not s.get('recording_active'))
  functions=j.device.tool('list_functions',include_hidden=True)['functions']
  found=[f for f in functions if f.get('source_run_id')==run_id];assert len(found)==1
  run=j.device.run_log(run_id);assert run['status']=='succeeded'
  assert any(s['action']['tool']=='input_text' and s['action']['args'].get('text')=='battery' for s in run['steps'])
  save('record',{'run_id':run_id,'function':found[0]});save('source-run',run)
  canonical=j.device.tool('get_run_log',run_id=run_id)
  save('canonical-source-run',canonical)
  for step in canonical['steps']:
   for key in ('observation','next_observation'):
    state=step.get(key,{})
    xml=state.get('forest') or state.get('xml') or ''
    assert xml,'Recorded observation XML is missing'
    assert 'Pause manual recording' not in xml and '暂停录制' not in xml,'Recording controls contaminated source evidence'
 elif a.phase=='author':
  source=read('record')
  j.device.adb('shell','am','start','-n',j.device.package+'/.activity.MainActivity','--es','route','/task/omniflow?functionId='+source['function']['function_id'])
  j.device.wait(lambda:j.matching(source['function']['function_id']),bool);j.tap(label('增强'))
  save('author-start',{'run_id':source['run_id'],'via':'actual Enhance button','time':time.time()})
 elif a.phase=='verify':
  source=read('record')
  # Registration may not have started when Enhance returns. Await the owning
  # chat's official terminal response before inspecting the Function Store.
  def author_terminal():
   with agent_database_snapshot(j.device.serial) as db:
    user=db.execute("SELECT conversationId,id FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0 ORDER BY id DESC LIMIT 1",(source['run_id'],)).fetchone()
    if not user:return None
    entries=[(t,json.loads(p)) for t,p in db.execute('SELECT entryType,payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? ORDER BY id',user)]
    tools=[p for t,p in entries if t=='tool_event' and p.get('rawResultJson') and json.loads(p['rawResultJson']).get('rawOutput') is not None]
    terminal=[p for t,p in entries if t=='assistant_message' and p.get('streamMeta',{}).get('stopReason') in ('end_turn','error','cancelled') and p.get('streamMeta',{}).get('isFinal')]
    return {'conversation_id':user[0],'tools':tools,'terminal':terminal} if tools and terminal else None
  save('author-terminal',j.device.wait(author_terminal,bool,seconds=300))
  functions=j.device.tool('list_functions',include_hidden=True)['functions']
  found=[f for f in functions if f.get('source_run_id')==source['run_id'] and f['function_id']!=source['function']['function_id']]
  save('authoring-observed',{'source_run_id':source['run_id'],'functions':found,
   'original_preserved':any(f==source['function'] for f in functions)})
  assert found,'Authoring did not register new Functions'
  assert any(f['function_id']==source['function']['function_id'] for f in functions),'Original Function lost'
  bound=[f for f in found if f.get('bindings') and f.get('agent_visible',True)]
  assert bound,'No executable parameterized Function from authoring'
  for f in bound:
   assert f['input_schema']['properties'],'Binding missing input schema'
  save('authored',{'functions':found,'bound':bound,'original_preserved':True})
 elif a.phase in ('replay','replay-local','replay-source'):
  fs=[read('record')['function']] if a.phase=='replay-source' else read('authored')['bound'];f=max(fs,key=lambda f:len(f['steps']))
  query='battery' if a.phase=='replay-source' else 'wifi'
  # Only select the model-generated result through the actual product UI.
  settings_home()
  if a.phase=='replay-local':
   assert len(f['steps'])==1 and f['steps'][0]['action']['tool']=='input_text'
   # Separate acceptance for the authored local operation's actual entry state.
   # This does not count as the full homepage-to-search workflow.
   j.tap(label('搜索设置项'))
  j.device.adb('shell','am','start','-n',j.device.package+'/.activity.MainActivity','--es','route','/task/omniflow?functionId='+f['function_id'])
  j.device.wait(lambda:j.matching(f['function_id']),bool)
  baseline=j.device.tool('list_run_logs',limit=1)['runs'][0]['run_id'];j.tap(label('执行'))
  if a.phase!='replay-source':
   fields=j.device.wait(lambda:[n for n in j.nodes() if n.get('class')=='android.widget.EditText'],bool)
   assert len(fields)==1,'Fixture requires one semantic query field'
   j.tap_node(fields[0])
   j.device.wait(lambda:j.nodes(),lambda ns:any(n.get('class')=='android.widget.EditText' and n.get('focused')=='true' for n in ns) and any('inputmethod' in n.get('package','') and n.get('visible-to-user')=='true' for n in ns))
   j.device.adb('shell','input','text',query)
   j.device.wait(lambda:j.nodes(),lambda ns:any(n.get('class')=='android.widget.EditText' and n.get('package')==j.device.package and j.label(n)==query for n in ns))
   j.tap(label('开始执行'))
  result=j.device.wait(lambda:j.device.tool('list_run_logs',limit=1)['runs'][0],lambda r:r['run_id']!=baseline and r['status'] in ('succeeded','failed','cancelled'),seconds=180)
  save('replay-result',result)
  run=j.device.run_log(result['run_id']);save('replay-run',run)
  assert result['status']=='succeeded',{'error':run.get('error'),'diagnostics':result.get('diagnostics')}
  assert any(n.get('package') in ('com.android.settings','com.google.android.settings.intelligence') and j.label(n)==query for n in j.nodes()),'Expected query absent on real screen'
  assert any(s['action']['tool']=='input_text' and s['action']['args'].get('text')==query for s in run['steps'])
  save('replay-verified',{'function_id':f['function_id'],'query':query,'source_query':'battery','scope':a.phase,'passed':True})
 elif a.phase=='restart':
  expected=read('authored')['functions'];j.device.adb('shell','am','force-stop',j.device.package);j.launch()
  # Store reload does not require GUI observation or an accessibility service.
  # Some OEMs disable that service on force-stop; do not hide this by changing it.
  actual=j.device.tool('list_functions',include_hidden=True)['functions']
  for f in expected:assert next(v for v in actual if v['function_id']==f['function_id'])==f
  save('restart',{'passed':True,'function_ids':[f['function_id'] for f in expected]})
 print(a.phase+' passed',flush=True)
except Exception as e:
 save(a.phase+'-failure',{'passed':False,'error':str(e)});raise
