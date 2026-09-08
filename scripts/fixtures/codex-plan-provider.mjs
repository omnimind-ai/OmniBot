// Local deterministic model only; the app still runs real Codex + official ACP.
import {createServer} from 'node:http';
import {respondHarnessSuccess} from '../harness-success-fixture.mjs';
createServer(async (req,res) => {
  if (req.url.endsWith('/models')) {
    res.writeHead(200,{'content-type':'application/json'});
    res.end(JSON.stringify({data:[{id:'gpt-4o',object:'model'}]})); return;
  }
  const chunks=[]; for await(const c of req) chunks.push(c);
  const body=JSON.parse(Buffer.concat(chunks).toString());
  const input=body.input ?? body.messages ?? [];
  const users=Array.isArray(input) ? input.filter(m=>m.role==='user') : [];
  const text=JSON.stringify(users);
  const marker=[...text.matchAll(/OOB_CODEX_(?:PLAN|DEFAULT|HOLD)_[A-Z0-9_]+/g)].at(-1)?.[0];
  if(!marker) {res.writeHead(400);res.end('Synthetic test marker required');return;}
  const plan=marker.startsWith('OOB_CODEX_PLAN_') && !JSON.stringify(users.at(-1)).includes('Implement the approved plan.');
  const reply=plan ? `<proposed_plan>\n# ${marker}_DONE\n1. Inspect the code.\n2. Run tests.\n</proposed_plan>` : `${marker}_DONE`;
  console.log(JSON.stringify({marker,plan,model:body.model,path:req.url, priorTestUserCount:users.filter(u=>JSON.stringify(u).includes('OOB_CODEX_')).length}));
  if(marker.startsWith('OOB_CODEX_HOLD_')) {
    await new Promise(resolve=>{
      const timer=setTimeout(resolve,30000);
      res.once('close',()=>{clearTimeout(timer); console.log(JSON.stringify({marker,cancelled:!res.writableEnded})); resolve();});
    });
    if(res.destroyed) return;
  }
  respondHarnessSuccess(req,res,body,{reply});
}).listen(18769,'127.0.0.1',()=>console.log('Local Codex plan fixture ready'));
