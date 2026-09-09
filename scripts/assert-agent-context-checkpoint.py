#!/usr/bin/env python3
"""Read-only assertion for a synthetic UI journey; never edits app history."""
import hashlib, json, os, re, sys
from agent_test_database import agent_database_snapshot
from pathlib import Path
def verify_live_checkpoint(db, marker):
    assert re.fullmatch(r'OOB_LIVE_AUTO_COMPACT_\d+', marker), 'Automatic compaction test marker required'
    users = db.execute("SELECT id,conversationId FROM agent_conversation_entries WHERE entryType='user_message' AND instr(payloadJson,?)>0", (marker,)).fetchall()
    assert len(users) == 1, 'Missing or duplicate user admission'
    user, conversation = users[0]
    summary, cutoff, revision = db.execute('SELECT contextSummary,contextSummaryCutoffEntryDbId,contextSummaryUpdatedAt FROM conversations WHERE id=?', (conversation,)).fetchone()
    assert summary and cutoff and revision, 'No durable summary'
    assert cutoff > user, 'Old checkpoint does not prove same-task automatic compaction'
    next_user = db.execute("SELECT min(id) FROM agent_conversation_entries WHERE conversationId=? AND entryType='user_message' AND id>?", (conversation,user)).fetchone()[0]
    assert next_user is None or cutoff < next_user, 'Later task checkpoint does not prove this task compacted'
    row = db.execute('SELECT entryType,payloadJson FROM agent_conversation_entries WHERE id=? AND conversationId=?', (cutoff, conversation)).fetchone()
    assert row and row[0] == 'tool_event', 'Checkpoint does not identify a completed tool boundary'
    payload = json.loads(row[1])
    assert payload.get('toolCallId') and payload.get('sessionId') and payload.get('success') is True, 'Checkpoint tool identity or completion missing'
    return {'conversationId':conversation,'summarySha256':hashlib.sha256(summary.encode()).hexdigest(),'cutoff':cutoff,'revision':revision}

if __name__ == '__main__':
    serial, marker = sys.argv[1:]
    assert re.fullmatch(r'emulator-\d+', serial) or os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1'
    assert re.fullmatch(r'OOB_[A-Z0-9_]+', marker)
    with agent_database_snapshot(serial) as db:
        if marker.startswith('OOB_LIVE_AUTO_COMPACT_'):
            print(json.dumps(verify_live_checkpoint(db, marker)))
        else:
            row = db.execute("""SELECT c.id,c.contextSummary,c.contextSummaryCutoffEntryDbId,c.contextSummaryUpdatedAt
                FROM conversations c JOIN agent_conversation_entries e ON c.id=e.conversationId
                WHERE e.entryType='user_message' AND instr(e.payloadJson,?)>0 ORDER BY e.id DESC LIMIT 1""", (marker,)).fetchone()
            assert row and row[1] and row[2] and row[3], 'No durable nonempty checkpoint for this test conversation'
            assert db.execute('SELECT 1 FROM agent_conversation_entries WHERE id=? AND conversationId=?', (row[2],row[0])).fetchone(), 'Cutoff no longer identifies a history row'
            tools = [json.loads(record[0]) for record in db.execute(
                "SELECT payloadJson FROM agent_conversation_entries WHERE conversationId=? AND entryType='tool_event' AND instr(entryId,?)>0", (row[0], marker))]
            assert tools and all(tool.get('toolCallId') and tool.get('sessionId') for tool in tools), 'Tool identity lost during display/save roundtrip'
            print(json.dumps({'conversationId':row[0], 'summarySha256':hashlib.sha256(row[1].encode()).hexdigest(), 'cutoff':row[2], 'revision':row[3]}))
