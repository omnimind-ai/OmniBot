import {test} from 'node:test';
import assert from 'node:assert/strict';
import {assertMcpFixturePhase as verify} from './mcp-fixture-observation.mjs';
const wait={method:'tools/call',tool:'lifecycle_wait',id:'current'};
test('requires current pending request and ignores older events',()=>{
 assert(verify({events:[wait,wait],pending:['current']},1,'pending').passed);
 assert.throws(()=>verify({events:[wait],pending:['old']},0,'pending'));
});
test('cancellation must target active original operation exactly once',()=>{
 const good={cancelled:'current',found:true};
 assert(verify({events:[wait,good],pending:[]},0,'cancelled').passed);
 for(const rows of [[{cancelled:'old',found:true}],[{cancelled:'current',found:false}],[good,good]])
  assert.throws(()=>verify({events:[wait,...rows],pending:[]},0,'cancelled'));
 assert.throws(()=>verify({events:[wait,good],pending:['current']},0,'cancelled'));
 assert.throws(()=>verify({events:[{...wait,streamId:'one'},{...good,streamId:'two'}],pending:[]},0,'cancelled'));
 assert(verify({events:[{...wait,streamId:'one'},{...good,streamId:'one'}],pending:[]},0,'cancelled').passed);
});
test('next echo must actually execute once',()=>{
 const events=[wait,{cancelled:'current',found:true},{method:'tools/call',tool:'lifecycle_echo',id:'echo'}];
 assert(verify({events,pending:[]},0,'echo').passed);
 assert.throws(()=>verify({events:[...events,events[2]],pending:[]},0,'echo'));
});
test('permission rejection cannot pass with replay or absent recovery',()=>{
 const denial={method:'tools/call',tool:'lifecycle_denied',id:'denied'};
 assert(verify({events:[denial],pending:[]},0,'denied').passed);
 assert.throws(()=>verify({events:[denial,denial],pending:[]},0,'denied'));
 assert.throws(()=>verify({events:[denial],pending:[]},0,'denied-recovery'));
 assert(verify({events:[denial,{method:'tools/call',tool:'lifecycle_echo',id:'next'}],pending:[]},0,'denied-recovery').passed);
});
