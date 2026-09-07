// Local deterministic Provider for emulator UI acceptance. Uses no real credentials.
// Configure an isolated test Provider at http://10.0.2.2:PORT/v1.
// Send Reply OOB_ROLE_GUARDS, Reply OOB_TRUNCATED_GUARD, or Reply OOB_DISCONNECT.
// Logs assertions only; never logs prompts, headers, or user history.
import http from 'node:http';
import assert from 'node:assert/strict';
const model = 'oob-role-fixture';
let sequence = 0;
const text = m => typeof m.content === 'string' ? m.content : JSON.stringify(m.content ?? '');
const server = http.createServer(async (req, res) => {
  if (req.url === '/v1/models') {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({data:[{id:model, object:'model', owned_by:'local-fixture'}]})); return;
  }
  if (req.url !== '/v1/chat/completions') {res.writeHead(404).end(); return;}
  try {
    const chunks=[]; for await (const c of req) chunks.push(c);
    const body=JSON.parse(Buffer.concat(chunks));
    assert.equal(body.model,model);
    const messages=body.messages;
    const lastUser=text(messages.filter(m=>m.role==='user').at(-1));
    const last=messages.at(-1);
    const tools=(body.tools??[]).map(t=>t.function.name);
    const event=(delta,finish=null)=>res.write(`data: ${JSON.stringify({id:`fixture-${sequence}`,object:'chat.completion.chunk',model,choices:[{index:0,delta,finish_reason:finish}]})}\n\n`);
    const answer=value=>{event({role:'assistant',content:value},'stop');res.end('data: [DONE]\n\n');};
    const call=(name,args,finish='tool_calls')=>{event({role:'assistant',tool_calls:[{index:0,id:`oob_call_${sequence}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]},finish);res.end('data: [DONE]\n\n');};
    sequence++;
    console.log(JSON.stringify({sequence,phase:'request',tools:tools.length,lastRole:last.role}));
    res.writeHead(200,{'content-type':'text/event-stream','cache-control':'no-cache'});
    if (lastUser.includes('OOB_PLANNER_CHILD') || lastUser.includes('OOB_EXPLORER_CHILD')) {
      const planner=lastUser.includes('OOB_PLANNER_CHILD');
      const marker=planner?'OOB_PLANNER_BLOCKED':'OOB_EXPLORER_BLOCKED';
      assert(!tools.includes('file_write'));
      if(planner) assert.equal(tools.length,0);
      else assert(tools.includes('file_read'));
      if(last.role==='tool') {
        assert.match(text(last),/role permissions/);
        console.log(JSON.stringify({phase:'assertion',marker,passed:true}));answer(marker);
      } else call('file_write',{path:'OOB_FORBIDDEN_ROLE.txt',content:'MUST_NOT_BE_WRITTEN'});
    } else if(lastUser.includes('OOB_ROLE_GUARDS')) {
      if(last.role==='tool') {
        assert.match(text(last),/OOB_PLANNER_BLOCKED/);assert.match(text(last),/OOB_EXPLORER_BLOCKED/);
        console.log(JSON.stringify({phase:'assertion',marker:'OOB_ROLE_GUARDS_PASS',passed:true}));answer('OOB_ROLE_GUARDS_PASS');
      } else {
        assert(tools.includes('subagent_dispatch'));
        call('subagent_dispatch',{tasks:[{profileId:'planner',instruction:'OOB_PLANNER_CHILD'},{profileId:'explorer',instruction:'OOB_EXPLORER_CHILD'}],concurrency:2});
      }
    } else if(lastUser.includes('OOB_TRUNCATED_GUARD')) {
      if(last.role==='tool') {
        assert.match(text(last),/length limit|长度上限/);
        console.log(JSON.stringify({phase:'assertion',marker:'OOB_TRUNCATED_GUARD_PASS',passed:true}));answer('OOB_TRUNCATED_GUARD_PASS');
      } else call('file_write',{path:'OOB_FORBIDDEN_TRUNCATED.txt',content:'MUST_NOT_BE_WRITTEN'},'length');
    } else if(lastUser.includes('OOB_CANCEL')) {
      event({role:'assistant',content:'OOB_PARTIAL_BEFORE_CANCEL'});
      res.on('close',()=>console.log(JSON.stringify({phase:'cancel_connection_closed',passed:!res.writableEnded})));
    } else if(lastUser.includes('OOB_DISCONNECT')) {
      event({role:'assistant',content:'OOB_VISIBLE_BEFORE_DISCONNECT'});res.end();
    } else if(lastUser.includes('OOB_RECOVERY')) answer('OOB_RECOVERY_PASS');
    else {throw Error('Unrecognized isolated test marker');}
  } catch(error) {
    console.log(JSON.stringify({phase:'assertion',passed:false,error:error.message}));
    if(!res.headersSent)res.writeHead(500);res.end();
  }
});
server.listen(Number(process.env.OOB_FIXTURE_PORT||18766),'127.0.0.1',()=>console.log(JSON.stringify({phase:'listening',port:server.address().port})));
