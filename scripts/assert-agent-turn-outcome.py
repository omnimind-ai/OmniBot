#!/usr/bin/env python3
"""Read-only, turn-scoped assertions for synthetic provider and tool failures."""
import json, re, sys
from agent_test_database import agent_database_snapshot

def verify(db, marker, expected, summary=None):
    assert re.fullmatch(r'OOB_[A-Z0-9_]+', marker), 'Synthetic marker required'
    candidates = db.execute("SELECT id,conversationId,payloadJson FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0", (marker,)).fetchall()
    selected = [r for r in candidates if json.loads(r[2]).get('content',{}).get('text') == 'Reply '+marker]
    assert len(selected) == 1, 'User admission missing or duplicated'
    start, conversation, _ = selected[0]
    end = db.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'", (conversation,start)).fetchone()[0]
    rows = [(t,json.loads(p)) for t,p in db.execute('SELECT entryType,payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND id<? ORDER BY id', (conversation,start,end or 2**63-1))]
    identities = {(p.get('streamMeta',{}).get('sessionId'),p.get('streamMeta',{}).get('turnId')) for _,p in rows}
    assert len(identities)==1 and all(next(iter(identities))), 'Missing or mixed turn identity'
    assert all(not p.get('isLoading',False) for _,p in rows), 'Turn still loading'
    failures=[p for t,p in rows if t=='tool_event' and p.get('toolName')=='agent.status' and p.get('status')=='error']
    assistants=[p for t,p in rows if t=='assistant_message']
    if expected=='error':
        assert len(failures)==1, 'Expected one owning turn failure'
        if summary is not None: assert failures[0].get('summary')==summary, 'Wrong user-facing error category'
        assert failures[0]['streamMeta'].get('stopReason')=='error'
        assert not any(p.get('streamMeta',{}).get('stopReason')=='end_turn' for p in assistants), 'Failure projected as completion'
        if '_PARTIAL_' in marker:
            assert any(marker+'_PARTIAL' in p.get('content',{}).get('text','') for p in assistants), 'Partial output lost'
    else:
        assert expected in ('recovered','done')
        assert not failures, 'Recovered turn incorrectly remains failed'
        suffix='_RECOVERED' if expected=='recovered' else '_DONE'
        replies=[p for p in assistants if p.get('content',{}).get('text')==marker+suffix]
        assert len(replies)==1 and replies[0].get('streamMeta',{}).get('stopReason')=='end_turn', 'Missing canonical completion'
        if expected=='recovered':
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1 and tools[0].get('success') is False, 'Failed tool missing or replayed'
    return {'marker':marker,'expected':expected,'passed':True,'entries':len(rows)}

if __name__=='__main__':
    serial, marker, expected, *summary=sys.argv[1:]
    assert re.fullmatch(r'emulator-\d+',serial)
    with agent_database_snapshot(serial) as db:
        print(json.dumps(verify(db,marker,expected,summary[0] if summary else None)))
