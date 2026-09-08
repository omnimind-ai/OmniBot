#!/usr/bin/env node
// Exercise the packaged Ubuntu hardlink entries under the real Android app UID.
// Usage: ADB=/path/to/adb node scripts/verify-rootfs-hardlinks.mjs SERIAL [production|proot|plain|full]
// Extracts two members (or the entire archive in full mode) into a new app-cache directory.
// Existing rootfs/user files are not changed; the diagnostic fixture remains in cache.
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const [serial, mode = 'production'] = process.argv.slice(2);
assert(serial && !serial.startsWith('-'), 'Explicit device serial required');
assert(['production', 'proot', 'plain', 'full'].includes(mode), 'Unknown extraction mode');
const prefix = '/data/user/0/cn.com.omnimind.bot';
const apk = execFileSync(process.env.ADB || 'adb', ['-s', serial, 'shell', 'pm',
  'path', 'cn.com.omnimind.bot'], {encoding: 'utf8', timeout: 15000}).trim();
assert(/^package:\/[^\n]+\/base\.apk$/.test(apk), 'Expected one installed base APK');
const nativeDir = apk.slice('package:'.length, -'/base.apk'.length) + '/lib/arm64';
const source = readFileSync(new URL('../ReTerminal/core/main/src/main/assets/init-host.sh',
  import.meta.url), 'utf8');
const extraction = source.match(/if ! run_child ([\s\S]*?-xf "\$ROOTFS_ARCHIVE" -C "\$ROOTFS_DIR"); then/);
assert(extraction, 'Cannot locate production rootfs extraction command');
const extractCommand = (mode === 'production' || mode === 'full') ? extraction[1] :
  `${mode === 'proot' ? '"$LINKER" "$PREFIX/local/bin/proot" --link2symlink ' : ''}` +
  '/system/bin/tar -xf "$ROOTFS_ARCHIVE" -C "$ROOTFS_DIR"';
const command = `set -eu
cd ${prefix}
probe=$(mktemp -d cache/oob-rootfs-hardlinks.XXXXXX)
export LD_LIBRARY_PATH=${prefix}/local/lib
export PROOT_TMP_DIR=${prefix}/$probe
export PROOT_LOADER=${nativeDir}/libproot-loader.so
PREFIX=${prefix}
LINKER=/system/bin/linker64
ROOTFS_ARCHIVE=$PREFIX/files/ubuntu.tar.gz
ROOTFS_DIR=$PREFIX/$probe
${extractCommand} ${mode === 'full' ? '' : 'usr/bin/gunzip usr/bin/uncompress'}
test -s "$probe/usr/bin/uncompress"
cmp "$probe/usr/bin/gunzip" "$probe/usr/bin/uncompress"
${mode === 'full' ? `
"$LINKER" "$PREFIX/local/bin/proot" -0 -r "$ROOTFS_DIR" -w /root /bin/sh -c '
  set -e
  . /etc/os-release
  test "$ID" = ubuntu
  /bin/pwd
  /usr/bin/apt-get --version | /usr/bin/head -n 1
  test -s /var/lib/dpkg/status
  printf "persistent-rootfs-check\\n" > /root/oob-install-probe
'
"$LINKER" "$PREFIX/local/bin/proot" -0 -r "$ROOTFS_DIR" -w /root /bin/sh -c '
  test "$(/usr/bin/cat /root/oob-install-probe)" = persistent-rootfs-check
'
` : ''}
echo "PASS: ${mode} archive hardlink extraction; fixture=$probe"
`;
const quote = s => `'${s.replaceAll("'", "'\\''")}'`;
try {
  process.stdout.write(execFileSync(process.env.ADB || 'adb', [
    '-s', serial, 'shell', 'run-as', 'cn.com.omnimind.bot',
    'sh', '-c', quote(command),
  ], {encoding: 'utf8', timeout: mode === 'full' ? 180000 : 30000, stdio: ['ignore', 'pipe', 'pipe']}));
} catch (error) {
  process.stderr.write(error.stderr?.toString() || 'Device probe failed\n');
  process.exitCode = 1;
}
