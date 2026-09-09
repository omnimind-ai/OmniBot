#!/usr/bin/env python3
"""Verify a synthetic prompt's persisted Conversation -> Session -> Agent binding."""
import argparse,json,re,subprocess,xml.etree.ElementTree as ET
from agent_test_database import agent_database_snapshot
p=argparse.ArgumentParser();p.add_argument('serial');p.add_argument('marker');p.add_argument('agent');a=p.parse_args()
assert re.fullmatch(r'OOB_LIVE_SWITCH_DRAFT_\d+',a.marker)
assert a.agent in ('xiaowan-acp','deepseek-harness-acp')
with agent_database_snapshot(a.serial) as db:
 rows=db.execute("SELECT id,conversationId FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0",(a.marker,)).fetchall();assert len(rows)==1,'Missing or repeated user admission'
 entry,conversation=rows[0];binding=db.execute('SELECT threadId FROM codex_thread_bindings WHERE conversationId=?',(conversation,)).fetchone();assert binding
 session=binding[0]
 root=ET.fromstring(subprocess.check_output(['adb','-s',a.serial,'exec-out','run-as','cn.com.omnimind.bot','cat','shared_prefs/acp_agent_profiles.xml'],timeout=30))
 bindings=json.loads(next(n.text for n in root if n.get('name')=='session_bindings'))
 assert bindings.get(session)==a.agent,'Session belongs to another Harness'
 end=db.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'",(conversation,entry)).fetchone()[0] or 2**63-1
 meta=[json.loads(r[0]).get('streamMeta',{}) for r in db.execute('SELECT payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND id<?',(conversation,entry,end))]
 assert meta and all(m.get('sessionId')==session for m in meta),'Projected output belongs to another session'
 turns={m.get('turnId') for m in meta};assert len(turns)==1 and None not in turns
 print(json.dumps({'passed':True,'marker':a.marker,'conversationId':conversation,'sessionId':session,'turnId':next(iter(turns)),'agentId':a.agent,'userEntryId':entry}))
