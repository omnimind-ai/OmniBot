#!/usr/bin/env python3
"""Capture/compare read-only metadata around idle session-list UI operations.

Capture after opening the existing chat, then enter/back/re-enter the session
list and assert. No prompts or metadata edits may run between snapshots.
Stores identities/timestamps only, never message contents or credentials.
"""
import argparse
import json
import os
import re
from pathlib import Path
from agent_test_database import agent_database_snapshot

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial')
parser.add_argument('checkpoint', type=Path)
parser.add_argument('--capture', action='store_true')
args = parser.parse_args()
assert re.fullmatch(r'emulator-\d+', args.serial) or os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1'
with agent_database_snapshot(args.serial) as db:
    current = {
        'conversations': [list(row) for row in db.execute(
            'select id, updatedAt from conversations order by id')],
        'bindings': [list(row) for row in db.execute(
            'select conversationId, updatedAt from codex_thread_bindings order by conversationId')],
    }
assert current['conversations'] and current['bindings'], 'Requires existing bound session histories'
if args.capture:
    # An existing checkpoint must not be silently replaced after a failure.
    with args.checkpoint.open('x') as target:
        json.dump(current, target, indent=2)
    print('Captured conversation/binding metadata')
else:
    expected = json.loads(args.checkpoint.read_text())
    assert current == expected, f'Session list mutated metadata: expected {expected}, actual {current}'
    print(json.dumps({'passed': True, 'conversations': len(current['conversations']),
                      'bindings': len(current['bindings'])}))
