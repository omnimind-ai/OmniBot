#!/usr/bin/env python3
"""launchd entry point: read private deployment config, then exec the existing Bridge."""
import json
import os
import pathlib
import sys

config = json.loads(pathlib.Path(sys.argv[1]).read_text())
repo = pathlib.Path(config['repository'])
relay = repo / 'deploy/codex-relay'
token = pathlib.Path(config['tokenFile']).read_text().strip()
if not token:
    raise RuntimeError('Bridge authentication token is empty')
env = dict(os.environ,
    OMNIBOT_BRIDGE_TOKEN=token,
    OOB_SHARED_CODEX_SOCKET=config['socket'],
    OOB_CODEX_ACP_MODULE=str(relay / 'codex-acp-v2-module.mjs'),
    CODEX_PATH=str(relay / 'shared-codex-proxy.cjs'))
env.pop('OOB_SHARED_CODEX_URL', None)
os.execve(config['node'], [config['node'], str(repo / 'tools/codex-bridge/server.mjs'),
    '--host', config.get('host', '127.0.0.1'), '--port', str(config['port']),
    '--cwd', config['cwd'], '--acp-bin', str(relay / 'shared-acp-v2.mjs'),
    '--no-interactive'], env)
