import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,writeFileSync,mkdirSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
const script=readFileSync('app/src/main/assets/acp/install/opencode.sh','utf8');
for (const alpine of [false,true]) for (const broken of [false,true]) {
  test(`${alpine?'Alpine':'Ubuntu'} uses matching binary and ${broken?'rejects loader failure':'checks execution before linking'}`,()=>{
    const root=mkdtempSync(join(tmpdir(),'oob-opencode-'));
    try {
      const prefix=join(root,'npm'); const fakebin=join(root,'bin'); mkdirSync(fakebin);
      const platform=alpine?'opencode-linux-arm64-musl':'opencode-linux-arm64';
      const binary=join(prefix,'lib/node_modules',platform,'bin/opencode');
      mkdirSync(join(prefix,'bin'),{recursive:true}); mkdirSync(join(binary,'..'),{recursive:true});
      writeFileSync(binary,`#!/bin/sh\nexit ${broken?127:0}\n`,{mode:0o755});
      const log=join(root,'calls');
      writeFileSync(join(fakebin,'npm'),`#!/bin/sh\nprintf '%s\\n' "$*" >> '${log}'\n`,{mode:0o755});
      writeFileSync(join(fakebin,'node'),'#!/bin/sh\nprintf "1.18.29\\n"\n',{mode:0o755});
      const marker=join(root,'alpine-release'); if(alpine)writeFileSync(marker,'3.21');
      const result=spawnSync('/bin/sh',['-c',script.replaceAll('/root/.npm-global',prefix).replaceAll('/etc/alpine-release',marker)],{env:{...process.env,PATH:fakebin+':'+process.env.PATH},encoding:'utf8'});
      assert.equal(result.status,broken?127:0,result.stderr);
      assert(readFileSync(log,'utf8').includes(`${platform}@1.18.29`));
      const link=spawnSync('test',['-L',join(prefix,'bin/opencode')]);
      assert.equal(link.status,broken?1:0);
    }finally{rmSync(root,{recursive:true,force:true});}
  });
}
