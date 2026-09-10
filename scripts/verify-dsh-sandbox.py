#!/usr/bin/env python3
"""Run installed official DSH sandbox readiness checks as the Android app UID.
A zero exit only proves readiness, not complete isolation/UI acceptance.
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
js = r'''
import {createRequire} from 'node:module';
import {spawnSync} from 'node:child_process';
const req = createRequire('/root/.npm-global/lib/node_modules/@deepseek-ai/dsh/package.json');
const {Context} = await import(req.resolve('@deepseek-ai/cordis'));
const {LocalSandboxProvider} = await import(req.resolve('@deepseek-ai/dsh-sandbox-local'));
let landlock;
try { landlock = await import(req.resolve('@deepseek-ai/node-addon-system/landlock-run')); }
catch (error) {
  if (error.code !== 'MODULE_NOT_FOUND') throw error;
  landlock = await import(req.resolve('@deepseek-ai/node-addon-landlock-run'));
}
const launch = landlock.launcherPath();
const run = (cmd, argv) => {
  const r = spawnSync(cmd, argv, {encoding:'utf8', timeout:10000});
  return {status:r.status, stdout:r.stdout?.slice(0,2000), stderr:r.stderr?.slice(0,2000), error:r.error?.code};
};
const report = {version:req('./package.json').version, platform:process.platform,
  arch:process.arch, bash:run('/bin/bash',['-c','printf OOB_DSH_BASH_OK']),
  landlock:{launcher:launch, verdict:landlock.probe(), ...run(launch,['--probe'])},
  bwrap:run('bwrap',['--version']),
  // Exact read-only startup probe from the installed official sandbox-local
  // defaultProbeBwrap; keep diagnostics its stdio:'ignore' would discard.
  // This does not replace the provider's own confine/probe below.
  bwrapProfileProbe:run('bwrap',['--ro-bind','/','/','--dev','/dev',
    '--unshare-pid','--proc','/proc','--die-with-parent','--','true']), modes:{}};
const provider = new LocalSandboxProvider(new Context(), {
  runnerCommand:[], runnerFailureSignatures:[], probeTimeoutMs:5000});
for (const mode of ['read-only','workspace-write']) {
  try {
    const wrapped = provider.confine(['/bin/bash','-c','printf OOB_DSH_SANDBOX_OK'],
      {mode, workspaceRoot:'/workspace'});
    const result = run(wrapped.argv[0],wrapped.argv.slice(1));
    report.modes[mode] = {...result, enforcement:wrapped.enforcement,
      ready:result.status===0 && result.stdout==='OOB_DSH_SANDBOX_OK'};
  } catch(e) {report.modes[mode]={ready:false,code:e.code,message:e.message};}
}
console.log('OOB_DSH_PROBE='+JSON.stringify(report));
'''
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
line = next((x for x in r.stdout.splitlines() if x.startswith('OOB_DSH_PROBE=')), None)
if not line:
    raise RuntimeError(f'Probe did not complete: {r.stderr[-1500:]}')
report=json.loads(line.split('=',1)[1])
report['device']=args.serial
report['apkSha256']=adb('shell','sha256sum',apk).split()[0]
report['kernel']=adb('shell','uname','-r')
launcher=prefix+'/local/alpine'+report['landlock']['launcher']
direct=subprocess.run(base+['shell','run-as','cn.com.omnimind.bot',launcher,'--probe'],
                      text=True,capture_output=True,timeout=15)
report['landlockOutsideProot']={'status':direct.returncode,'stdout':direct.stdout.strip(),'stderr':direct.stderr.strip()}
report['ready']=all(x['ready'] for x in report['modes'].values())
report['acceptanceScope']='Backend readiness only; full UI and isolation acceptance still required if ready.'
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False,indent=2))
raise SystemExit(0 if report['ready'] else 1)
