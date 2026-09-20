#!/usr/bin/env python3
"""Verify Ubuntu after actual first-run UI setup; emulator only. Restarts the app."""
import json, os, re, shlex, subprocess, sys
from pathlib import Path
serial, output = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+', serial), 'Dedicated emulator required'
base = [os.environ.get('ADB', 'adb'), '-s', serial]
def adb(*args, timeout=30):
    return subprocess.check_output(base + list(args), text=True, timeout=timeout).strip()
pkg = 'cn.com.omnimind.bot'
apk = adb('shell', 'pm', 'path', pkg).removeprefix('package:')
assert '\n' not in apk
native = str(Path(apk).parent / 'lib/arm64')
prefix = '/data/user/0/' + pkg
shell = f'''set -e
cd {prefix}
test -f local/ubuntu/.omnibot-rootfs-ready
export PREFIX={prefix} LINKER=/system/bin/linker64 NATIVE_LIB_DIR={shlex.quote(native)}
export LD_LIBRARY_PATH=$PREFIX/local/lib PROOT_LOADER=$NATIVE_LIB_DIR/libproot-loader.so
export PROOT_TMP_DIR=$PREFIX/cache/ubuntu-startup-test TMPDIR=$PREFIX/cache
mkdir -p "$PROOT_TMP_DIR"
export OMNIBOT_TERMINAL_DISTRIBUTION=ubuntu OMNIBOT_HEADLESS=1
/system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc '''
def probe(command):
    return adb('shell', 'run-as', pkg, '/system/bin/sh', '-c',
               shlex.quote(shell + shlex.quote(command)), timeout=90)
first = probe('set -e; . /etc/os-release; test "$ID" = ubuntu; printf "OS=%s\\n" "$PRETTY_NAME"; '
              'apt-get --version | head -n 1; node --version; npm --version; '
              'node -e \'if (+process.versions.node.split(".")[0] < 22) process.exit(1); console.log("NODE_EXEC_OK")\'; '
              'printf "ubuntu-restart-probe" > /root/.oob-ubuntu-startup-probe')
assert 'NODE_EXEC_OK' in first, first
adb('shell', 'am', 'force-stop', pkg)
adb('shell', 'am', 'start', '-W', '-n', pkg + '/.activity.LauncherActivity')
second = probe('set -e; test "$(cat /root/.oob-ubuntu-startup-probe)" = ubuntu-restart-probe; '
               'node -e \'console.log("RESTART_NODE_OK")\'; rm /root/.oob-ubuntu-startup-probe')
assert 'RESTART_NODE_OK' in second, second
result = {'passed': True, 'kind': 'emulator-installed-runtime-not-physical-device', 'serial': serial,
          'fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint'),
          'apkSha256': adb('shell', 'sha256sum', apk).split()[0], 'first': first, 'afterAppRestart': second}
Path(output).write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(result, ensure_ascii=False))
