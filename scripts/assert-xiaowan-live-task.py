#!/usr/bin/env python3
"""Assert synthetic real-provider work from the canonical journal, read-only."""
import json, re, sys
from agent_test_database import agent_database_snapshot
serial, marker, phase = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+', serial)
assert re.fullmatch(r'OOB_LIVE_[A-Z0-9_]+', marker)
assert phase in ('files', 'long', 'recovery')
# One SQLite statement reads a consistent snapshot; only synthetic-task metadata
# is returned, not credentials, unrelated conversations, or large file bodies.
query = f"""WITH selected AS (
 SELECT id,conversationId FROM agent_conversation_entries
 WHERE entryType='user_message' AND instr(payloadJson,'{marker}')>0 ORDER BY id DESC LIMIT 1
) SELECT json_object('id',e.id,'tool',json_extract(e.payloadJson,'$.toolName'),
 'toolCallId',json_extract(e.payloadJson,'$.toolCallId'),
 'turnId',json_extract(e.payloadJson,'$.turnId'),
 'success',json_extract(e.payloadJson,'$.success'),
 'args',json_extract(e.payloadJson,'$.args'),
 'offset',json_extract(json_extract(e.payloadJson,'$.rawResultJson'),'$.result.offset'),
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
if phase == 'files':
    required = {'file_write','file_list','file_read','file_search','file_stat','file_edit','file_move','terminal_execute'}
    assert required <= {r['tool'] for r in rows if r['success'] == 1}, 'Requested tool coverage missing'
    assert all(r['success'] == 1 for r in rows), 'Unexpected tool failure'
elif phase == 'long':
    pages = [r for r in rows if r['tool']=='file_read' and isinstance(r['args'],dict)
             and r['args'].get('path')=='/workspace/oob-file-repro/large.html']
    assert len(pages)==20 and all(r['success']==1 for r in pages), 'Twenty successful reads required'
    assert [r['offset'] for r in pages]==[i*65536 for i in range(20)], 'Missing or repeated page'
else:
    assert any(r['tool']=='file_read' and r['success']==0 for r in rows), 'Missing expected file error'
    failed = [r for r in rows if r['tool']=='terminal_execute' and r['resultCode']==7]
    assert failed, 'Missing actual exit 7'
    assert any(r['tool']=='terminal_execute' and r['success']==1 and r['id']>failed[-1]['id'] for r in rows), 'No recovery command after failure'
print(json.dumps({'marker':marker,'phase':phase,'passed':True,'toolCalls':len(rows),
 'tools':sorted({r['tool'] for r in rows})}))
