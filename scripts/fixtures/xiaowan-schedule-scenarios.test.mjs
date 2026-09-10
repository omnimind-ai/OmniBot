import test from 'node:test';
import assert from 'node:assert/strict';
import {respondXiaowanSchedule} from './xiaowan-schedule-scenarios.mjs';
function call(phase,results=[]) {
 let raw;
 respondXiaowanSchedule({writeHead(){},end(v){raw=v;}},{messages:[{role:'user',content:`Reply OOB_SCHEDULE_${phase}_123`},...results.map(r=>({role:'tool',content:JSON.stringify(r)}))]},()=>{});
 return JSON.parse(raw.split('\n')[0].slice(6)).choices[0].delta;
}
const result=(success,data)=>({success,previewJson:JSON.stringify(data)});
test('schedule acceptance creates only a disabled task without model-owned ID',()=>{
 const args=JSON.parse(call('CREATE').tool_calls[0].function.arguments);
 assert.equal(args.enabled,false);assert.equal(args.notificationEnabled,false);assert(!('taskId' in args));
});
test('schedule fixture rejects lost restart state or enabled tasks',()=>{
 assert.throws(()=>call('CLEAN',[result(true,[])]));
 assert.throws(()=>call('CLEAN',[result(true,[{title:'OOB_SCHEDULE_123',taskId:'native',enabled:true}])]));
});
test('cleanup uses the actual returned task identity',()=>{
 const args=JSON.parse(call('CLEAN',[result(true,[{title:'OOB_SCHEDULE_123',taskId:'native',enabled:false}])]).tool_calls[0].function.arguments);
 assert.equal(args.taskId,'native');assert.equal(args.enabled,false);
});
