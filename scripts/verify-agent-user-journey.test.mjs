import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, writeFileSync, readFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';

for (const deviceTime of ['1697425200', 'not-a-timestamp']) {
  test(`real-provider clock preflight rejects ${deviceTime} before any UI action`, () => {
    const directory = mkdtempSync(join(tmpdir(), 'oob-clock-preflight-'));
    try {
      const adb = join(directory, 'adb');
      const calls = join(directory, 'calls.jsonl');
      writeFileSync(adb, `#!/usr/bin/env node
const fs = require('node:fs');
fs.appendFileSync(${JSON.stringify(calls)}, JSON.stringify(process.argv.slice(2)) + "\\n");
if (process.argv.slice(2).join(' ') !== '-s emulator-1234 shell date +%s') process.exit(7);
process.stdout.write(${JSON.stringify(deviceTime)});
`, {mode: 0o700});
      const journey = join(directory, 'journey.json');
      writeFileSync(journey, JSON.stringify({name:'clock preflight unit fixture', requireClockSync:true,
        steps:[{action:'send', marker:'OOB_NEVER_SENT'}]}));
      const evidence = join(directory, 'evidence');
      const result = spawnSync(process.execPath, ['scripts/verify-agent-user-journey.mjs',
        'emulator-1234', journey, evidence], {env:{...process.env, ADB:adb}, encoding:'utf8'});
      assert.equal(result.status, 1);
      const report = JSON.parse(readFileSync(join(evidence,'result.json'), 'utf8'));
      assert.equal(report.passed, false);
      assert.equal(report.steps[0].index, 0);
      assert.match(report.steps[0].detail, /Device clock differs/);
      const observations = readFileSync(calls,'utf8').trim().split('\n').map(line => JSON.parse(line));
      assert.deepEqual(observations, [['-s','emulator-1234','shell','date','+%s']]);
    } finally { rmSync(directory, {recursive:true, force:true}); }
  });
}

const escapeXml = value => String(value).replaceAll('&','&amp;').replaceAll('"','&quot;')
  .replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('\n','&#10;');

for (const [name, target, expectedFailure] of [
  ['explicit unsent owner', {agentId:'kimi-code-acp',mode:'agent',isNewConversation:true}, null],
  ['another draft owner', {agentId:'xiaowan-acp',mode:'agent',isNewConversation:true}, /Wrong draft conversation owner/],
  ['persisted conversation', {agentId:'kimi-code-acp',mode:'agent',isNewConversation:true,conversationId:42}, /created durable history/],
  ['legacy selection without draft ownership', null, /Wrong draft conversation owner/],
]) {
  test(`draft acceptance checks conversation ownership: ${name}`, () => {
    const directory=mkdtempSync(join(tmpdir(),'oob-draft-owner-'));
    try {
      const marker='OOB_LIVE_SWITCH_DRAFT_12345';
      const scenario=JSON.parse(readFileSync('scripts/fixtures/user-scenarios/xiaowan-switch-reply.json','utf8'));
      const expected=`${scenario.prompt.replaceAll('{{MARKER}}',marker)} End your final reply with ${marker}_DONE.`;
      const xml=`<hierarchy><node package="cn.com.omnimind.bot" class="android.widget.EditText" text="${escapeXml(expected)}"/><node package="cn.com.omnimind.bot" text="Kimi Code"/></hierarchy>`;
      const preferences=`<map><string name="flutter.last_visible_conversation_target">${escapeXml(JSON.stringify(target))}</string></map>`;
      const legacyPreferences='<map><string name="selected_profile_id">kimi-code-acp</string></map>';
      const adb=join(directory,'adb');
      const calls=join(directory,'calls.jsonl');
      writeFileSync(adb,`#!/usr/bin/env node
const fs=require('node:fs');
const args=process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(calls)},JSON.stringify(args)+"\\n");
const command=args.slice(2).join(' ');
if(command==='shell uiautomator dump /data/local/tmp/oob-user-journey.xml') process.stdout.write('UI hierarchy dumped to: /data/local/tmp/oob-user-journey.xml');
else if(command==='shell cat /data/local/tmp/oob-user-journey.xml') process.stdout.write(${JSON.stringify(xml)});
else if(command==='exec-out run-as cn.com.omnimind.bot cat shared_prefs/FlutterSharedPreferences.xml') process.stdout.write(${JSON.stringify(preferences)});
else if(command==='exec-out run-as cn.com.omnimind.bot cat shared_prefs/acp_agent_profiles.xml') process.stdout.write(${JSON.stringify(legacyPreferences)});
else if(command==='exec-out screencap -p') process.stdout.write('fixture-image');
else process.exit(7);
`,{mode:0o700});
      const journey=join(directory,'journey.json');
      writeFileSync(journey,JSON.stringify({name:'draft ownership fixture',steps:[{
        action:'assert-draft',marker,scenario:'xiaowan-switch-reply.json',agentId:'kimi-code-acp',agentLabel:'Kimi Code',
      }]}));
      const evidence=join(directory,'evidence');
      const result=spawnSync(process.execPath,['scripts/verify-agent-user-journey.mjs','emulator-1234',journey,evidence],
        {env:{...process.env,ADB:adb},encoding:'utf8'});
      const report=JSON.parse(readFileSync(join(evidence,'result.json'),'utf8'));
      assert.equal(result.status,expectedFailure ? 1 : 0,JSON.stringify(report));
      assert.equal(report.passed,!expectedFailure);
      if(expectedFailure) assert.match(report.steps[0].detail,expectedFailure);
      else {
        const draft=JSON.parse(readFileSync(join(evidence,'1-draft.json'),'utf8'));
        assert.equal(draft.draftAgentId,'kimi-code-acp');
        assert.equal(draft.visibleAgentLabel,'Kimi Code');
      }
      const observations=readFileSync(calls,'utf8').trim().split('\n').map(line=>JSON.parse(line));
      assert(observations.every(args=>!args.includes('shared_prefs/acp_agent_profiles.xml')));
      assert(observations.every(args=>!args.includes('input')),'Draft verification must not mutate UI');
    } finally {rmSync(directory,{recursive:true,force:true});}
  });
}
