#!/usr/bin/env python3
"""Verify plugin reference bootstrap with the installed official DSH skill provider.
Uses an isolated temporary DSH home; no user profile changes.
No model request, permission change, installation, or fallback to unconfined mode.
"""
import argparse
import json
import os
import shlex
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('serial')
parser.add_argument('output', type=Path)
args = parser.parse_args()
if not args.serial.startswith('emulator-') and os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') != '1':
    parser.error('Physical device requires OOB_ALLOW_PHYSICAL_DEVICE=1')
base = ['adb', '-s', args.serial]
def adb(*cmd):
    return subprocess.check_output(base + list(cmd), text=True, timeout=20).strip()
apk = adb('shell', 'pm', 'path', 'cn.com.omnimind.bot').splitlines()[0].removeprefix('package:')
native = str(Path(apk).parent / 'lib/arm64')
prefix = '/data/user/0/cn.com.omnimind.bot'
installer = Path('app/src/main/assets/acp/install/deepseek-harness.sh').read_text()
js = r'''
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
const root='/root/.npm-global/lib/node_modules/@deepseek-ai/dsh';
const req=createRequire(root+'/package.json');
const {Context}=await import(req.resolve('@deepseek-ai/cordis'));
const {FileSystemSkillProvider}=await import(req.resolve('@deepseek-ai/dsh-skill-filesystem'));
const installer=INSTALLER;
const reference=installer.split("node <<'OMNIBOT_DSH_PLUGIN_REFERENCE'\n")[1].split('\nOMNIBOT_DSH_PLUGIN_REFERENCE')[0];
const home=fs.mkdtempSync('/tmp/oob-dsh-reference-');
process.env.DSH_HOME=home; process.env.DSH_PACKAGE_ROOT=root;
process.env.DSH_BUNDLED_SKILL_DIR=home+'/omnibot-bundled-skills';
const {spawnSync}=await import('node:child_process');
try {
 for(let i=0;i<2;i++) {
  const r=spawnSync(process.execPath,['-e',reference],{encoding:'utf8'});
  assert.equal(r.status,0,r.stderr);
  const stop=new AbortController();
  const provider=new FileSystemSkillProvider(new Context(),{signal:stop.signal,invalidate(){}},{watch:false});
  const result=await provider.list({});
  const entries=Array.isArray(result)?result:result.candidates;
  const skill=entries.find(x=>x.name==='dsh-plugins');
  assert.ok(skill,'official skill provider must discover generated reference');
  const loaded=await provider.get(skill,{signal:stop.signal});
  assert.ok(JSON.stringify(loaded).includes('cordis_define'));
  stop.abort();
 }
 console.log('OOB_DSH_REFERENCE='+JSON.stringify({device:DEVICE,upstream:req('./package.json').version,passed:true,scope:'fresh isolated home and repeated official skill discovery; no model calls, no permission change',installerSha256:createHash('sha256').update(installer).digest('hex')}));
} finally {fs.rmSync(home,{recursive:true,force:true});}
'''
js = js.replace('INSTALLER', json.dumps(installer)).replace('DEVICE', json.dumps(args.serial))
shell = f'''set -eu
cd {prefix}
test -f local/alpine/.omnibot-rootfs-ready
probe_dir=$(mktemp -d cache/oob-dsh-probe.XXXXXX)
trap 'rmdir "$probe_dir" 2>/dev/null || true' EXIT
export PREFIX={prefix} LINKER=/system/bin/linker64
export LD_LIBRARY_PATH=$PREFIX/local/lib PROOT_LOADER={shlex.quote(native)}/libproot-loader.so
export PROOT_TMP_DIR=$PREFIX/$probe_dir TMPDIR=$PREFIX/tmp
export OMNIBOT_TERMINAL_DISTRIBUTION=alpine OMNIBOT_HEADLESS=1 OMNIBOT_DISABLE_PROOT_LINK2SYMLINK=1
/system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc {shlex.quote('node --input-type=module -e ' + shlex.quote(js))}
'''
r = subprocess.run(base+['shell','run-as','cn.com.omnimind.bot','sh'],input=shell,
                   text=True,capture_output=True,timeout=60)
line = next((x for x in r.stdout.splitlines() if x.startswith('OOB_DSH_REFERENCE=')), None)
if not line:
    raise RuntimeError(f'Probe did not complete: {r.stderr[-1500:]}')
report=json.loads(line.split('=',1)[1])
report['device']=args.serial
report['apkSha256']=adb('shell','sha256sum',apk).split()[0]
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n')
print(json.dumps(report, ensure_ascii=False, indent=2))
