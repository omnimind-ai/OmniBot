#!/usr/bin/env node
import {uiXmlField as field} from './agent-ui-xml.mjs';
import {assertMcpFixturePhase} from './mcp-fixture-observation.mjs';
// Execute a maintained UI journey on an isolated Android emulator.
// Every action uses current accessibility bounds. No ACP calls, DB writes,
// synthetic replies, retrying sends, or coordinate fallbacks count as UI acceptance.
import {execFileSync} from 'node:child_process';
import {readFileSync, mkdirSync, writeFileSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const [serial, journeyPath, outputPath] = process.argv.slice(2);
assert((/^emulator-\d+$/.test(serial || '') ||
  (process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1' && /^[A-Za-z0-9._:-]+$/.test(serial || ''))) &&
  journeyPath && outputPath,
  'Usage: verify-agent-user-journey.mjs SERIAL journey.json evidence-directory; physical devices require OOB_ALLOW_PHYSICAL_DEVICE=1');
const scripts = dirname(fileURLToPath(import.meta.url));
const journey = JSON.parse(readFileSync(journeyPath, 'utf8'));
assert(Array.isArray(journey.steps) && journey.steps.length, 'Journey requires steps');
// Old successful history must never satisfy a new run's reply assertion.
const runId = Date.now().toString();
const checkpoints = new Map();
const markers = new Map(journey.steps.filter(s => ['send','prepare-draft'].includes(s.action))
  .map(s => [s.marker, `${s.marker}_${runId}`]));
for (const step of journey.steps) {
  if (markers.has(step.marker)) step.marker = markers.get(step.marker);
  if (typeof step.text === 'string') {
    for (const [original, unique] of markers) {
      if (step.text === original || step.text.startsWith(original + '_')) {
        step.text = unique + step.text.slice(original.length);
        break;
      }
    }
  }
}
const out = resolve(outputPath);
mkdirSync(out, {recursive: true, mode: 0o700});
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {timeout: 30000, maxBuffer: 32 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe']});

function snapshot() {
  assert.match(adb('shell', 'uiautomator', 'dump', '/data/local/tmp/oob-user-journey.xml').toString(),
    /dumped to:/, 'Fresh accessibility snapshot unavailable');
  return [...adb('shell', 'cat', '/data/local/tmp/oob-user-journey.xml').toString()
    .matchAll(/<node\b[^>]*>/g)].map(([n]) => n)
    .filter(n => field(n, 'package') === 'cn.com.omnimind.bot');
}
const label = n => field(n, 'content-desc') || field(n, 'text');
const report = {name: journey.name, serial, runId,
  kind: serial.startsWith('emulator-') ? 'emulator-user-interface' : 'physical-device-user-interface',
  passed: false, steps: []};
let index = 0;
let mcpBaseline;
try {
  if (journey.requireClockSync) {
    const deviceSeconds = Number(adb('shell', 'date', '+%s').toString().trim());
    assert(Number.isFinite(deviceSeconds) && Math.abs(deviceSeconds - Date.now() / 1000) < 300,
      'Device clock differs by more than five minutes; correct test environment before real TLS requests');
  }
  for (const step of journey.steps) {
    index++;
    const started = Date.now();
    if (step.action === 'mcp-fixture') {
      const deadline=Date.now()+20000;
      while(true) {
        const response=await fetch('http://127.0.0.1:29091/observations', {signal:AbortSignal.timeout(3000)});
        assert(response.ok,'MCP fixture observations unavailable');
        const observation=await response.json();
        if(step.phase==='baseline') {mcpBaseline=observation.events.length;break;}
        assert(Number.isInteger(mcpBaseline),'MCP baseline must precede send');
        try {
          const verified=assertMcpFixturePhase(observation,mcpBaseline,step.phase);
          writeFileSync(resolve(out,`${index}-mcp-fixture.json`),JSON.stringify(verified,null,2));
          break;
        } catch(error) {if(Date.now()>=deadline) throw error;}
        await new Promise(resolve=>setTimeout(resolve,250));
      }
    } else if (step.action === 'tap' || step.action === 'long-press') {
      execFileSync(process.execPath, [resolve(scripts, 'tap-agent-device-control.mjs'), serial, step.label, step.parentLabel || '', ...(step.action === 'long-press' ? ['long-press'] : [])],
        {timeout: 40000, stdio: ['ignore', 'pipe', 'pipe']});
    } else if (step.action === 'dismiss-settings') {
      const nodes=snapshot();
      assert(nodes.some(n=>label(n)==='Next turn') && nodes.some(n=>label(n)==='Model & settings'), 'Expected Agent settings sheet');
      adb('shell','input','keyevent','4');
      const returned=snapshot();
      assert(returned.some(n=>field(n,'class')==='android.widget.EditText') && !returned.some(n=>label(n)==='Next turn'), 'System back did not close settings while preserving the conversation');
    } else if (step.action === 'dismiss-permissions') {
      const nodes = snapshot();
      assert(nodes.some(n => label(n) === 'Read only') && nodes.some(n => label(n) === 'Workspace write'), 'Expected permission popup');
      adb('shell', 'input', 'keyevent', '4');
      const returned = snapshot();
      assert(returned.some(n => field(n, 'class') === 'android.widget.EditText') && !returned.some(n => label(n) === 'Read only'), 'System back did not close permissions while preserving the conversation');
    } else if (step.action === 'clear-command-draft') {
      const inputs = snapshot().filter(n => field(n,'class') === 'android.widget.EditText');
      assert.equal(inputs.length,1);
      assert.equal(field(inputs[0],'text'), '/', 'Only the command-menu slash may be cleared');
      assert.equal(field(inputs[0],'focused'),'true');
      adb('shell','input','keyevent','67');
      assert(snapshot().some(n => field(n,'class') === 'android.widget.EditText' && field(n,'text') === ''));
    } else if (step.action === 'assert-draft') {
      const scenario=JSON.parse(readFileSync(resolve(scripts,'fixtures','user-scenarios',step.scenario),'utf8'));
      const expected=`${scenario.prompt.replaceAll('{{MARKER}}',step.marker)} End your final reply with ${step.marker}_DONE.`;
      const inputs=snapshot().filter(n=>field(n,'class')==='android.widget.EditText');
      assert.equal(inputs.length,1,'Expected unique draft input');
      assert.equal(field(inputs[0],'text'),expected,'Switch changed the draft');
      const selected=execFileSync('python3',['-c',
        "import sys,xml.etree.ElementTree as E; r=E.fromstring(sys.stdin.read()); print(next((n.text for n in r if n.get('name')=='selected_profile_id'),''))"],
        {input:adb('exec-out','run-as','cn.com.omnimind.bot','cat','shared_prefs/acp_agent_profiles.xml'),encoding:'utf8'}).trim();
      assert.equal(selected,step.agentId,'Wrong native selected Harness');
      writeFileSync(resolve(out,`${index}-draft.json`),JSON.stringify({passed:true,marker:step.marker,selectedAgentId:selected,characters:expected.length}));
    } else if (step.action === 'search') {
      assert(/^[A-Za-z0-9._-]+$/.test(step.text), 'Search only accepts a non-private model ID');
      const inputs = snapshot().filter(n => field(n, 'class') === 'android.widget.EditText' &&
        (field(n, 'hint') === step.hint ||
          (field(n, 'focused') === 'true' && field(n, 'text') === step.text)) &&
        field(n, 'enabled') === 'true');
      assert.equal(inputs.length, 1, 'Expected one model search input');
      assert(['', step.text].includes(field(inputs[0], 'text')), 'Preserve existing search input');
      const b = [...field(inputs[0], 'bounds').matchAll(/\d+/g)].map(([n]) => Number(n));
      assert.equal(b.length, 4);
      adb('shell', 'input', 'tap', String(Math.round((b[0]+b[2])/2)), String(Math.round((b[1]+b[3])/2)));
      assert(snapshot().some(n => field(n, 'class') === 'android.widget.EditText' && field(n, 'focused') === 'true'),
        'Model search did not gain focus');
      if (!field(inputs[0], 'text')) adb('shell', 'input', 'text', step.text);
      assert(snapshot().some(n => field(n, 'class') === 'android.widget.EditText' &&
        field(n, 'focused') === 'true' && field(n, 'text') === step.text),
        'Search text was not entered correctly');
    } else if (step.action === 'open-xiaowan-session-list') {
      // The current Android tab strip lacks individual accessibility labels.
      // Derive its bounds from a fresh, unique horizontal scrollable surface;
      // this fixture selects Xiaowan's left tab, never stale screen coordinates.
      const findStrips = () => snapshot().filter(n => field(n,'scrollable') === 'true' && !label(n)).filter(n => {
          const b = [...field(n,'bounds').matchAll(/\d+/g)].map(([v]) => +v);
          return b.length === 4 && b[1] < 500 && (b[2]-b[0]) > 3*(b[3]-b[1]);
        });
      let strips = findStrips();
      const interactive = strips.filter(n => field(n,'clickable') === 'true');
      if (interactive.length) strips = interactive;
      assert.equal(strips.length,1,'Expected unique Xiaowan tab strip');
      if (field(strips[0],'clickable') !== 'true') {
        // Tool activity selects the tools layer. An upward gesture restores
        // the mode layer using the existing app-bar gesture.
        const region = [...field(strips[0],'bounds').matchAll(/\d+/g)].map(([v]) => +v);
        const x = String(Math.round((region[0]+region[2])/2));
        adb('shell','input','swipe',x,String(Math.round((region[1]+region[3])/2)),x,String(Math.max(0,region[1]-100)),'400');
        strips = findStrips().filter(n => field(n,'clickable') === 'true');
        assert.equal(strips.length,1);
        assert.equal(field(strips[0],'clickable'),'true','Mode layer did not become interactive');
      }
      const b = [...field(strips[0],'bounds').matchAll(/\d+/g)].map(([v]) => +v);
      adb('shell','input','tap',String(Math.round(b[0]+(b[2]-b[0])/4)),String(Math.round((b[1]+b[3])/2)));
    } else if (step.action === 'session-counters') {
      const deadline = Date.now() + (step.timeoutMs || 30000);
      while (true) {
        try {
          const verified = JSON.parse(execFileSync('python3', [resolve(scripts,'assert-agent-session-list.py'),serial,
            '--running',String(step.running),'--loaded',String(step.loaded),'--archived','0'],
            {encoding:'utf8',timeout:30000,stdio:['ignore','pipe','pipe']}));
          writeFileSync(resolve(out,`${index}-session-counters.json`),JSON.stringify(verified,null,2));
          break;
        } catch(error) { if(Date.now() >= deadline) throw error; }
        await new Promise(r => setTimeout(r,750));
      }
    } else if (['init','init-attachment-cancel','init-double-tap-cancel'].includes(step.action)) {
      const before = execFileSync('python3', ['-c',
        "from agent_test_database import agent_database_snapshot; import sys; " +
        "ctx=agent_database_snapshot(sys.argv[1]); db=ctx.__enter__(); " +
        "print(db.execute('SELECT coalesce(max(id),0) FROM agent_conversation_entries').fetchone()[0]); ctx.__exit__(None,None,None)", serial],
        {cwd:scripts,encoding:'utf8',timeout:30000}).trim();
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Init requires idle composer');
      for (const control of (step.menuAlreadyOpen ? ['init'] : ['命令','init'])) {
        const readyDeadline=Date.now()+30000;
        while (!snapshot().some(n => label(n).split('\n')[0]===control &&
          field(n,'clickable')==='true' && field(n,'enabled')==='true')) {
          assert(Date.now()<readyDeadline, `Init control not ready: ${control}`);
          await new Promise(r=>setTimeout(r,500));
        }
        const gesture=control==='init' && step.action==='init-double-tap-cancel' ? ['','double-tap'] : [];
        const tapResult=execFileSync(process.execPath,[resolve(scripts,'tap-agent-device-control.mjs'),serial,control,...gesture],
          {timeout:40000,stdio:['ignore','pipe','pipe']});
        if (gesture.length) writeFileSync(resolve(out,`${index}-double-tap.json`),tapResult);
      }
      const deadline=Date.now()+(step.timeoutMs || 600000);
      const attachmentArgs=step.action==='init-attachment-cancel'
        ? ['--attachment','oob-attachment-admission.txt'] : [];
      const cancelInit=step.action!=='init';
      if (cancelInit) {
        while(true) {
          try {
            const active=execFileSync('python3',[resolve(scripts,'assert-agent-init.py'),serial,before,
              '--expected','active',...attachmentArgs],{encoding:'utf8',timeout:30000});
            writeFileSync(resolve(out,`${index}-init-active.json`),active);
            break;
          } catch(error) {if(error.status!==2 || Date.now()>=deadline) throw error;}
          await new Promise(r=>setTimeout(r,1000));
        }
        execFileSync(process.execPath,[resolve(scripts,'tap-agent-device-control.mjs'),serial,'Stop'],
          {timeout:40000,stdio:['ignore','pipe','pipe']});
      }
      while(true) {
        try {
          const result=execFileSync('python3',[resolve(scripts,'assert-agent-init.py'),serial,before,
            '--expected',cancelInit ? 'cancelled' : 'end_turn',...attachmentArgs],
            {encoding:'utf8',timeout:30000});
          writeFileSync(resolve(out,`${index}-init.json`),result);
          break;
        } catch(error) {
          if(error.status!==2 || Date.now()>=deadline) throw error;
        }
        await new Promise(r=>setTimeout(r,5000));
      }
      if (!cancelInit) {
        adb('shell','run-as','cn.com.omnimind.bot','test','-s','workspace/AGENTS.md');
        writeFileSync(resolve(out,`${index}-file.sha256`),
          adb('shell','run-as','cn.com.omnimind.bot','sha256sum','workspace/AGENTS.md'));
      }
    } else if (step.action === 'unsupported-command') {
      assert(['/pause', '/resume'].includes(step.command), 'Only unsupported lifecycle commands');
      const admissions = () => execFileSync('python3', ['-c',
        "from agent_test_database import agent_database_snapshot; import sys; " +
        "ctx=agent_database_snapshot(sys.argv[1]); db=ctx.__enter__(); " +
        "print(db.execute(\"SELECT count(*) FROM agent_conversation_entries WHERE entryType='user_message'\").fetchone()[0]); ctx.__exit__(None,None,None)", serial],
        {cwd: scripts, encoding: 'utf8', timeout: 30000}).trim();
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Command check requires an idle turn');
      const before = admissions();
      execFileSync(process.execPath, [resolve(scripts, 'send-agent-test-message.mjs'), serial, step.command],
        {timeout: 90000, stdio: ['ignore', 'pipe', 'pipe']});
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Unsupported command started a turn');
      assert.equal(admissions(), before, 'Unsupported command admitted a user prompt');
    } else if (step.action === 'compact') {
      execFileSync(process.execPath, [resolve(scripts, 'send-agent-test-message.mjs'), serial, '/compact'],
        {timeout: 90000, stdio: ['ignore', 'pipe', 'pipe']});
    } else if (step.action === 'turn-outcome') {
      // A visible final text chunk can precede PromptResponse and its durable
      // commit. Observe that completion; never resend the logical user turn.
      const deadline = Date.now() + 60000;
      let verified;
      while (!verified) {
        try {
          verified = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-agent-turn-outcome.py'), serial, step.marker, step.expected, ...(step.summary ? [step.summary] : [])],
            {encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe']}));
        } catch (error) {
          if (Date.now() >= deadline || !/Missing canonical completion|Missing canonical cancellation|Turn still loading|Missing current pending permission|Missing or mixed turn identity/.test(String(error.stderr))) throw error;
        }
      }
      assert(verified.passed, 'Canonical turn did not complete');
      writeFileSync(resolve(out, `${index}-turn-outcome.json`), JSON.stringify(verified, null, 2));
    } else if (step.action === 'workspace-file-absent') {
      assert(/^OOB_FAILURE_STREAMTOOL_\d+$/.test(step.marker), 'Only synthetic failed-tool output may be checked');
      adb('shell','run-as','cn.com.omnimind.bot','test','-d','workspace');
      adb('shell','run-as','cn.com.omnimind.bot','test','!','-e',`workspace/${step.marker}-must-not-exist.txt`);
    } else if (step.action === 'session-config') {
      const verified=JSON.parse(execFileSync('python3',[resolve(scripts,'assert-agent-session-config.py'),serial,step.marker,step.effort],{encoding:'utf8',timeout:60000}));
      assert(verified.passed);
      writeFileSync(resolve(out,`${index}-session-config.json`),JSON.stringify(verified));
    } else if (step.action === 'terminal-child') {
      assert(['started','stopped'].includes(step.state));
      const record = resolve(out, `${step.marker}-child.json`);
      const verified = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-terminal-child-state.py'), serial, step.marker, step.state, record], {encoding:'utf8',timeout:90000}));
      writeFileSync(resolve(out, `${index}-terminal-child.json`), JSON.stringify(verified));
    } else if (step.action === 'live-task') {
      const verified = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-xiaowan-live-task.py'), serial, step.marker, step.phase], {encoding: 'utf8', timeout: 60000}));
      writeFileSync(resolve(out, `${index}-live-task.json`), JSON.stringify(verified, null, 2));
    } else if (step.action === 'checkpoint') {
      const checkpoint = JSON.parse(execFileSync('python3', [resolve(scripts, 'assert-agent-context-checkpoint.py'), serial, step.marker], {encoding: 'utf8', timeout: 30000}));
      if (checkpoints.has(step.marker)) assert.deepEqual(checkpoint, checkpoints.get(step.marker), 'Checkpoint changed across restart');
      checkpoints.set(step.marker, checkpoint);
      writeFileSync(resolve(out, `${index}-checkpoint.json`), JSON.stringify(checkpoint, null, 2));
    } else if (step.action === 'send' || step.action === 'prepare-draft') {
      let observations;
      try {
        observations = execFileSync(process.execPath, [resolve(scripts, 'send-agent-test-message.mjs'), serial, step.marker],
        {timeout: step.timeoutMs || 90000, stdio: ['ignore', 'pipe', 'pipe'],
          env: {...process.env, OOB_PREPARE_DRAFT_ONLY:step.action==='prepare-draft'?'1':'0', ...(step.scenario ? {OOB_USER_SCENARIO_FILE: resolve(scripts, 'fixtures', 'user-scenarios', step.scenario)} : {})}});
      } catch (error) {
        observations = error.stdout;
        throw error;
      } finally {
        // The sender emits only synthetic markers and semantic bounds, never user content.
        if (observations) writeFileSync(resolve(out, `${index}-send.jsonl`), observations, {mode: 0o600});
      }
    } else if (step.action === 'expect' || step.action === 'reply') {
      const deadline = Date.now() + (step.timeoutMs || 120000);
      let found = false;
      let snapshotFailures = 0;
      do {
        let nodes;
        try {
          nodes = snapshot();
        } catch {
          // UIAutomator can be unavailable during window transitions. Repeat
          // observation only; never repeat a tap or send or reuse stale XML.
          snapshotFailures++;
          await new Promise(r => setTimeout(r, 750));
          continue;
        }
        // A user bubble says "Reply MARKER". Only a separate exact line in
        // an assistant item qualifies, and the Send control must be idle.
        const matching = nodes.filter(n => label(n).split('\n').includes(step.text) &&
          !label(n).includes(`Reply ${step.text}`));
        const running = nodes.some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n)));
        found = matching.length > 0 && (step.action !== 'reply' || !running);
        if (found) break;
        await new Promise(r => setTimeout(r, 750));
      } while (Date.now() < deadline);
      assert(found, `Expected visible result did not appear before deadline (${snapshotFailures} unavailable snapshots)`);
    } else if (step.action === 'assertAbsent') {
      const matching = snapshot().filter(n => label(n).split('\n')[0] === step.label &&
        field(n, 'enabled') === 'true' && field(n, 'clickable') === 'true');
      assert.equal(matching.length, 0, `Stale interactive control remains: ${step.label}`);
    } else if (step.action === 'background') {
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))), 'Parent turn must be idle');
      if (step.marker) {
        assert(/^OOB_LIVE_SCHEDULE_[0-9]+$/.test(step.marker));
        let exists = false;
        try { adb('shell','run-as','cn.com.omnimind.bot','test','-e',`workspace/oob-live-scheduled/${step.marker}.txt`); exists = true; }
        catch (error) { assert.equal(error.status, 1, 'Could not verify artifact absence'); }
        assert(!exists, 'Artifact appeared before background acceptance began');
      }
      adb('shell', 'input', 'keyevent', '3');
    } else if (step.action === 'scheduled-file') {
      assert(/^OOB_LIVE_SCHEDULE_[0-9]+$/.test(step.marker));
      const path = `workspace/oob-live-scheduled/${step.marker}.txt`;
      const deadline = Date.now() + (step.timeoutMs || 180000);
      let content;
      do {
        try { content = adb('shell','run-as','cn.com.omnimind.bot','cat',path).toString(); }
        catch { /* Observe only; never trigger or resubmit the scheduled task. */ }
        if (content?.trim() === `${step.marker}_FIRED`) break;
        await new Promise(r => setTimeout(r, 1500));
      } while (Date.now() < deadline);
      assert.equal(content?.trim(), `${step.marker}_FIRED`, 'Scheduled artifact not produced before deadline');
      writeFileSync(resolve(out, `${index}-scheduled-file.json`), JSON.stringify({marker:step.marker,content:content.trim(),passed:true}));
    } else if (step.action === 'foreground') {
      adb('shell', 'am', 'start', '-n', 'cn.com.omnimind.bot/.activity.LauncherActivity');
    } else if (step.action === 'restart') {
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))),
        'Cannot restart during an active turn');
      adb('shell', 'am', 'force-stop', 'cn.com.omnimind.bot');
      adb('shell', 'am', 'start', '-n', 'cn.com.omnimind.bot/.activity.LauncherActivity');
    } else {
      throw new Error('Unknown journey action');
    }
    writeFileSync(resolve(out, `${index}.png`), adb('exec-out', 'screencap', '-p'), {mode: 0o600});
    report.steps.push({index, action: step.action, marker: step.marker,
      passed: true, elapsedMs: Date.now() - started});
    console.log(JSON.stringify(report.steps.at(-1)));
  }
  report.passed = true;
} catch (error) {
  report.steps.push({index, passed: false, errorType: error.name,
    code: error.code, status: error.status, signal: error.signal,
    detail: (error.stderr?.toString() || error.message)?.slice(0, 2000),
    assertionSite: error.stack?.split('\n').find(line => line.includes('verify-agent-user-journey.mjs:'))?.trim()});
  process.exitCode = 1;
} finally {
  writeFileSync(resolve(out, 'result.json'), JSON.stringify(report, null, 2), {mode: 0o600});
  console.log(JSON.stringify({name: journey.name, passed: report.passed, evidenceDirectory: out}));
}
