#!/usr/bin/env python3
"""Read-only, turn-scoped assertions for synthetic provider and tool failures."""
import json, re, sys
from agent_test_database import agent_database_snapshot

def verify(db, marker, expected, summary=None):
    assert re.fullmatch(r'OOB_[A-Z0-9_]+', marker), 'Synthetic marker required'
    candidates = db.execute("SELECT id,conversationId,payloadJson FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0", (marker,)).fetchall()
    live_scenario = marker.startswith('OOB_LIVE_')
    def matches_user(payload):
        text = payload.get('content',{}).get('text','')
        return text == 'Reply '+marker or (live_scenario and
            text.endswith(f'End your final reply with {marker}_DONE.'))
    selected = [r for r in candidates if matches_user(json.loads(r[2]))]
    assert len(selected) == 1, 'User admission missing or duplicated'
    start, conversation, _ = selected[0]
    end = db.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'", (conversation,start)).fetchone()[0]
    rows = [(t,json.loads(p)) for t,p in db.execute('SELECT entryType,payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND id<? ORDER BY id', (conversation,start,end or 2**63-1))]
    identities = {(p.get('streamMeta',{}).get('sessionId'),p.get('streamMeta',{}).get('turnId')) for _,p in rows}
    assert len(identities)==1 and all(next(iter(identities))), 'Missing or mixed turn identity'
    if expected=='permission-pending':
        cards=[p.get('content',{}).get('cardData',{}) for t,p in rows if t=='ui_card']
        pending=[c for c in cards if c.get('type')=='agent_request' and c.get('status')=='pending' and c.get('requestKind')=='approval' and c.get('requestId')]
        assert len(pending)==1, 'Missing current pending permission'
        assert not any(p.get('streamMeta',{}).get('stopReason') in ('end_turn','cancelled','error') for t,p in rows if t=='assistant_message'), 'Permission belongs to a finished turn'
        return {'marker':marker,'expected':expected,'passed':True,'entries':len(rows)}
    assert all(not p.get('isLoading',False) for _,p in rows), 'Turn still loading'
    failures=[p for t,p in rows if t=='tool_event' and p.get('toolName')=='agent.status' and p.get('status')=='error']
    assistants=[p for t,p in rows if t=='assistant_message']
    if expected=='cancelled':
        assert not failures, 'User cancellation incorrectly projected as failure'
        assert not any(t=='ui_card' and p.get('content',{}).get('cardData',{}).get('type')=='agent_request' and p.get('content',{}).get('cardData',{}).get('status')=='pending' for t,p in rows), 'Cancelled turn retains actionable request'
        assert any(p.get('streamMeta',{}).get('stopReason')=='cancelled' for _,p in rows), 'Missing canonical cancellation'
        assert not any(p.get('streamMeta',{}).get('stopReason') in ('end_turn','error') for _,p in rows), 'Mixed terminal outcomes'
    elif expected=='error':
        assert len(failures)==1, 'Expected one owning turn failure'
        if summary is not None: assert failures[0].get('summary')==summary, 'Wrong user-facing error category'
        assert failures[0]['streamMeta'].get('stopReason')=='error'
        assert not any(p.get('streamMeta',{}).get('stopReason')=='end_turn' for p in assistants), 'Failure projected as completion'
        if '_PARTIAL_' in marker or re.search(r'^OOB_FAILURE_STREAM(?:ERROR|RATE|LIMIT|AUTH|SERVICE|REJECT|MODEL)_', marker):
            assert any(marker+'_PARTIAL' in p.get('content',{}).get('text','') for p in assistants), 'Partial output lost'
        if '_STREAMTOOL_' in marker:
            assert all(p.get('status')=='pending' and p.get('success') is False for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status'), 'Failed provider response contains an executed tool result'
    else:
        assert expected in ('recovered','done')
        assert not failures, 'Recovered turn incorrectly remains failed'
        if marker.startswith('OOB_LIVE_TERMINAL_RECOVERY_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1 and tools[0].get('toolName') in ('terminal_execute','bash') and tools[0].get('success') is True, 'Next turn did not complete exactly one terminal command'
            raw=tools[0].get('rawResultJson') or '{}'
            result=json.loads(raw) if isinstance(raw,str) else raw
            assert result.get('result',{}).get('stdout','').strip()==marker+'_DONE', 'Terminal output missing or incorrect'
            reasons={p.get('streamMeta',{}).get('stopReason') for _,p in rows}-{None,''}
            assert reasons=={'end_turn'}, 'Missing canonical completion for terminal execution'
            return {'marker':marker,'expected':expected,'passed':True,'entries':len(rows)}
        suffix='_RECOVERED' if expected=='recovered' else '_DONE'
        replies=[p for p in assistants if p.get('content',{}).get('text')==marker+suffix or
            (live_scenario and p.get('content',{}).get('text','').rstrip().rstrip('*`').rstrip().endswith(marker+suffix))]
        assert len(replies)==1 and replies[0].get('streamMeta',{}).get('stopReason')=='end_turn', 'Missing canonical completion'
        if marker.startswith('OOB_LIVE_CODEX_EXIT_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1, 'Codex exit probe requires exactly one terminal call'
            raw=tools[0].get('rawResultJson') or '{}'
            result=json.loads(raw) if isinstance(raw,str) else raw
            assert result.get('type')=='commandExecution', 'Codex exit probe did not use command execution'
            output=result.get('rawOutput',{})
            assert output.get('exit_code')==7, 'Codex terminal did not execute the expected exit 7 command'
            text=output.get('formatted_output','')
            assert marker+'_STDOUT' in text and marker+'_STDERR' in text, 'Codex terminal output missing'
            assert tools[0].get('success') is False, 'Nonzero exit incorrectly marked successful'
        if marker.startswith('OOB_LIVE_CODEX_DETAIL_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1, 'Command detail probe requires exactly one tool'
            tool=tools[0]
            raw=tool.get('rawResultJson') or '{}'
            result=json.loads(raw) if isinstance(raw,str) else raw
            assert result.get('type')=='commandExecution', 'Expected actual command tool'
            code=result.get('rawOutput',{}).get('exit_code')
            assert type(code) is int and code!=0, 'Expected recorded nonzero exit'
            assert tool.get('status')=='error' and tool.get('success') is False, 'Failed command state lost'
            assert tool.get('summary')==f'Command exited with code {code}', 'Actual exit detail missing from history'
        if marker.startswith('OOB_LIVE_MCP_DENIED_'):
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1 and tools[0].get('success') is False, 'MCP denial missing or replayed'
            assert 'lifecycle_denied' in tools[0].get('toolName','') and 'HTTP 401' in tools[0].get('summary',''), 'Wrong MCP denial category'
        if expected=='recovered':
            tools=[p for t,p in rows if t=='tool_event' and p.get('toolName')!='agent.status']
            assert len(tools)==1 and tools[0].get('success') is False, 'Failed tool missing or replayed'
            tool=tools[0]; diagnostic=tool.get('summary','')
            if '_UNKNOWN_' in marker:
                assert tool.get('toolName')=='oob_nonexistent_tool' and 'Unknown capability' in diagnostic, 'Wrong unknown-tool failure'
            elif '_ARGS_' in marker:
                assert tool.get('toolName')=='file_read' and '{bad json' in diagnostic, 'Wrong malformed-arguments failure'
            elif '_MISSING_' in marker:
                assert tool.get('toolName')=='file_read' and marker+'-missing.txt' in diagnostic and re.search(r'does not exist|not found|不存在',diagnostic,re.I), 'Permission or another failure is not missing-file coverage'

    return {'marker':marker,'expected':expected,'passed':True,'entries':len(rows)}

if __name__=='__main__':
    serial, marker, expected, *summary=sys.argv[1:]
    assert re.fullmatch(r'emulator-\d+',serial)
    with agent_database_snapshot(serial) as db:
        print(json.dumps(verify(db,marker,expected,summary[0] if summary else None)))
