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
