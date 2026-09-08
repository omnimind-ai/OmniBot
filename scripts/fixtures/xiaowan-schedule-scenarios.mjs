import assert from 'node:assert/strict';
export function respondXiaowanSchedule(response,body,log=console.log) {
 const text=m=>typeof m?.content==='string'?m.content:(m?.content||[]).filter(p=>p.type==='text').map(p=>p.text).join('\n');
 const index=body.messages.findLastIndex(m=>m.role==='user');
 const match=text(body.messages[index]).match(/OOB_SCHEDULE_(REPRO|CREATE|CLEAN)_([0-9]+)/);
 if(!match)return false;
 const [marker,phase,run]=match; const title='OOB_SCHEDULE_'+run;
 const results=body.messages.slice(index+1).filter(m=>m.role==='tool').map(m=>JSON.parse(text(m)));
 const data=r=>r ? JSON.parse(r.rawResultJson||r.previewJson||'{}'):undefined;
 const create={title,targetKind:'subagent',scheduleType:'countdown',countdownMinutes:60,repeatDaily:false,enabled:false,notificationEnabled:false,subagentPrompt:'Return the synthetic word cerulean.'};
 let calls;
 if(phase==='REPRO') {
  calls=[['schedule_task_create',create],['schedule_task_delete',{taskId:title+'-missing'}]];
  if(results.length>0)assert.equal(results[0].success,false,'Old create bug did not reproduce');
  if(results.length>1){assert.equal(results[1].success,true);assert.equal(data(results[1]).deleted,false);}
 } else if(phase==='CREATE') {
  calls=[['schedule_task_create',create]];
  if(results.length){assert.equal(results[0].success,true);assert(data(results[0]).taskId);assert.equal(data(results[0]).enabled,false);}
 } else {
  const tasks=data(results[0]); const task=tasks?.find(t=>t.title===title);
  if(results.length)assert(task?.taskId && task.enabled===false,'Disabled test task not preserved');
  calls=[['schedule_task_list',{}],['schedule_task_update',{taskId:task?.taskId,title:title+' updated',enabled:false}],['schedule_task_delete',{taskId:task?.taskId}],['schedule_task_delete',{taskId:task?.taskId}],['schedule_task_update',{taskId:task?.taskId,title:'must not recreate',enabled:false}],['schedule_task_list',{}]];
  results.forEach((r,i)=>assert.equal(r.success,![3,4].includes(i),`Schedule step ${i} result mismatch`));
  if(results.length>5)assert(!data(results[5]).some(t=>t.taskId===task.taskId),'Deleted task was recreated');
 }
 const [name,args]=calls[results.length]||[];
 log(JSON.stringify({marker,completed:results.length,nextTool:name||null,verified:true}));
 const event=(delta,finish)=>`data: ${JSON.stringify({choices:[{index:0,delta,finish_reason:finish}]})}\n\n`;
 response.writeHead(200,{'content-type':'text/event-stream'});
 response.end(name?event({tool_calls:[{index:0,id:`call_${marker}_${results.length}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]},'tool_calls')+'data: [DONE]\n\n':event({content:marker+'_DONE'},'stop')+'data: [DONE]\n\n');
 return true;
}
