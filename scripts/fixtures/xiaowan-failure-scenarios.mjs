// Deterministic failures through the real Android HTTP -> Agent -> ACP path.
// Only synthetic markers are accepted. Never contacts or logs real credentials.
import assert from 'node:assert/strict';
export function respondXiaowanFailure(request, response, body, log = console.log) {
  const text = m => typeof m?.content === 'string' ? m.content : (m?.content || []).filter(p => p.type === 'text').map(p => p.text).join('\n');
  const messages = body.messages || [];
  const lastUser = messages.findLastIndex(m => m.role === 'user');
  const marker = text(messages[lastUser]).match(/OOB_FAILURE_([A-Z]+)(?:_\d+)+/);
  if (!marker) return false;
  const kind = marker[1], id = marker[0];
  log(JSON.stringify({marker:id, failureScenario:kind, phase:'request'}));
  const event = (delta, finish = null) => `data: ${JSON.stringify({choices:[{index:0,delta,finish_reason:finish}]})}\n\n`;
  const success = content => {response.writeHead(200,{'content-type':'text/event-stream'}); response.end(event({content},'stop')+'data: [DONE]\n\n');};
  const status = {AUTH:401, QUOTA:429, RATE:429, SERVER:503}[kind];
  if (kind === 'WAIT') {
    response.writeHead(200, {'content-type':'text/event-stream'});
    response.write(event({content:`${id}_WAITING`}));
    const timer = setTimeout(() => response.destroy(), 180000);
    timer.unref();
    response.once('close', () => {
      clearTimeout(timer);
      log(JSON.stringify({marker:id, failureScenario:kind, phase:'response-closed', completed:response.writableEnded}));
    });
  } else if (['STREAMERROR','STREAMTOOL','STREAMRATE','STREAMLIMIT','STREAMAUTH','STREAMSERVICE','STREAMREJECT','STREAMMODEL'].includes(kind)) {
    response.writeHead(200, {'content-type':'text/event-stream'});
    response.write(event(kind !== 'STREAMTOOL'
      ? {content:`${id}_PARTIAL`}
      : {tool_calls:[{index:0,id:`call_${id}`,type:'function',function:{name:'file_write',arguments:JSON.stringify({path:`/workspace/${id}-must-not-exist.txt`,content:'must not execute'})}}]}));
    const [errorStatus,errorCode]= {
      STREAMAUTH:[401,'unknown'], STREAMSERVICE:[503,'server_error'],
      STREAMREJECT:[403,'permission_denied'], STREAMMODEL:[404,'model_not_found'],
      STREAMRATE:[429,'rate_limit_exceeded'], STREAMLIMIT:[429,'request_limited'],
    }[kind] || [429,'insufficient_quota'];
    response.write(`data: ${JSON.stringify({error:{code:errorCode,message:`${id} synthetic failure`},status_code:errorStatus})}\n\n`);

    // Deliberately no DONE/EOF: the explicit error must settle the request.
    const timer=setTimeout(()=>response.destroy(),180000); timer.unref();
    response.once('close',()=>{clearTimeout(timer);log(JSON.stringify({marker:id,failureScenario:kind,phase:'response-closed',completed:response.writableEnded}));});
  } else if (kind === 'OK') success(`${id}_DONE`);
  else if (status) {
    response.writeHead(status,{'content-type':'application/json'});
    response.end(JSON.stringify({error:{code:kind==='QUOTA'?'insufficient_quota':kind==='RATE'?'rate_limit_exceeded':kind.toLowerCase(),message:`${id} synthetic ${kind.toLowerCase()} failure`}}));
  } else if (kind === 'BEFORE') response.destroy();
  else if (kind === 'PARTIAL') {
    response.writeHead(200,{'content-type':'text/event-stream'});
    response.write(event({content:`${id}_PARTIAL`}));
    setTimeout(()=>response.destroy(),1000);
  } else if (kind === 'EMPTY') {
    response.writeHead(200,{'content-type':'text/event-stream'});
    response.end(event({},'stop')+'data: [DONE]\n\n');
  } else if (['UNKNOWN','ARGS','MISSING','EXIT','TIMEOUT'].includes(kind)) {
    const results = messages.slice(lastUser+1).filter(m=>m.role==='tool');
    if (results.length) {
      assert.equal(results.length,1,'A failed tool was replayed');
      const result=JSON.parse(text(results[0]));
      assert.equal(result.success,false,'Expected failed tool result, not silent success');
      if (kind==='EXIT') assert.equal(JSON.parse(result.rawResultJson).resultCode,7,'Environment failure is not the expected command exit');
      if (kind==='TIMEOUT') assert.equal(result.timedOut,true,'Permission or environment failure is not a command timeout');
      log(JSON.stringify({marker:id,failureScenario:kind,phase:'tool-failure-observed',success:result.success}));
      success(`${id}_RECOVERED`);
    } else {
      response.writeHead(200,{'content-type':'text/event-stream'});
      const name=kind==='UNKNOWN'?'oob_nonexistent_tool':['EXIT','TIMEOUT'].includes(kind)?'terminal_execute':'file_read';
      const args=kind==='ARGS'?'{bad json':JSON.stringify(kind==='EXIT'
        ? {command:`printf ${id}; exit 7`,timeoutSeconds:10}
        : kind==='TIMEOUT' ? {command:'sleep 4',timeoutSeconds:1}
        : {path:`/workspace/${id}-missing.txt`});
      response.end(event({tool_calls:[{index:0,id:`call_${id}`,type:'function',function:{name,arguments:args}}]},'tool_calls')+'data: [DONE]\n\n');
    }
  } else throw Error(`Unsupported synthetic scenario ${kind}`);
  return true;
}
