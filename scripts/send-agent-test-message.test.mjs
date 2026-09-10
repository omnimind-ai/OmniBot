import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,readFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
test('empty initial UI observation is retried before touching an existing draft',()=>{
 const root=mkdtempSync(join(tmpdir(),'oob-send-readiness-'));
 try {
  const adb=join(root,'adb'),calls=join(root,'calls');
  const xml='<node package="cn.com.omnimind.bot" class="android.widget.EditText" text="existing draft" />';
  writeFileSync(adb,`#!/usr/bin/env node
const fs=require('node:fs');const args=process.argv.slice(2);const path=${JSON.stringify(calls)};
const prior=fs.existsSync(path)?fs.readFileSync(path,'utf8'):'';
fs.appendFileSync(path,JSON.stringify(args)+'\\n');
if(args.includes('dump')) {if(prior.includes('dump'))process.stdout.write('UI hierchary dumped to: test');}
else if(args.includes('cat'))process.stdout.write(${JSON.stringify(xml)});
else process.exit(9);
`,{mode:0o700});
  const result=spawnSync(process.execPath,['scripts/send-agent-test-message.mjs','emulator-1234','OOB_READINESS'],{env:{...process.env,ADB:adb},encoding:'utf8',timeout:10000});
  assert.equal(result.status,1);
  assert.match(result.stderr,/Preserve an existing draft/);
  const observations=readFileSync(calls,'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(observations.filter(a=>a.includes('dump')).length,2);
  assert(!observations.some(a=>a.includes('input')));
 }finally{rmSync(root,{recursive:true,force:true});}
});

test('empty focus observation is retried without replaying the composer tap',()=>{
 const root=mkdtempSync(join(tmpdir(),'oob-send-focus-'));
 try {
  const adb=join(root,'adb'),calls=join(root,'calls');
  const xml='<node package="cn.com.omnimind.bot" class="android.widget.EditText" text="" focused="false" bounds="[0,0][100,100]" />';
  writeFileSync(adb,`#!/usr/bin/env node
const fs=require('node:fs');const args=process.argv.slice(2);const path=${JSON.stringify(calls)};
const prior=fs.existsSync(path)?fs.readFileSync(path,'utf8').trim().split('\\n').filter(Boolean).map(JSON.parse):[];
fs.appendFileSync(path,JSON.stringify(args)+'\\n');
if(args.includes('dump')) {if(prior.filter(a=>a.includes('dump')).length!==1)process.stdout.write('UI hierchary dumped to: test');}
else if(args.includes('cat'))process.stdout.write(${JSON.stringify(xml)});
else if(!args.includes('tap'))process.exit(9);
`,{mode:0o700});
  const result=spawnSync(process.execPath,['scripts/send-agent-test-message.mjs','emulator-1234','OOB_FOCUS'],{env:{...process.env,ADB:adb},encoding:'utf8',timeout:10000});
  assert.equal(result.status,1);
  assert.match(result.stderr,/Composer did not gain focus/);
  const observations=readFileSync(calls,'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(observations.filter(a=>a.includes('dump')).length,3);
  assert.equal(observations.filter(a=>a.includes('tap')).length,1);
  assert(!observations.some(a=>a.includes('text')));
 }finally{rmSync(root,{recursive:true,force:true});}
});
