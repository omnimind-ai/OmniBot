#!/usr/bin/env python3
"""Prove a synthetic scheduled result came from one child ACP turn, not its parent."""
import json,re,sys
from agent_test_database import agent_database_snapshot
serial,marker=sys.argv[1:]
assert re.fullmatch(r'emulator-\d+',serial)
assert re.fullmatch(r'OOB_LIVE_SCHEDULE_\d+',marker)
with agent_database_snapshot(serial) as db:
    parents=[(i,c,json.loads(p)) for i,c,p in db.execute("SELECT id,conversationId,payloadJson FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0",(marker,)) if json.loads(p).get('content',{}).get('text','').startswith('Create exactly one enabled one-shot')]
    assert len(parents)==1,'Scheduled parent admission missing or duplicated'
    start,parent,_=parents[0]
    end=db.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'",(parent,start)).fetchone()[0] or 2**63-1
    parent_tools=[json.loads(p) for (p,) in db.execute("SELECT payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND id<? AND entryType='tool_event'",(parent,start,end))]
    assert len(parent_tools)==1 and parent_tools[0].get('toolName')=='schedule_task_create' and parent_tools[0].get('success') is True,'Parent must only create the task, not produce the artifact'
    children=db.execute('SELECT id,parentConversationId,scheduledTaskId,createdAt FROM conversations WHERE title=? AND mode=?',(marker,'subagent')).fetchall()
    assert len(children)==1 and children[0][1]==parent and children[0][2],'Scheduled child identity or parent link incorrect'
    child=children[0][0]
    entries=[(t,json.loads(p)) for t,p in db.execute('SELECT entryType,payloadJson FROM agent_conversation_entries WHERE conversationId=? ORDER BY id',(child,))]
    users=[p for t,p in entries if t=='user_message']; assert len(users)==1,'Child prompt duplicated or missing'
    tools=[p for t,p in entries if t=='tool_event']; assert tools and all(p.get('success') is True for p in tools),'Child tool failure'
    writes=[p for p in tools if p.get('toolName')=='file_write']; assert len(writes)==1,'Scheduled child must write exactly once'
    args=writes[0].get('args'); args=json.loads(args) if isinstance(args,str) else args
    assert args.get('path')==f'/workspace/oob-live-scheduled/{marker}.txt','Wrong scheduled artifact path'
    finals=[p for t,p in entries if t=='assistant_message' and p.get('streamMeta',{}).get('stopReason')=='end_turn' and marker+'_CHILD_DONE' in p.get('content',{}).get('text','')]
    assert len(finals)==1,'Child did not finish its canonical turn'
    turns={p.get('streamMeta',{}).get('turnId') for t,p in entries if t!='user_message'}
    assert len(turns)==1 and None not in turns,'Child was replayed across turns'
print(json.dumps({'marker':marker,'passed':True,'parentConversationId':parent,'childConversationId':child,'scheduledTaskId':children[0][2],'childToolCalls':len(tools),'childWrites':len(writes)}))
