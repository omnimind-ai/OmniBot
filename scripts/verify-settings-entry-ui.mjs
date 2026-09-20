// Read-only navigation smoke test; start on Settings in an English emulator.
// This checks entry/return, not every operation inside each page.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {mkdirSync, writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {uiXmlField as field} from './agent-ui-xml.mjs';
const [serial, output] = process.argv.slice(2);
assert(/^emulator-\d+$/.test(serial || '') && output);
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args], {encoding:'utf8', timeout:30000});
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const snapshot = () => {
  assert.match(adb('shell','uiautomator','dump','/data/local/tmp/oob-entry-ui.xml'), /dumped to:/);
  return [...adb('shell','cat','/data/local/tmp/oob-entry-ui.xml').matchAll(/<node\b[^>]*>/g)]
    .map(([n]) => n).filter(n => field(n,'package') === 'cn.com.omnimind.bot');
};
const label = n => (field(n,'content-desc') || field(n,'text')).split('\n')[0];
const bounds = n => [...field(n,'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
const isSettings = ns => ns.some(n => label(n) === 'Settings' && field(n,'clickable') === 'false');
const entries = ['Account','Model Providers','Scene Model Config','Workspace Memory','Agent Mode',
  'Terminal Environment','MCP Tools','Appearance','Misc','App Permission Authorization','Storage Usage','About Omnibot'];
const report = {serial, scope:'read-only Settings entry and return; not full page acceptance', passed:false, entries:[]};
mkdirSync(output,{recursive:true});
try {
  assert(isSettings(snapshot()), 'Start on the Settings page');
  for (const entry of entries) {
    const started = Date.now();
    let target;
    for(let attempt=0;attempt<5;attempt++) {
      const nodes = snapshot();
      const matches = nodes.filter(n => label(n)===entry && field(n,'clickable')==='true' && field(n,'enabled')==='true');
      assert(matches.length<=1, `Ambiguous entry: ${entry}`);
      if(matches.length) {target=matches[0];break;}
      const scroll = nodes.find(n => field(n,'scrollable')==='true');
      assert(scroll, `No scroll container for ${entry}`);
      const [x1,y1,x2,y2] = bounds(scroll);
      const x = String(Math.round((x1+x2)/2));
      adb('shell','input','swipe',x,String(Math.round(y1+(y2-y1)*0.8)),x,String(Math.round(y1+(y2-y1)*0.25)),'350');
      await pause(400);
    }
    assert(target, `Entry not found: ${entry}`);
    const [x1,y1,x2,y2] = bounds(target);
    adb('shell','input','tap',String(Math.round((x1+x2)/2)),String(Math.round((y1+y2)/2)));
    await pause(1200);
    const page = snapshot();
    assert(page.length && !isSettings(page), `Entry did not open: ${entry}`);
    adb('shell','input','keyevent','4');
    await pause(700);
    assert(isSettings(snapshot()), `Back did not return from ${entry}`);
    report.entries.push({entry,passed:true,elapsedMs:Date.now()-started});
    console.log(JSON.stringify(report.entries.at(-1)));
  }
  report.passed = true;
} catch(error) { report.error=error.message;throw error; }
finally { writeFileSync(resolve(output,'result.json'),JSON.stringify(report,null,2)+'\n'); }
