#!/usr/bin/env python3
"""Compare installed Codex :workspace sandbox with ordinary shell as App UID.

No model request, config write, permission change, or unconfined fallback.
Readiness only: exit 0 does not prove isolation or full user-flow acceptance.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('output', type=Path)
    parser.add_argument('--native', action='store_true', help='Capture status/signal directly from the installed native binary')
    parser.add_argument('--fd-exec', action='store_true', help='Also isolate PRoot execution through a close-on-exec file descriptor')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial) and os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') != '1':
        parser.error('Physical device requires explicit OOB_ALLOW_PHYSICAL_DEVICE=1')
    adb = ['adb', '-s', args.serial]
    def read(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=20).strip()
    apk = read('shell', 'pm', 'path', 'cn.com.omnimind.bot').splitlines()[0].removeprefix('package:')
    native = str(Path(apk).parent / 'lib/arm64')
    prefix = '/data/user/0/cn.com.omnimind.bot'
    package = json.loads(read('exec-out', 'run-as', 'cn.com.omnimind.bot', 'cat',
        'local/alpine/root/.npm-global/lib/node_modules/@openai/codex/package.json'))
    command = '/bin/bash -c ' + shlex.quote(
        'printf "OOB_CODEX_SANDBOX_STDOUT\\n"; printf "OOB_CODEX_SANDBOX_STDERR\\n" >&2; exit 7')
    report = {'device': args.serial, 'version': package['version'],
              'apkSha256': read('shell', 'sha256sum', apk).split()[0],
              'scope': 'Backend readiness only; no model or App configuration changes', 'cases': {}}
    sandbox_command = '/root/.npm-global/bin/codex sandbox -P :workspace -C /workspace -- ' + command
    if args.native:
        executable = '/root/.npm-global/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/bin/codex'
        argv = ['sandbox', '-P', ':workspace', '-C', '/workspace', '--'] + shlex.split(command)
        js = ("const {spawnSync}=require('node:child_process');"
            "const r=spawnSync(" + json.dumps(executable) + ',' + json.dumps(argv) +
            ",{encoding:'utf8',timeout:10000});"
            "console.log('OOB_NATIVE_STATUS='+JSON.stringify({status:r.status,signal:r.signal,error:r.error?.code}));"
            "process.stdout.write(r.stdout||'');process.stderr.write(r.stderr||'');"
            "process.exit(Number.isInteger(r.status)?r.status:125);")
        sandbox_command = 'node -e ' + shlex.quote(js)
    # Built-in :workspace follows the installed CLI's named permission interface.
    # https://learn.chatgpt.com/docs/permissions
    cases = [('shellControl', command), ('workspaceSandbox', sandbox_command)]
    if args.fd_exec:
        for inheritable in (False, True):
            # Python opens descriptors non-inheritable, like Rust File::open.
            # Both forms must work on native Linux; this probe changes only its
            # own descriptor flag, never Codex's sandbox or installed launcher.
            code = ('import os; fd=os.open("/bin/bash",os.O_RDONLY); '
                    f'os.set_inheritable(fd,{inheritable!r}); '
                    'os.execv("/proc/self/fd/"+str(fd),'
                    + repr(shlex.split(command)) + ')')
            cases.append(('fdInheritable' if inheritable else 'fdCloseOnExec',
                          'python3 -c ' + shlex.quote(code)))
        # Separate executable loading from the kernel's namespace restrictions.
        # This still enters bubblewrap isolation; no unconfined retry is used.
        bwrap = ('/root/.npm-global/lib/node_modules/@openai/codex/node_modules/'
                 '@openai/codex-linux-arm64/vendor/aarch64-unknown-linux-musl/'
                 'codex-resources/bwrap')
        cases.append(('bundledBwrapByPath', shlex.quote(bwrap) +
                      ' --ro-bind / / --dev /dev --proc /proc --unshare-user'
                      ' --unshare-pid --die-with-parent -- ' + command))
    for name, guest in cases:
        shell = f'''cd {prefix} || exit 1
export PREFIX={prefix} LINKER=/system/bin/linker64 LD_LIBRARY_PATH={prefix}/local/lib
export PROOT_LOADER={shlex.quote(native)}/libproot-loader.so PROOT_TMP_DIR={prefix}/tmp TMPDIR={prefix}/tmp
export OMNIBOT_TERMINAL_DISTRIBUTION=alpine OMNIBOT_HEADLESS=1 OMNIBOT_DISABLE_PROOT_LINK2SYMLINK=1
printf 'OOB_PROBE_UID:'; id -u
/system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc {shlex.quote(guest)}
printf 'OOB_HOST_EXIT:%s\\n' "$?"
'''
        result = subprocess.run(adb + ['shell', '-T', 'run-as', 'cn.com.omnimind.bot',
            '/system/bin/sh', '-c', shlex.quote(shell)], input='', text=True,
            capture_output=True, timeout=30)
        uid = re.search(r'^OOB_PROBE_UID:(\d+)$', result.stdout, re.M)
        status = re.search(r'^OOB_HOST_EXIT:(\d+)$', result.stdout, re.M)
        code = int(status[1]) if status else None
        passed = (result.returncode == 0 and uid is not None and int(uid[1]) >= 10000
            and code == 7 and 'OOB_CODEX_SANDBOX_STDOUT' in result.stdout.splitlines()
            and 'OOB_CODEX_SANDBOX_STDERR' in result.stderr.splitlines())
        report['cases'][name] = {'passed': passed, 'uid': int(uid[1]) if uid else None,
            'exitCode': code, 'adbExit': result.returncode,
            'stdout': result.stdout[:8000], 'stderr': result.stderr[:8000]}
        if args.native and name == 'workspaceSandbox':
            detail = re.search(r'^OOB_NATIVE_STATUS=(.+)$', result.stdout, re.M)
            report['cases'][name]['nativeProcess'] = json.loads(detail[1]) if detail else None
            if detail is None or report['cases'][name]['nativeProcess'].get('signal') is not None:
                report['cases'][name]['passed'] = False
    report['passed'] = all(case['passed'] for case in report['cases'].values())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
