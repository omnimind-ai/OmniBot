#!/usr/bin/env python3
"""Install isolated, authenticated Codex test backend + reverse SSH launch agents.
No public listener or existing website configuration is changed.
"""
import argparse, os, pathlib, plistlib, re, secrets, subprocess
p = argparse.ArgumentParser()
p.add_argument('--ssh-host', required=True)
p.add_argument('--codex', default='/Applications/ChatGPT.app/Contents/Resources/codex')
p.add_argument('--local-port', type=int, default=17329)
p.add_argument('--remote-port', type=int, default=18329)
p.add_argument('--apply', action='store_true')
p.add_argument('--tunnel-only', action='store_true', help='Supervise the ACP tunnel without starting another Codex backend')
p.add_argument('--tunnel-label', default='cn.omnimind.codex-acp-relay-test', help='Distinct launchd label for a tunnel-only deployment')
p.add_argument('--test-forward-port', type=int, help='Optional local loopback forward through the relay for device tests; requires --tunnel-only')
a = p.parse_args()
if not re.fullmatch(r'[A-Za-z][A-Za-z0-9.-]+', a.tunnel_label): p.error('Invalid launchd tunnel label')
if not a.tunnel_only and not pathlib.Path(a.codex).is_file(): p.error('Codex executable does not exist')
if a.test_forward_port and not a.tunnel_only: p.error('--test-forward-port requires --tunnel-only')
if any(not 1024 <= port <= 65535 for port in [a.local_port, a.remote_port] + ([a.test_forward_port] if a.test_forward_port is not None else [])): p.error('Ports must be 1024..65535')
if a.test_forward_port == a.local_port: p.error('Test forward must not reuse the backend port')
if a.ssh_host.startswith('-'): p.error('SSH host must not be an option')
home = pathlib.Path.home()
state = home / 'Library/Application Support/OmniBot/codex-relay-test'
logs = home / 'Library/Logs/OmniBot/codex-relay-test'
agent_dir = home / 'Library/LaunchAgents'
token = state / 'capability-token'
configs = {
 'cn.omnimind.codex-shared-test': [a.codex, 'app-server', '--listen', f'ws://127.0.0.1:{a.local_port}', '--ws-auth', 'capability-token', '--ws-token-file', str(token)],
 'cn.omnimind.codex-relay-test': ['/usr/bin/ssh', '-NT', '-o', 'BatchMode=yes', '-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3', '-o', 'ConnectTimeout=10', '-o', 'ControlMaster=no', '-o', 'ControlPath=none', '-R', f'127.0.0.1:{a.remote_port}:127.0.0.1:{a.local_port}', a.ssh_host],
}
if a.tunnel_only:
 configs = {a.tunnel_label: configs['cn.omnimind.codex-relay-test']}
 if a.test_forward_port:
  arguments = list(configs[a.tunnel_label])
  arguments[-3:-1] = ['-L', f'127.0.0.1:{a.test_forward_port}:127.0.0.1:{a.remote_port}']
  forward_label = 'cn.omnimind.codex-acp-forward-test' if a.tunnel_label == 'cn.omnimind.codex-acp-relay-test' else a.tunnel_label + '.forward'
  configs[forward_label] = arguments
if not a.apply:
 print(f'Will install {len(configs)} login launch agents: {", ".join(configs)}; remote loopback port {a.remote_port}. Use --apply.')
 raise SystemExit()
for directory in [state, logs, agent_dir]: directory.mkdir(parents=True, exist_ok=True)
state.chmod(0o700)
if not a.tunnel_only and not token.exists():
 fd = os.open(token, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
 with os.fdopen(fd, 'w') as f: f.write(secrets.token_urlsafe(48))
if not a.tunnel_only: token.chmod(0o600)
domain = f'gui/{os.getuid()}'
for label, arguments in configs.items():
 path = agent_dir / f'{label}.plist'
 config = dict(Label=label, ProgramArguments=arguments, RunAtLoad=True, KeepAlive=True,
  ThrottleInterval=10, WorkingDirectory=str(state),
  EnvironmentVariables={'PATH': '/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin'},
  StandardOutPath=str(logs / f'{label}.out.log'), StandardErrorPath=str(logs / f'{label}.err.log'))
 encoded = plistlib.dumps(config)
 if path.exists() and path.read_bytes() != encoded:
  raise RuntimeError(f'Existing configuration differs: {path}; review before replacement')
 path.write_bytes(encoded); path.chmod(0o600)
 result = subprocess.run(['launchctl', 'print', f'{domain}/{label}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
 if result.returncode: subprocess.run(['launchctl', 'bootstrap', domain, str(path)], check=True)
 print(f'Installed {label}')
if not a.tunnel_only: print(f'Token file (not printed): {token}')
