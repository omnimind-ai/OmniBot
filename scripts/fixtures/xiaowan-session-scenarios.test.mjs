import test from 'node:test';
import assert from 'node:assert/strict';
import {respondXiaowanSession} from './xiaowan-session-scenarios.mjs';
const tools=['skills_list','skills_read','context_time_now'].map(name=>({function:{name}}));
function call(kind,results=[],extraTools=[]) {
  let output='';
  const response={writeHead(){},end(s){output=s;}};
  const body={tools:[...tools,...extraTools],messages:[{role:'user',content:`Reply OOB_SESSION_${kind}_123`},...results.map(r=>({role:'tool',content:JSON.stringify(r)}))]};
  respondXiaowanSession(response,body,()=>{});
  return JSON.parse(output.split('\n')[0].slice(6)).choices[0].delta;
}
const result=(success,raw={})=>({success,rawResultJson:JSON.stringify(raw)});
test('legacy discovery is a deliberate negative call followed by advertised skills',()=>{
  assert.equal(call('DISCOVERY').tool_calls[0].function.name,'tools_search');
  assert.equal(call('DISCOVERY',[result(false)]).tool_calls[0].function.name,'skills_list');
});
test('unexpected discovery advertisement is rejected',()=>{
  assert.throws(()=>call('DISCOVERY',[],[{function:{name:'tools_search'}}]));
});
test('an actual installed skill is selected and empty skill bodies fail',()=>{
  const initial=[result(false),result(true,{items:[{id:'example'}]})];
  assert.equal(JSON.parse(call('DISCOVERY',initial).tool_calls[0].function.arguments).skillId,'example');
  assert.throws(()=>call('DISCOVERY',[...initial,result(true,{id:'example',bodyMarkdown:''})]));
});
test('terminal environment failure cannot count as successful exit 7 coverage',()=>{
  const prefix=[result(true,{sessionId:'native'}),result(true),result(true,{stdout:'OOB_SESSION_STATE_OK'}),result(true,{content:'OOB_SESSION_STATE_OK'})];
  assert.throws(()=>call('LIFECYCLE',[...prefix,result(false,{exitCode:127})]));
  assert.equal(call('LIFECYCLE',[...prefix,result(false,{exitCode:7})]).tool_calls[0].function.name,'terminal_session_exec');
});
