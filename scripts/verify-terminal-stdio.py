#!/usr/bin/env python3
"""Exercise installed host/PRoot stdin and exit status as the App UID; no API request."""
import json, os, re, shlex, subprocess, sys
from pathlib import Path
serial, output = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+',serial) or (os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE')=='1' and re.fullmatch(r'[A-Za-z0-9._:-]+',serial))
base=['adb','-s',serial]
def adb(*args):return subprocess.check_output(base+list(args),text=True,timeout=20).strip()
apk=adb('shell','pm','path','cn.com.omnimind.bot').splitlines()[0].removeprefix('package:')
native=str(Path(apk).parent/'lib/arm64')
prefix='/data/user/0/cn.com.omnimind.bot'
command='read -r line; printf "OOB_STDIO_READ:%s\\n" "$line"; exit 7'
shell=f'''cd {prefix} || exit 1
test -f local/alpine/.omnibot-rootfs-ready || exit 1
probe_dir=$(mktemp -d cache/oob-stdio-probe.XXXXXX) || exit 1
trap 'rmdir "$probe_dir" 2>/dev/null || true' EXIT
export PREFIX={prefix} LINKER=/system/bin/linker64
export LD_LIBRARY_PATH=$PREFIX/local/lib PROOT_LOADER={shlex.quote(native)}/libproot-loader.so
export PROOT_TMP_DIR=$PREFIX/$probe_dir TMPDIR=$PREFIX/tmp
export OMNIBOT_TERMINAL_DISTRIBUTION=alpine OMNIBOT_HEADLESS=1 OMNIBOT_DISABLE_PROOT_LINK2SYMLINK=1
printf 'OOB_STDIO_UID:'; id -u
/system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc {shlex.quote(command)}
printf 'OOB_STDIO_EXIT:%s\\n' "$?"
'''
# -c leaves stdin entirely for the runtime; feeding the script through stdin
# would let the outer shell buffer input intended for the guest.
r=subprocess.run(base+['shell','-T','run-as','cn.com.omnimind.bot','/system/bin/sh','-c',shlex.quote(shell)],input='OOB_PIPE_INPUT\n',text=True,capture_output=True,timeout=30)
lines=r.stdout.splitlines()
assert 'OOB_STDIO_READ:OOB_PIPE_INPUT' in lines, 'Installed runtime lost stdin: '+r.stdout[-1000:]+r.stderr[-500:]
assert 'OOB_STDIO_EXIT:7' in lines, 'Installed runtime lost nonzero exit status'
uid=next(line.split(':')[1] for line in lines if line.startswith('OOB_STDIO_UID:'))
assert int(uid)>=10000, 'Probe must execute as the App UID'
report={'passed':True,'serial':serial,'uid':int(uid),'stdin':'preserved','exitCode':7,'apkSha256':adb('shell','sha256sum',apk).split()[0],'kind':'installed-runtime-probe-not-UI-acceptance'}
Path(output).write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
