#!/usr/bin/env python3
"""Read-only init admission/terminal assertion, bounded by a pre-action entry ID."""
import argparse
import json
import sys
from agent_test_database import agent_database_snapshot


class InitPending(AssertionError):
    pass

def verify(db, after, expected="end_turn", attachment=None):
    rows = [(i, c, t, json.loads(p)) for i, c, t, p in db.execute(
        'SELECT id,conversationId,entryType,payloadJson FROM agent_conversation_entries WHERE id>? ORDER BY id', (after,))]
    users = [r for r in rows if r[2] == 'user_message']
    if not users: raise InitPending('Waiting for init admission')
    assert len(users) == 1, 'Init admission missing or duplicated; another send invalidates this observation'
    user = users[0]
    text = user[3].get('content', {}).get('text', '')
    assert text == '/init' or text.startswith('Please analyze this repository and create or update an AGENTS.md file'), 'Not an init submission'
    if attachment:
        refs = user[3].get('content', {}).get('attachments', [])
        assert len(refs) == 1 and refs[0].get('name') == attachment and refs[0].get('path'), 'Init attachment missing or duplicated'
    items = [p for i, c, t, p in rows if c == user[1] and i > user[0]]
    if not items: raise InitPending('No init output yet')
    identities = {(p.get('streamMeta', {}).get('sessionId'), p.get('streamMeta', {}).get('turnId')) for p in items}
    assert len(identities) == 1 and all(next(iter(identities))), 'Missing or mixed canonical turn identity'
    if expected != 'active' and any(p.get('isLoading', False) for p in items): raise InitPending('Init is still loading')
    stops = {p.get('streamMeta', {}).get('stopReason') for p in items} - {None, ''}
    if expected == 'active':
        assert not stops, f'Init already terminal: {stops}'
    else:
        if not stops: raise InitPending('Waiting for official completion')
        assert stops == {expected}, f'Unexpected init terminal: {stops}'
    tools = [p for i,c,t,p in rows if c == user[1] and i > user[0] and t == 'tool_event' and p.get('toolName') != 'agent.status']
    ids = [p.get('toolCallId') for p in tools]
    assert all(ids) and len(ids) == len(set(ids)), 'Missing or duplicate tool identity'
    return {'passed': True, 'userEntryId': user[0], 'conversationId': user[1], 'sessionId': next(iter(identities))[0], 'turnId': next(iter(identities))[1], 'toolCalls': len(tools), 'expected': expected, 'attachmentChecked': attachment, 'scope': 'History admission and lifecycle only; inspect model transport separately'}

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial'); parser.add_argument('after', type=int)
    parser.add_argument('--expected', choices=['active','end_turn','cancelled'], default='end_turn')
    parser.add_argument('--attachment')
    args = parser.parse_args()
    try:
        with agent_database_snapshot(args.serial) as db:
            print(json.dumps(verify(db, args.after, args.expected, args.attachment)))
    except InitPending as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
