#!/usr/bin/env node
// Execute production history rendering. Synthetic DOM/data, not device acceptance.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const [sourcePath, mode] = process.argv.slice(2);
assert(sourcePath, 'Usage: node verify-life-xp-history.cjs app.js');
if (mode !== '--child') {
  const results = ['UTC', 'Asia/Shanghai', 'America/Los_Angeles'].map(TZ => {
    const result = spawnSync(process.execPath, [__filename, sourcePath, '--child'],
      {env: {...process.env, TZ}, encoding:'utf8', timeout:10000});
    return {timezone:TZ, passed:result.status === 0, output:result.stdout, error:result.stderr};
  });
  console.log(JSON.stringify({scope:'production renderHistory with synthetic DOM/data',results},null,2));
  process.exit(results.every(r=>r.passed)?0:1);
}
class Element {
  constructor(){this.children=[];this.style={};this.className='';this.html='';}
  set innerHTML(value){this.html=value;this.children=[];}
  get innerHTML(){return this.html;}
  appendChild(child){this.children.push(child);return child;}
  get textContent(){return this.html.replace(/<[^>]*>/g,'')+this.children.map(c=>c.textContent).join('\n');}
  set textContent(value){this.html=String(value);this.children=[];}
}
const elements=new Map();
const document={addEventListener(){},createElement(){return new Element();},getElementById(id){
  if(!elements.has(id))elements.set(id,new Element());return elements.get(id);
}};
let habits=[];
const errors=[];
const context=vm.createContext({document,window:{omni:{tools:{call:async name=>{
  assert.equal(name,'get_habits');return {rows:habits,count:habits.length};
}}}},console,Date,Set,Map,setTimeout,clearTimeout});
vm.runInContext(fs.readFileSync(sourcePath,'utf8')+'\nglobalThis.ProductionClass=LifeXPApp;',context,{timeout:2000});
const app=Object.create(context.ProductionClass.prototype);
app.showNotification=text=>errors.push(text);
const walk=e=>[e,...e.children.flatMap(walk)];
async function render(rows){
  elements.clear();errors.length=0;
  await app.renderHistory(rows);
  await new Promise(resolve=>setImmediate(resolve));
  assert.deepEqual(errors,[]);
  return document.getElementById('historyList');
}
(async()=>{
  const failures=[];
  habits=[{id:1,name:'Water',xp_value:10},{id:2,name:'Exercise',xp_value:30},
    {id:3,name:'Reading',xp_value:20},{id:4,name:'Walk',xp_value:15}];
  const rows=[4,3,2,1].map(id=>({id,habit_id:id,day:'2026-09-17',created_at:`2026-09-17 10:00:0${id}`}));
  const history=await render(rows);
  for(const [name,xp] of [['Water',10],['Exercise',40],['Reading',60],['Walk',75]]){
    try{const item=walk(history).find(e=>e.className==='history-item'&&e.textContent.includes(name));
      assert(item,`missing ${name}`);assert.match(item.textContent,new RegExp(`累计[:：]\\s*${xp}\\s*XP`));
    }catch(e){failures.push(`${name} cumulative: ${e.message}`);}
  }
  try{assert.match(history.textContent,/2026-09-17/);assert.doesNotMatch(history.textContent,/2026-09-16/);}
  catch(e){failures.push('calendar day must not shift with timezone: '+e.message);}
  habits=[{id:1,name:'Large reward',xp_value:150}];
  const multi=await render([{id:1,habit_id:1,day:'2026-09-18',created_at:'2026-09-18 12:00:00'}]);
  for(const level of [2,3,4])try{assert.match(multi.textContent,new RegExp(`等级\\s*${level}(?!\\d)`));}
    catch(e){failures.push(`crossed level ${level} missing: ${e.message}`);}
  habits=[{id:1,name:'Yesterday',xp_value:60},{id:2,name:'Today',xp_value:10}];
  const acrossDays=await render([
    {id:2,habit_id:2,day:'2026-09-18',created_at:'2026-09-18 10:00:00'},
    {id:1,habit_id:1,day:'2026-09-17',created_at:'2026-09-17 10:00:00'},
  ]);
  try {
    const today=walk(acrossDays).find(e=>e.className==='history-item'&&e.textContent.includes('Today'));
    assert(today,'missing later day');
    assert.match(today.textContent,/累计[:：]\s*70\s*XP/);
  } catch(e){failures.push('lifetime cumulative must continue across calendar days: '+e.message);}
  console.log(JSON.stringify({passed:failures.length===0,failures}));
  process.exitCode=failures.length?1:0;
})().catch(e=>{console.error(e);process.exitCode=1;});
