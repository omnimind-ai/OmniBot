#!/usr/bin/env python3
"""Supervise Bridge with launchd while retaining the already-running Codex backend."""
import argparse
import json
import ipaddress
import os
import pathlib
import plistlib
import re
import shutil
import subprocess
import sys

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--socket', required=True, type=pathlib.Path)
p.add_argument('--token-file', required=True, type=pathlib.Path)
p.add_argument('--cwd', required=True, type=pathlib.Path)
p.add_argument('--port', type=int, default=17336)
p.add_argument('--host', default='127.0.0.1', help='Local interface IP; defaults to loopback')
p.add_argument('--label', default='cn.omnimind.codex-shared-bridge-test')
p.add_argument('--apply', action='store_true')
a = p.parse_args()
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]+', a.label): p.error('Invalid launchd label')
if not 1024 <= a.port <= 65535: p.error('Invalid port')
try: ipaddress.ip_address(a.host)
except ValueError: p.error('Host must be a local interface IP address')
if not a.socket.is_socket(): p.error('Existing Codex socket required')
if not a.cwd.is_dir() or not a.token_file.is_file(): p.error('Existing workspace and token file required')
if a.token_file.stat().st_mode & 0o077: p.error('Token file must be private (0600)')
repo = pathlib.Path(__file__).resolve().parents[2]
node = shutil.which('node')
if not node: p.error('Node.js is required')
for entry in ['tools/codex-bridge/node_modules/ws',
              'deploy/codex-relay/codex-acp-v2-module.mjs']:
    if not (repo / entry).exists(): p.error('Install pinned dependencies and prepare adapter first: ' + entry)
state = pathlib.Path.home() / 'Library/Application Support/OmniBot' / a.label
config_path = state / 'config.json'
plist_path = pathlib.Path.home() / 'Library/LaunchAgents' / (a.label + '.plist')
config = dict(repository=str(repo), node=node, socket=str(a.socket.resolve()),
              tokenFile=str(a.token_file.resolve()), cwd=str(a.cwd.resolve()), port=a.port)
if a.host != '127.0.0.1': config['host'] = a.host
plist = dict(Label=a.label, ProgramArguments=[sys.executable,
    str(repo / 'deploy/codex-relay/run-shared-bridge.py'), str(config_path)],
    RunAtLoad=True, KeepAlive=True, ThrottleInterval=10, WorkingDirectory=str(state),
    EnvironmentVariables={'PATH': '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
    StandardOutPath='/dev/null', StandardErrorPath=str(state / 'stderr.log'))
encoded_config = json.dumps(config, indent=2).encode()
encoded_plist = plistlib.dumps(plist)
for path, data in [(config_path, encoded_config), (plist_path, encoded_plist)]:
    if path.exists() and path.read_bytes() != data:
        p.error('Existing deployment differs; review its configuration before replacing: ' + str(path))
if not a.apply:
    print(f'Will supervise Bridge on {a.host}:{a.port} as {a.label}; existing Codex backend is reused.')
    raise SystemExit()
state.mkdir(parents=True, exist_ok=True, mode=0o700)
state.chmod(0o700)
plist_path.parent.mkdir(parents=True, exist_ok=True)
for path, data in [(config_path, encoded_config), (plist_path, encoded_plist)]:
    path.write_bytes(data)
    path.chmod(0o600)
log = state / 'stderr.log'
log.touch(mode=0o600, exist_ok=True)
log.chmod(0o600)
domain = f'gui/{os.getuid()}'
running = subprocess.run(['launchctl', 'print', f'{domain}/{a.label}'],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
if running.returncode:
    subprocess.run(['launchctl', 'bootstrap', domain, str(plist_path)], check=True)
print('Installed ' + a.label)
