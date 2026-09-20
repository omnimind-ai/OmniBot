#!/usr/bin/env node
// Logic audit only: runs the supplied production app.js, with synthetic connector
// data and a minimal DOM. Does NOT claim native persistence or rendered UI coverage.
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const [sourcePath, toolkitPath] = process.argv.slice(2);
assert(sourcePath && toolkitPath, 'Usage: node verify-life-xp-production.cjs app.js toolkit.json');
const source = fs.readFileSync(sourcePath, 'utf8');
const toolkit = JSON.parse(fs.readFileSync(toolkitPath, 'utf8'));
const rows = Array.from({length:601}, (_,i)=>({id:i+1,habit_id:1,day:'2026-09-17',created_at:`2026-09-17 00:00:00`}));
const calls = [];
const errors = [];
const elements = new Map();
const document = {addEventListener(){},getElementById(id){
  if(!elements.has(id)) elements.set(id,{style:{},textContent:'',innerHTML:''});
  return elements.get(id);
}};
const bridge = async (name,args={}) => {
  calls.push({name,args});
  const tool=toolkit.tools.find(t=>t.name===name);
  assert(tool,`Undeclared tool ${name}`);
  const schema=tool.parameters;
  for(const key of Object.keys(args)) if(schema.additionalProperties===false) assert(key in schema.properties,`Undeclared argument ${name}.${key}`);
  for(const key of schema.required||[]) assert(key in args,`Missing ${key}`);
  if(name==='get_habits')return {rows:[{id:1,name:'synthetic',xp_value:1}],count:1};
  assert.equal(name,'get_check_ins');
  const limit=args._limit??100,offset=args._offset??0;
  assert(Number.isInteger(limit)&&limit>=1&&limit<=500,'Invalid limit');
  assert(Number.isInteger(offset)&&offset>=0,'Invalid offset');
  assert(offset===0||args._order_by,'Pagination needs stable order');
  const page=rows.slice(offset,offset+limit);
  return {rows:page,count:page.length};
};
const context=vm.createContext({document,window:{omni:{tools:{call:bridge}}},console,Date,Set,Map,setTimeout,clearTimeout});
vm.runInContext(source+'\nglobalThis.ProductionClass = LifeXPApp;',context,{timeout:2000});
const app=Object.create(context.ProductionClass.prototype);
app.currentDate='2026-09-17';app.todayCheckIns=new Set();app.totalXp=0;app.level=1;
app.showNotification=message=>errors.push(message);
let history;
app.renderHistory=records=>{history=records;};
(async()=>{
 const failures=[];
 await app.loadStats();
 try{assert.equal(app.totalXp,601);assert.equal(app.level,13);}catch(e){failures.push('601-row stats: '+e.message);}
 await app.loadHistory();
 try{assert.equal(history?.length,601);}catch(e){failures.push('601-row history: '+e.message);}
 if(errors.length)failures.push(...errors);
 console.log(JSON.stringify({scope:'actual production methods with synthetic connector and DOM; not device UI',sourcePath,passed:!failures.length,failures,calls},null,2));
 process.exitCode=failures.length?1:0;
})().catch(e=>{console.error(e);process.exitCode=1;});
