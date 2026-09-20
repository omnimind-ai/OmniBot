#!/usr/bin/env python3
"""Read-only, turn-scoped assertions for synthetic provider and tool failures."""
import json, os, re, sys
from agent_test_database import agent_database_snapshot

def verify(db, marker, expected, summary=None):
    assert re.fullmatch(r'OOB_[A-Z0-9_]+', marker), 'Synthetic marker required'
    candidates = db.execute("SELECT id,conversationId,payloadJson FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0", (marker,)).fetchall()
    live_scenario = marker.startswith(('OOB_LIVE_', 'OOB_LIFE_XP_'))
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
    if expected=='terminal-failure':
        failures=[p for t,p in rows if t=='tool_event' and p.get('toolName')=='agent.status'
                  and p.get('status')=='error' and p.get('streamMeta',{}).get('stopReason')=='error']
        cancelled=any(p.get('streamMeta',{}).get('stopReason')=='cancelled' for _,p in rows)
        completed = bool(rows) and all(not p.get('isLoading', False) for _,p in rows) and \
            all(p.get('streamMeta',{}).get('stopReason') == 'end_turn' for _,p in rows)
        required_marker = summary if summary and re.fullmatch(r'OOB_[A-Z0-9_]+_DONE', summary) else None
        missing_marker = completed and required_marker is not None and not any(
            required_marker in p.get('content',{}).get('text','') for t,p in rows if t == 'assistant_message')
        return {'marker':marker,'failed':bool(failures) or cancelled,
                'completed':completed,
                'reason':'error' if failures else 'cancelled' if cancelled else None,
                'summary':failures[-1].get('summary') if failures else None,
                'completedWithoutRequiredMarker': missing_marker}
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
        if marker.startswith('OOB_FAILURE_INDEX_'):
            tools = [p for t,p in rows if t == 'tool_event' and p.get('toolName') != 'agent.status']
            assert len(tools) == 2, 'Expected exactly two writes, no replay'
            for i, tool in enumerate(tools):
                assert tool.get('toolName') == 'file_write' and tool.get('success') is True
                args = tool.get('argsJson') or tool.get('args') or '{}'
                args = json.loads(args) if isinstance(args, str) else args
                assert args == {'path':f'/workspace/{marker}-{i}.txt', 'content':f'{marker}_VALUE_{i}'}, 'Tool arguments crossed call indices'
        if marker.startswith('OOB_LIVE_FILE_EDIT_RECOVERY_'):
            tools = [p for t, p in rows if t == 'tool_event' and p.get('toolName') != 'agent.status']
            edits = [p for p in tools if p.get('toolName') == 'file_edit']
            assert len(edits) == 2, 'Expected one no-change edit and one corrected edit'
            def args_of(p):
                value = p.get('argsJson') or p.get('args') or '{}'
                return json.loads(value) if isinstance(value, str) else value
            first, second = map(args_of, edits)
            assert first.get('oldText') == first.get('newText') == 'alpha'
            assert edits[0].get('success') is False and edits[0].get('status') == 'error'
            assert '文件未变化' in str(edits[0].get('summary', ''))
            assert second.get('oldText') == 'alpha' and second.get('newText') == 'beta'
            assert second.get('path') == first.get('path') == '/workspace/edit-recovery.txt'
            assert edits[1].get('success') is True
            reads = [p for p in tools if p.get('toolName') == 'file_read' and p.get('success') is True]
            assert reads and 'beta' in str(reads[-1].get('rawResultJson')), 'Corrected content must be read back'
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
        if marker.startswith(('OOB_LIVE_DOCUMENT_FAILURE_', 'OOB_LIVE_SCAN_FAILURE_', 'OOB_LIVE_PASSWORD_FAILURE_')):
            reads=[p for t,p in rows if t=='tool_event' and p.get('toolName')=='file_read']
            assert len(reads)==1 and reads[0].get('success') is False, 'Unreadable PDF must produce one failed file_read'
            result=json.loads(reads[0].get('rawResultJson') or '{}').get('result',{})
            error_code = 'document_ocr_required' if marker.startswith('OOB_LIVE_SCAN_') else 'document_password_required' if marker.startswith('OOB_LIVE_PASSWORD_') else 'document_parse_failed'
            assert result.get('contentAvailable') is False and result.get('errorCode')==error_code, 'Wrong document failure'
            assert not result.get('content'), 'Unreadable file reported body'
            assert 'UNREADABLE' in replies[0].get('content',{}).get('text',''), 'Missing user-facing failure explanation'
        if marker.startswith(('OOB_LIVE_DOCUMENT_NEXT_', 'OOB_LIVE_CONTEXT_NEXT_')):
            assert re.search(r'\b42\b',replies[0].get('content',{}).get('text','')), 'Next task did not answer correctly'
            assert not any(t=='tool_event' for t,p in rows), 'Next turn replayed a tool from previous task'
        document = re.match(r'^OOB_LIVE_(UPLOAD|REOPEN)_(PDF|DOCX|XLSX)_', marker)
        if document:
            phase, extension = document.groups()
            expected_values = ['MAPLE-8264'] if phase == 'REOPEN' else ['CEDAR-7391', '37']
            reply = replies[0].get('content', {}).get('text', '')
            assert all(re.search(r'(?<![\w-])'+re.escape(value)+r'(?![\w-])', reply) for value in expected_values), 'Document answer missing actual fixture values'
            reads = [p for t,p in rows if t=='tool_event' and p.get('toolName')=='file_read' and p.get('success') is True]
            results = [json.loads(p.get('rawResultJson') or '{}').get('result', {}) for p in reads]
            parser = 'pdfbox-android/2.0.27.0' if extension == 'PDF' else 'ooxml-text/1'
            assert any(r.get('contentAvailable') is True and r.get('parser') == parser and
                       r.get('path','').lower().endswith('.'+extension.lower()) and
                       all(value in r.get('content','') for value in expected_values) for r in results), 'No actual document body read in this turn'
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
    assert re.fullmatch(r'emulator-\d+', serial) or (os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1' and re.fullmatch(r'[A-Za-z0-9._:-]+', serial)), 'Physical device requires OOB_ALLOW_PHYSICAL_DEVICE=1'
    with agent_database_snapshot(serial) as db:
        print(json.dumps(verify(db,marker,expected,summary[0] if summary else None)))
