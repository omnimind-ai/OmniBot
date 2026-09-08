#!/usr/bin/env python3
"""Read-only assertion for a synthetic UI journey; never edits app history."""
import hashlib, json, os, re, sys
from agent_test_database import agent_database_snapshot
from pathlib import Path
serial, marker = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+', serial) or os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1'
assert re.fullmatch(r'OOB_[A-Z0-9_]+', marker)
with agent_database_snapshot(serial) as db:
    row = db.execute('''SELECT c.id,c.contextSummary,c.contextSummaryCutoffEntryDbId,c.contextSummaryUpdatedAt
        FROM conversations c JOIN agent_conversation_entries e ON c.id=e.conversationId
        WHERE e.entryType='user_message' AND instr(e.payloadJson,?)>0 ORDER BY e.id DESC LIMIT 1''', (marker,)).fetchone()
    assert row and row[1] and row[2] and row[3], 'No durable nonempty checkpoint for this test conversation'
    assert db.execute('SELECT 1 FROM agent_conversation_entries WHERE id=? AND conversationId=?', (row[2],row[0])).fetchone(), 'Cutoff no longer identifies a history row'
    tools = [json.loads(record[0]) for record in db.execute(
        "SELECT payloadJson FROM agent_conversation_entries WHERE conversationId=? AND entryType='tool_event' AND instr(entryId,?)>0", (row[0], marker))]
    assert tools and all(tool.get('toolCallId') and tool.get('sessionId') for tool in tools), 'Tool identity lost during display/save roundtrip'
    print(json.dumps({'conversationId':row[0], 'summarySha256':hashlib.sha256(row[1].encode()).hexdigest(), 'cutoff':row[2], 'revision':row[3]}))
