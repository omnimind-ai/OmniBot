#!/usr/bin/env node
// Supplemental transport regression with a fake ACP peer, not device acceptance.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn } = require('node:child_process');
const { once } = require('node:events');
const WebSocket = require('ws');
const root = fs.mkdtempSync(path.join(os.tmpdir(), 'oob-bridge-regression-'));
let bridge;
async function until(check) {
  const end = Date.now()+10000;
  while (Date.now()<end) { if (check()) return; await new Promise(r=>setTimeout(r,20)); }
  throw Error('Regression deadline exceeded');
}
(async()=>{
  const source = path.resolve(__dirname,'../tools/codex-bridge');
  for(const name of ['server.mjs','package.json']) fs.copyFileSync(path.join(source,name),path.join(root,name));
  fs.mkdirSync(path.join(root,'node_modules'));
  for (const name of ['ws','qrcode-terminal']) {
    fs.symlinkSync(path.dirname(require.resolve(name+'/package.json')),path.join(root,'node_modules',name),'dir');
  }
  const fixture = path.join(root,'fixture.cjs');
  fs.writeFileSync(fixture, `#!${process.execPath}
const fs=require('fs'),path=require('path'),rl=require('readline');
if(process.argv.includes('--version')){console.log('fixture 1');process.exit(0)}
const dir=process.env.OOB_FIXTURE_ROOT;let active;
const out=m=>console.log(JSON.stringify({jsonrpc:'2.0',...m}));
process.on('SIGTERM',()=>{fs.writeFileSync(path.join(dir,active.mode+'.closed'),'yes');process.exit(0)});
rl.createInterface({input:process.stdin}).on('line',line=>{
 const m=JSON.parse(line);
 if(m.method==='session/prompt'){
  active={id:m.id,mode:m.params.mode};
  out({method:'session/update',params:{sessionId:'fixture',update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'started'}}}});
  if(active.mode==='pending')out({id:90,method:'session/request_permission',params:{sessionId:'fixture'}});
  const timer=setInterval(()=>{
   if(!fs.existsSync(path.join(dir,active.mode+'.release')))return;
   clearInterval(timer);
   if(active.mode==='after')out({id:90,method:'session/request_permission',params:{sessionId:'fixture'}});
   else if(active.mode!=='pending'){
    fs.writeFileSync(path.join(dir,active.mode+'.terminal'),'yes');
    out(active.mode==='error'?{id:active.id,error:{code:-32000,message:'fixture failure'}}:{id:active.id,result:{stopReason:'end_turn'}});
   }
  },10);
 }else if(m.id===90){
  fs.writeFileSync(path.join(dir,active.mode+'.permission'),JSON.stringify(m));
  fs.writeFileSync(path.join(dir,active.mode+'.terminal'),'yes');
  out({id:active.id,result:{stopReason:'refusal'}});
 }
});
`,{mode:0o700});
  // Reserve a temporary loopback port; no user services are touched.
  const net=require('node:net');const listener=net.createServer();listener.listen(0,'127.0.0.1');await once(listener,'listening');
  const port=listener.address().port;await new Promise(r=>listener.close(r));
  bridge=spawn(process.execPath,[path.join(root,'server.mjs'),'--host','127.0.0.1','--port',String(port),'--cwd',root,'--acp-bin',fixture,'--no-interactive','--token','regression-only'],{env:{...process.env,OOB_FIXTURE_ROOT:root},stdio:['ignore','pipe','ignore']});
  let ready=false;bridge.stdout.on('data',()=>{ready=true});await until(()=>ready);
  for(const mode of ['success','error','pending','after']){
    const ws=new WebSocket(`ws://127.0.0.1:${port}/codex`);const messages=[];
    ws.on('message',b=>messages.push(JSON.parse(b)));
    await once(ws,'open');ws.send(JSON.stringify({type:'hello',token:'regression-only',cwd:root}));
    await until(()=>messages.some(m=>m.type==='hello' && m.ok));
    ws.send(JSON.stringify({type:'stdin',line:JSON.stringify({jsonrpc:'2.0',id:7,method:'session/prompt',params:{sessionId:'fixture',mode}})}));
    await until(()=>messages.some(m=>m.type==='stdout' && JSON.parse(m.line).method===(mode==='pending'?'session/request_permission':'session/update')));
    ws.close();await once(ws,'close');
    if(mode!=='pending')assert(!fs.existsSync(path.join(root,mode+'.closed')),'Prompt must survive disconnect');
    fs.writeFileSync(path.join(root,mode+'.release'),'yes');
    await until(()=>fs.existsSync(path.join(root,mode+'.closed')));
    assert(fs.existsSync(path.join(root,mode+'.terminal')),'Official terminal response precedes process cleanup');
    if(['pending','after'].includes(mode)){
      const response=JSON.parse(fs.readFileSync(path.join(root,mode+'.permission')));
      assert.equal(response.jsonrpc,'2.0');assert.equal(response.id,90);assert(response.error);assert(!response.result);
    }
    console.log('PASS detached transport:',mode);
  }
})().catch(e=>{console.error(e);process.exitCode=1}).finally(async()=>{
  if(bridge && bridge.exitCode===null){bridge.kill();await once(bridge,'exit');}
  fs.rmSync(root,{recursive:true,force:true});
});
