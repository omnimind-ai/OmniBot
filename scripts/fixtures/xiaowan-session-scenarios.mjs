// Drive real Android tools using unique, synthetic test tasks. No native calls.
import assert from 'node:assert/strict';
export function respondXiaowanSession(response, body, log = console.log) {
  const text = m => typeof m?.content === 'string' ? m.content : (m?.content || []).filter(p=>p.type==='text').map(p=>p.text).join('\n');
  const index = body.messages.findLastIndex(m=>m.role==='user');
  const marker = text(body.messages[index]).match(/OOB_SESSION_(LIFECYCLE|DISCOVERY)_\d+/)?.[0];
  if (!marker) return false;
  const results = body.messages.slice(index+1).filter(m=>m.role==='tool').map(m=>JSON.parse(text(m)));
  const data = r => typeof r?.rawResultJson === 'string' ? JSON.parse(r.rawResultJson) : r;
  let name, args;
  if (marker.includes('_LIFECYCLE_')) {
    const session = data(results[0])?.sessionId;
    if(results.length) assert(session,'Native terminal session ID missing');
    const calls = [
      ['terminal_session_start',{sessionName:marker,workingDirectory:'/workspace'}],
      ['terminal_session_exec',{sessionId:session,command:'cd /tmp; export OOB_SESSION_VALUE=cerulean'}],
      ['terminal_session_exec',{sessionId:session,command:'test "$PWD" = /tmp && test "$OOB_SESSION_VALUE" = cerulean && printf OOB_SESSION_STATE_OK'}],
      ['terminal_session_read',{sessionId:session}],
      ['terminal_session_exec',{sessionId:session,command:'sh -c "exit 7"'}],
      ['terminal_session_exec',{sessionId:session,command:'printf OOB_SESSION_RECOVERY_OK'}],
      ['terminal_session_stop',{sessionId:session}],
      ['terminal_session_read',{sessionId:session}],
      ['terminal_session_stop',{sessionId:session}],
    ];
    results.forEach((r,i)=>assert.equal(r.success,![4,7,8].includes(i),`Session step ${i} success mismatch`));
    if(results.length>2) assert(JSON.stringify(data(results[2])).includes('OOB_SESSION_STATE_OK'),'cwd/environment were not preserved');
    if(results.length>3) assert(JSON.stringify(data(results[3])).includes('OOB_SESSION_STATE_OK'),'Read lost latest output');
    if(results.length>4) assert.equal(data(results[4]).exitCode,7,'Expected actual command exit 7');
    if(results.length>5) assert(JSON.stringify(data(results[5])).includes('OOB_SESSION_RECOVERY_OK'),'Session did not recover after exit 7');
    [name,args]=calls[results.length] || [];
  } else {
    assert(!body.tools.some(t=>t.function?.name==='tools_search'),'Removed discovery tool must not be advertised');
    for(const required of ['skills_list','skills_read','context_time_now']) assert(body.tools.some(t=>t.function?.name===required),`Missing advertised ${required}`);
    results.forEach((r,i)=>assert.equal(r.success,![0,3].includes(i),`Discovery step ${i} success mismatch`));
    const listed=data(results[1]);
    if(results.length>1) assert(listed.items?.length>0,'No installed skill available for read acceptance');
    if(results.length>2) {assert.equal(data(results[2]).id,listed.items[0].id);assert(data(results[2]).bodyMarkdown?.length>0,'Skill body missing');}
    const calls=[['tools_search',{query:'terminal_session',limit:20}],['skills_list',{limit:1}],['skills_read',{skillId:listed?.items?.[0]?.id}],['skills_read',{skillId:marker+'-missing'}],['context_time_now',{}]];
    [name,args]=calls[results.length] || [];
  }
  log(JSON.stringify({marker,completed:results.length,nextTool:name || null,success:true}));
  const event=(delta,finish)=>`data: ${JSON.stringify({choices:[{index:0,delta,finish_reason:finish}]})}\n\n`;
  response.writeHead(200,{'content-type':'text/event-stream'});
  response.end(name ? event({tool_calls:[{index:0,id:`call_${marker}_${results.length}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]},'tool_calls')+'data: [DONE]\n\n' : event({content:marker+'_DONE'},'stop')+'data: [DONE]\n\n');
  return true;
}
