"""Real configured ACP adapter regression: v2 reasoning selection after session/new.

Requires OOB_ACP_BIN, OOB_TEST_CWD and the adapter's normal backend environment.
Creates an empty isolated session, never sends a prompt or alters existing sessions.
"""
import json
import os
from pathlib import Path
import select
import subprocess
import time

cwd = Path(os.environ['OOB_TEST_CWD']).resolve(strict=True)
assert cwd.is_dir()
child = subprocess.Popen([os.environ['OOB_ACP_BIN']], stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
buffer = b''
sequence = 0

def rpc(method, params):
    global sequence, buffer
    sequence += 1
    request_id = sequence
    child.stdin.write((json.dumps(dict(jsonrpc='2.0', id=request_id,
                                     method=method, params=params)) + '\n').encode())
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if b'\n' not in buffer:
            if not select.select([child.stdout], [], [], 1)[0]:
                continue
            chunk = os.read(child.stdout.fileno(), 65536)
            assert chunk, 'Adapter exited before RPC response'
            buffer += chunk
        while b'\n' in buffer:
            line, buffer = buffer.split(b'\n', 1)
            message = json.loads(line)
            if message.get('id') == request_id:
                return message
    raise AssertionError('RPC timeout: ' + method)

try:
    initialized = rpc('initialize', dict(protocolVersion=2,
        info=dict(name='oob-config-regression', version='1'), capabilities={}))
    assert initialized['result']['protocolVersion'] == 2
    created = rpc('session/new', dict(cwd=str(cwd), mcpServers=[]))
    session = created['result']['sessionId']
    options = created['result']['configOptions']
    effort = next(o for o in options if o.get('category') == 'thought_level')
    params = dict(sessionId=session, configId=effort['id'], value=effort['currentValue'])
    rejected = rpc('session/set_config_option', params)
    assert rejected.get('error', {}).get('code') == -32602
    accepted = rpc('session/set_config_option', dict(params, type='id'))
    assert 'error' not in accepted, accepted.get('error')
    assert accepted['result']['configOptions']
    closed = rpc('session/close', dict(sessionId=session))
    assert 'error' not in closed, closed.get('error')
    print(json.dumps(dict(passed=True, sessionId=session, legacyRejected=True,
        typedSelectionAccepted=True, promptSent=False)))
finally:
    child.terminate()
    try:
        child.wait(timeout=5)
    except subprocess.TimeoutExpired:
        child.kill()
