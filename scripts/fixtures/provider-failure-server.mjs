// Local-only deterministic provider for maintained failure journeys.
import http from 'node:http';
import {respondXiaowanFailure} from './xiaowan-failure-scenarios.mjs';
const server=http.createServer(async(req,res)=>{
  if(req.method==='GET' && req.url==='/v1/models') {
    res.setHeader('content-type','application/json');
    res.end(JSON.stringify({data:[{id:'gpt-4o',object:'model',context_length:128000}]}));
    return;
  }
  if(req.method!=='POST' || req.url!=='/v1/chat/completions') return res.writeHead(404).end();
  try {
    const chunks=[]; for await(const chunk of req) chunks.push(chunk);
    const body=JSON.parse(Buffer.concat(chunks));
    if(!respondXiaowanFailure(req,res,body)) res.writeHead(400).end('Synthetic marker required');
  } catch { if(!res.headersSent) res.writeHead(400); res.end('Invalid synthetic request'); }
});
server.listen(Number(process.env.OOB_FAILURE_PORT || 18879),'127.0.0.1',()=>console.log('Local failure fixture ready'));
process.on('SIGTERM',()=>{server.closeAllConnections();server.close();});
