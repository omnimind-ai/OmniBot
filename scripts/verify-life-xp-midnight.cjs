#!/usr/bin/env node
// Production check-in method with an advancing clock and synthetic connector.
// This is not Android/WebView lifecycle acceptance.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=process.argv[2];assert(source,'Usage: node verify-life-xp-midnight.cjs app.js');
const RealDate=Date;
let now=new RealDate(2026,8,19,23,59,59).getTime();
class ClockDate extends RealDate {
  constructor(...args){super(...(args.length?args:[now]));}
  static now(){return now;}
}
const calls=[],notices=[],elements=new Map();
const document={addEventListener(){},querySelectorAll(){return [];},querySelector(){return null;},getElementById(id){
  if(!elements.has(id))elements.set(id,{style:{},textContent:''});return elements.get(id);
}};
const context=vm.createContext({document,window:{omni:{tools:{call:async(name,args={})=>{
  calls.push({name,args});
  if(name==='get_habits')return {rows:[{id:1,name:'Water',xp_value:10}],count:1};
  if(name==='get_check_ins')return {rows:[],count:0};
  if(name==='check_in_habit')return {rowId:2};
  throw Error('Unexpected production tool '+name);
}}}},console,Date:ClockDate,Set,Map,setTimeout,clearTimeout});
vm.runInContext(fs.readFileSync(source,'utf8')+'\nglobalThis.ProductionClass=LifeXPApp;',context,{timeout:2000});
context.ProductionClass.prototype.init=async()=>{};
const app=new context.ProductionClass();
app.todayCheckIns.add(1);app.totalXp=10;app.level=1;
app.showNotification=x=>notices.push(x);app.showLevelUpAnimation=()=>{};
app.loadHistory=async()=>{};
(async()=>{
  assert.equal(app.currentDate,'2026-09-19');
  now=new RealDate(2026,8,20,0,0,1).getTime();
  await app.checkInHabit(1);
  const writes=calls.filter(c=>c.name==='check_in_habit');
  const failures=[];
  try {assert.equal(writes.length,1,'yesterday check-in must not block today');
    assert.equal(writes[0].args.day,'2026-09-20','write must use the new local day');
  }catch(e){failures.push(e.message);}
  console.log(JSON.stringify({scope:'actual production check-in; synthetic clock/connector',passed:!failures.length,failures,calls,notices},null,2));
  process.exitCode=failures.length?1:0;
})().catch(e=>{console.error(e);process.exitCode=1;});
