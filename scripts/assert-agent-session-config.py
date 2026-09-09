#!/usr/bin/env python3
"""Read the persisted ACP configuration belonging to a synthetic test turn."""
import json,re,subprocess,sys,xml.etree.ElementTree as E
from agent_test_database import agent_database_snapshot
serial,marker,effort=sys.argv[1:]
assert re.fullmatch(r'emulator-\d+',serial)
assert re.fullmatch(r'OOB_LIVE_[A-Z_]+_\d+',marker)
assert effort in ('default','none','low','medium','high','max')
with agent_database_snapshot(serial) as db:
    users=db.execute("SELECT id,conversationId FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0",(f'End your final reply with {marker}_DONE.',)).fetchall()
    assert len(users)==1,'Missing or duplicate user admission'
    start,conversation=users[0]
    end=db.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'",(conversation,start)).fetchone()[0] or 2**63-1
    sessions={json.loads(p).get('streamMeta',{}).get('sessionId') for p, in db.execute('SELECT payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND id<?',(conversation,start,end))}
    assert len(sessions)==1 and None not in sessions,'Missing or mixed session identity'
    session=sessions.pop()
xml=subprocess.check_output(['adb','-s',serial,'exec-out','run-as','cn.com.omnimind.bot','cat','shared_prefs/acp_agent_profiles.xml'],timeout=15)
values=[json.loads(n.text) for n in E.fromstring(xml) if n.get('name')=='session_config:'+session]
assert len(values)==1 and values[0].get('reasoning_effort')==effort,'ACP session configuration not persisted as expected'
print(json.dumps({'passed':True,'marker':marker,'sessionId':session,'reasoningEffort':effort}))
