#!/usr/bin/env python3
"""Assert synthetic real-provider work from the canonical journal, read-only."""
import json, re, sys
from agent_test_database import agent_database_snapshot
serial, marker, phase = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+', serial)
assert re.fullmatch(r'OOB_LIVE_[A-Z0-9_]+', marker)
assert phase in ('files', 'long', 'recovery', 'permission-denied', 'permission-allowed', 'file-list-limits', 'content-search', 'session-exit', 'list-refresh', 'auto-pages', 'auto-pages-60', 'auto-next-60', 'small-page-body')
# One SQLite statement reads a consistent snapshot; only synthetic-task metadata
# is returned, not credentials, unrelated conversations, or large file bodies.
query = f"""WITH selected AS (
 SELECT id,conversationId FROM agent_conversation_entries
 WHERE entryType='user_message' AND instr(payloadJson,'{marker}')>0 ORDER BY id DESC LIMIT 1
) SELECT json_object('id',e.id,'tool',json_extract(e.payloadJson,'$.toolName'),
 'toolCallId',json_extract(e.payloadJson,'$.toolCallId'),
 'turnId',json_extract(e.payloadJson,'$.turnId'),
 'success',json_extract(e.payloadJson,'$.success'),
 'summary',json_extract(e.payloadJson,'$.summary'),
 'args',json_extract(e.payloadJson,'$.args'),
 'listCount',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.result.count'),
 'listItems',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.result.items'),
 'terminalStdout',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.result.stdout'),
 'offset',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.result.offset'),
 'privilegedCommand',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.rawOutput.result.command'),
 'privilegedExitCode',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.rawOutput.result.exitCode'),
 'privilegedStdout',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.rawOutput.result.stdout'),
 'resultCode',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.result.resultCode'))
 FROM agent_conversation_entries e,selected s
 WHERE e.conversationId=s.conversationId AND e.id>s.id AND e.entryType='tool_event'
 AND e.id<coalesce((SELECT min(n.id) FROM agent_conversation_entries n WHERE n.conversationId=s.conversationId AND n.entryType='user_message' AND n.id>s.id),9223372036854775807) ORDER BY e.id;
"""
with agent_database_snapshot(serial) as db:
    rows = [json.loads(row[0]) for row in db.execute(query)]
for row in rows:
    if isinstance(row['args'],str): row['args']=json.loads(row['args'])
assert rows and all(r['toolCallId'] and r['turnId'] for r in rows), 'Canonical tool identity missing'
assert len({r['toolCallId'] for r in rows}) == len(rows), 'Duplicate tool projection'
if phase == 'small-page-body':
    assert len(rows)==2 and all(r['tool']=='file_read' and r['success']==1 for r in rows), 'Expected two successful small reads'
    assert all(r['args'].get('path')=='/workspace/oob-small-page-body.html' and r['args'].get('maxChars')==2048 for r in rows), 'Small page limit missing'
    assert [r['args'].get('offset',0) for r in rows]==[0,2048], 'Wrong continuation offset'
    with agent_database_snapshot(serial) as db:
        selected=db.execute("SELECT id,conversationId FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0 ORDER BY id DESC LIMIT 1", (marker,)).fetchone()
        payloads=[r[0] for r in db.execute("SELECT payloadJson FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='assistant_message' AND id<coalesce((SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND id>? AND entryType='user_message'),9223372036854775807)", (selected[1],selected[0],selected[1],selected[0]))]
    assert any('OOB_BODY_RIVER_7391' in json.loads(payload).get('content',{}).get('text','') for payload in payloads), 'Model did not report value from file body'
elif phase == 'auto-next-60':
    assert len(rows)==1 and rows[0]['tool']=='file_read' and rows[0]['success']==1, 'Expected one successful continuation read'
    assert rows[0]['args'].get('path')=='/workspace/oob-file-repro/large.html', 'Wrong file after restart'
    assert rows[0]['args'].get('offset')==60*65536, 'Next unread page was not restored'
elif phase in ('auto-pages', 'auto-pages-60'):
    expected_pages = 60 if phase == 'auto-pages-60' else 20
    assert len(rows)==expected_pages, f'Expected exactly {expected_pages} reads; duplicate or offload detours occurred'
    assert all(r['tool']=='file_read' and r['success']==1 and r['args'].get('path')=='/workspace/oob-file-repro/large.html' for r in rows), 'Unexpected tool or file path during sequential reads'
    assert [r['args'].get('offset',0) for r in rows]==[i*65536 for i in range(expected_pages)], 'Page continuity lost'
elif phase == 'files':
    required = {'file_write','file_list','file_read','file_search','file_stat','file_edit','file_move','terminal_execute'}
    assert required <= {r['tool'] for r in rows if r['success'] == 1}, 'Requested tool coverage missing'
    assert all(r['success'] == 1 for r in rows), 'Unexpected tool failure'
elif phase == 'file-list-limits':
    operations = [r for r in rows if r['tool']=='file_list']
    assert len(operations)==3 and all(r['success']==1 for r in rows), 'Three successful list calls and no tool errors required'
    assert [r['args'].get('limit') for r in operations]==[1,2,None], 'Explicit limits or call order changed'
    assert all(r['args'].get('recursive') is True and r['args'].get('path')=='/workspace/'+marker for r in operations)
    assert [r['listCount'] for r in operations]==[1,2,2], 'Root consumed the requested result limit'
    for operation in operations:
        items=operation['listItems']
        if isinstance(items,str): items=json.loads(items)
        assert len(items)==operation['listCount']
        assert all(item['name'] in ('first.txt','second.txt') for item in items), 'Unexpected root or unrelated entry'
    assert {item['name'] for item in items}=={'first.txt','second.txt'}
elif phase == 'content-search':
    operations = [r for r in rows if r['tool']=='file_search']
    assert len(rows)==3 and len(operations)==3 and all(r['success']==1 for r in operations), 'Three real searches required'
    assert [r['args'].get('query') for r in operations]==['cross_boundary_needle','tail_needle','absent_content_needle']
    assert [r['listCount'] for r in operations]==[1,1,0], 'Missing boundary or end-of-file search result'
    for operation, expected in zip(operations, ['x'*40+'cross_boundary_needle'+'z'*120, 'z'*40+'tail_needle', None]):
        items=operation['listItems']
        if isinstance(items,str): items=json.loads(items)
        assert len(items)==operation['listCount']
        if expected is not None:
            assert items[0]['path']=='/workspace/oob-content-search-regression/large.html'
            assert items[0]['matchType']=='content' and items[0]['snippet']==expected, 'Excerpt content or bounds wrong'
elif phase == 'list-refresh':
    assert len(rows)==1 and rows[0]['tool']=='terminal_execute'
    assert rows[0]['args'].get('command')=='sleep 60' and rows[0]['success']==1, 'Expected one completed sleep command'
elif phase == 'session-exit':
    assert all(r['tool'] in ('terminal_session_start','terminal_session_exec','terminal_session_stop') for r in rows), 'Unexpected substitute tool'
    operations=[r for r in rows if r['tool']=='terminal_session_exec']
    assert len(operations)==2, 'Exactly one exit and one recovery execution required'
    failed, recovered=operations
    assert failed['args'].get('command')=='exit 7' and failed['success']==0
    assert 'exit=7' in str(failed['summary']), 'Expected actual shell exit, not timeout or environment failure'
    assert recovered['success']==1 and str(recovered['terminalStdout']).strip()==marker+'_OK', 'Actual recovery output required'
    assert failed['args']['sessionId']!=recovered['args']['sessionId'], 'Recovery must use a fresh session'
    assert len([r for r in rows if r['tool']=='terminal_session_start' and r['success']==1])==2
    assert any(r['tool']=='terminal_session_stop' and r['success']==1 and r['args'].get('sessionId')==recovered['args']['sessionId'] for r in rows)
elif phase == 'long':
    pages = [r for r in rows if r['tool']=='file_read' and isinstance(r['args'],dict)
             and r['args'].get('path')=='/workspace/oob-file-repro/large.html']
    assert len(pages)==20 and all(r['success']==1 for r in pages), 'Twenty successful reads required'
    assert [r['offset'] for r in pages]==[i*65536 for i in range(20)], 'Missing or repeated page'
elif phase in ('permission-denied', 'permission-allowed'):
    assert len(rows)==1, 'Permission denial must not replay or substitute another tool'
    operation=rows[0]
    assert operation['tool']=='android_privileged_action'
    arguments=operation['args'].get('arguments',{})
    assert operation['args'].get('action')=='shell.exec'
    if phase=='permission-denied':
        assert arguments.get('command')=='id' and str(arguments.get('confirmed',False)).lower()=='false', 'Model must await permission'
        assert operation['success']==0 and re.search(r'拒绝|denied|declined|rejected',str(operation['summary']),re.I), 'Missing actual user denial; backend failure is not denial coverage'
    else:
        assert arguments.get('command')=='id' and str(arguments.get('confirmed')).lower()=='false', 'Original model arguments were overwritten by progress'
        assert operation['privilegedCommand']=='id' and operation['success']==1 and operation['privilegedExitCode']==0, 'Approved command did not actually exit successfully'
        assert re.search(r'uid=\d+',str(operation['privilegedStdout'])), 'Actual id command output required'

else:
    missing = [r for r in rows if r['tool']=='file_read' and r['success']==0 and
               isinstance(r['args'],dict) and r['args'].get('path')=='/workspace/oob-live-integration/missing-intentional.txt']
    assert len(missing)==1 and re.search(r'does not exist|not found|不存在',str(missing[0]['summary']),re.I), 'Expected missing file was not verified; permissions or unrelated file errors do not qualify'
    failed = [r for r in rows if r['tool']=='terminal_execute' and r['resultCode']==7 and r['success']==0]
    assert failed, 'Missing actual exit 7'
    assert any(r['tool']=='terminal_execute' and r['success']==1 and r['id']>failed[-1]['id'] for r in rows), 'No recovery command after failure'
print(json.dumps({'marker':marker,'phase':phase,'passed':True,'toolCalls':len(rows),
 'tools':sorted({r['tool'] for r in rows})}))
