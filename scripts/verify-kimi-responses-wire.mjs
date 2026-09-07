// Real installed Kimi ACP -> local Responses fixture, no real credentials or inference.
// Usage: node scripts/verify-kimi-responses-wire.mjs emulator-N
// Requires Ubuntu, Kimi and the app ACP filesystem compatibility preload installed.
// Uses an isolated temporary Kimi home; never overwrites the configured Provider.
import {execFileSync,spawn} from 'node:child_process';
import {createServer} from 'node:http';
import assert from 'node:assert/strict';
const serial=process.argv[2],pkg='cn.com.omnimind.bot';
assert(/^emulator-\d+$/.test(serial || ''), 'Explicit emulator serial required');
const q=s=>"'"+s.replaceAll("'","'\\''")+"'";
const apk=execFileSync('adb',['-s',serial,'shell','pm','path',pkg],{encoding:'utf8'}).trim();
const native=apk.slice(8,-9)+'/lib/arm64';
let reached=false,initialized=false,sessionCreated=false;
const server=createServer((req,res)=>{let body='';req.on('data',x=>body+=x);req.on('end',()=>{
 const data=JSON.parse(body||'{}');
 reached=req.url==='/v1/responses'&&data.model==='oob-responses-probe'&&req.headers.authorization==='Bearer oob-fake-key';
 console.log(JSON.stringify({wireReached:reached,path:req.url,model:data.model}));
 res.writeHead(401,{'content-type':'application/json'});res.end(JSON.stringify({error:{message:'Deliberate local probe rejection',type:'invalid_request_error'}}));
});});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const port=server.address().port;
const config=`default_model = "omnibot"\n[providers.omnibot]\ntype = "openai_responses"\nbase_url = "http://10.0.2.2:${port}/v1"\napi_key = "oob-fake-key"\n[models.omnibot]\nprovider = "omnibot"\nmodel = "oob-responses-probe"\nmax_context_size = 262144\ncapabilities = ["thinking", "image_in"]\n`;
const prefix='/data/user/0/'+pkg;
const command=`set -eu
cd ${prefix}
probe=$(mktemp -d local/ubuntu/tmp/oob-kimi-response.XXXXXX)
proot_tmp=$(mktemp -d cache/oob-kimi-proot.XXXXXX)
printf %s ${q(config)} > "$probe/config.toml"
export KIMI_CODE_HOME=/tmp/\${probe##*/}
export KIMI_CODE_NO_AUTO_UPDATE=1 KIMI_DISABLE_TELEMETRY=1
export PREFIX=${prefix} HOME=/root LINKER=/system/bin/linker64
export LD_LIBRARY_PATH=$PREFIX/local/lib PROOT_LOADER=${native}/libproot-loader.so
export PROOT_TMP_DIR=$PREFIX/$proot_tmp TMPDIR=$PREFIX/tmp OMNIBOT_TERMINAL_DISTRIBUTION=ubuntu OMNIBOT_HEADLESS=1
export NODE_OPTIONS='--require /root/.omnibot/acp-fs-compat.cjs'
exec /system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc 'export PATH=/root/.npm-global/bin:$PATH; cd /tmp; exec kimi acp'
`;
const child=spawn('adb',['-s',serial,'shell','-T','run-as',pkg,'sh','-c',q(command)],{stdio:['pipe','pipe','pipe']});
const send=(id,method,params)=>child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
const timer=setTimeout(()=>{console.log('probe timeout');child.kill();server.close();process.exitCode=1;},90000);
let pending='',stderr='';
child.stderr.on('data',x=>stderr+=x);
child.stdout.on('data',x=>{pending+=x;const lines=pending.split('\n');pending=lines.pop();for(const line of lines){let m;try{m=JSON.parse(line);}catch{continue;}
 if(m.id===1){initialized=m.result?.protocolVersion===1;console.log(JSON.stringify({initialized,error:m.error}));if(initialized)send(2,'session/new',{cwd:'/tmp',mcpServers:[]});else child.stdin.end();}
 if(m.id===2){sessionCreated=!!m.result?.sessionId;console.log(JSON.stringify({sessionCreated,error:m.error}));if(sessionCreated)send(3,'session/prompt',{sessionId:m.result.sessionId,prompt:[{type:'text',text:'Say OK'}]});else child.stdin.end();}
 if(m.id===3){console.log(JSON.stringify({officialPromptTerminated:true,expectedFixtureError:!!m.error}));child.stdin.end();}
}});
child.on('close',code=>{clearTimeout(timer);server.close();console.log(JSON.stringify({initialized,sessionCreated,reached,exitCode:code}));if(!initialized||!sessionCreated||!reached){console.log(stderr.slice(-1500));process.exitCode=1;}});
send(1,'initialize',{protocolVersion:1,clientInfo:{name:'oob-regression',version:'1'},clientCapabilities:{}});
