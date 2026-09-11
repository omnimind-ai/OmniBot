// Isolated MCP device regression fixture. No model credentials or user files.
import http from 'node:http';
import fs from 'node:fs';
const port = Number(process.argv[2] || 29091);
const logPath = process.argv[3];
const events = [];
const pending = new Map();
const streams = new Map();
let streamSequence=0;
const log = event => {
  const row = {at:Date.now(), ...event}; events.push(row);
  if (logPath) fs.appendFileSync(logPath, JSON.stringify(row)+'\n');
};
const tools = [
 {name:'lifecycle_wait',description:'Wait until the user stops this tool. For lifecycle regression only.',inputSchema:{type:'object',properties:{},additionalProperties:false}},
 {name:'lifecycle_echo',description:'Return MCP_LIFECYCLE_OK immediately. For lifecycle regression only.',inputSchema:{type:'object',properties:{},additionalProperties:false}},
 {name:'lifecycle_denied',description:'Return HTTP 401 to test permission error handling. For lifecycle regression only.',inputSchema:{type:'object',properties:{},additionalProperties:false}}
];
const server=http.createServer(async (req,res)=>{
 if(req.method==='GET' && req.url==='/observations') {res.setHeader('Content-Type','application/json');res.end(JSON.stringify({events,pending:[...pending.keys()]}));return;}
 if(req.method==='DELETE'){res.writeHead(200);res.end();return;}
 const url=new URL(req.url,'http://127.0.0.1');
 if(req.method==='GET' && url.pathname==='/sse'){
  const id=String(++streamSequence);streams.set(id,res);
  res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
  res.write(`event: endpoint\ndata: /messages?connection=${id}\n\n`);
  log({streamOpened:id});return;
 }
 if(req.method!=='POST'||!['/mcp','/messages'].includes(url.pathname)){res.writeHead(404);res.end();return;}
 const streamId=url.pathname==='/messages'?url.searchParams.get('connection'):null;
 const stream=streamId?streams.get(streamId):null;
 if(streamId && !stream){res.writeHead(404);res.end();return;}
 let body='';for await(const chunk of req){body+=chunk;if(body.length>65536){res.writeHead(413);res.end();return;}}
 let msg;try{msg=JSON.parse(body);}catch{res.writeHead(400);res.end();return;}
 log({method:msg.method,id:msg.id??null,tool:msg.params?.name??null,streamId});
 if(msg.method==='tools/call' && msg.params?.name==='lifecycle_denied'){
  res.writeHead(401);res.end('Fixture permission denied');
  if(stream){stream.end();streams.delete(streamId);}return;
 }
 if(stream){res.writeHead(202);res.end();}
 const reply=(result,error)=>{
  const payload=JSON.stringify({jsonrpc:'2.0',id:msg.id,...(error?{error}:{result})});
  if(stream){
   if(!stream.destroyed)stream.write('data: '+payload+'\n\n');
   if(msg.method!=='initialize'){stream.end();streams.delete(streamId);}
  }else if(!res.destroyed){res.setHeader('Content-Type','application/json');res.end(payload);}
 };
 if(msg.method==='server/discover'){reply(null,{code:-32601,message:'Legacy fixture'});return;}
 if(msg.method==='initialize'){if(!stream)res.setHeader('Mcp-Session-Id','oob-lifecycle-fixture');reply({protocolVersion:'2025-11-25',capabilities:{tools:{}},serverInfo:{name:'OOB Lifecycle Fixture',version:'1'}});return;}
 if(msg.method==='notifications/cancelled'){
  const id=msg.params?.requestId;const active=pending.get(id);pending.delete(id);
  if(active){clearTimeout(active.timer);if(!active.response.destroyed)active.response.end();}
  log({cancelled:id,found:Boolean(active),streamId});if(streamId)streams.delete(streamId);if(!stream){res.writeHead(202);res.end();}return;
 }
 if(msg.method?.startsWith('notifications/')){if(!stream){res.writeHead(202);res.end();}return;}
 if(msg.method==='tools/list'){reply({tools});return;}
 if(msg.method==='tools/call'){
  if(msg.params?.name==='lifecycle_wait'){
   const timer=setTimeout(()=>{pending.delete(msg.id);reply({isError:true,content:[{type:'text',text:'Fixture deadline reached'}]});},120000);
   pending.set(msg.id,{timer,response:stream||res});return;
  }
  if(msg.params?.name==='lifecycle_echo'){reply({content:[{type:'text',text:'MCP_LIFECYCLE_OK'}]});return;}
 }
 reply(null,{code:-32601,message:'Unsupported fixture method'});
});
server.listen(port,'127.0.0.1',()=>process.stdout.write(JSON.stringify({ready:true,port:server.address().port})+'\n'));
process.on('SIGTERM',()=>{for(const p of pending.values()){clearTimeout(p.timer);p.response.destroy();}for(const stream of streams.values())stream.destroy();server.close(()=>process.exit(0));});
