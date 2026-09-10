#!/usr/bin/env node
// Compose the existing UI journeys; never replay a failed send or manufacture replies.
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {readFileSync, writeFileSync, mkdirSync, statSync} from 'node:fs';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const scripts = dirname(fileURLToPath(import.meta.url));
const directory = resolve(scripts, 'fixtures/agent-user-journeys');
export function loadSuite() {
  const suite = JSON.parse(readFileSync(resolve(directory, 'xiaowan-context-suite.json')));
  assert.equal(new Set(suite.journeys.map(j => j.id)).size, suite.journeys.length);
  for (const item of suite.journeys) {
    const journey = JSON.parse(readFileSync(resolve(directory, item.file)));
    assert.equal(journey.steps.length, item.expectedSteps, `${item.id}: update the suite when coverage changes`);
  }
  assert.equal(suite.journeys.reduce((n, j) => n + j.expectedSteps, 0), suite.expectedSteps);
  return suite;
}
export function assertJourneyPassed(result, item, serial) {
  assert.equal(result.serial, serial);
  assert.equal(result.passed, true, `${item.id}: journey failed`);
  assert.equal(result.steps.length, item.expectedSteps, `${item.id}: incomplete journey`);
  result.steps.forEach((step, i) => {
    assert.equal(step.index, i + 1);
    assert.equal(step.passed, true);
  });
}
export function assertProviderEvidence(rows, results) {
  const runIds = new Set(results.map(r => r.runId));
  const current = rows.filter(r => runIds.has(r.marker?.split('_').at(-1)));
  assert(current.some(r => r.summary === true && r.marker.includes('_SUMMARY_')),
    'No actual summary request: offloading alone is not summary acceptance');
  assert(current.some(r => r.validated === true && r.marker.includes('_IMAGE_') && /^[a-f0-9]{64}$/.test(r.imageHash || '')),
    'No validated original image hash for this run');
  return current;
}
async function main() {
  const suite = loadSuite();
  if (process.argv[2] === '--list') {
    console.log(JSON.stringify({...suite, executed: false}, null, 2));
    return;
  }
  const [serial, output] = process.argv.slice(2);
  assert(/^emulator-\d+$/.test(serial || '') && output,
    'Usage: OOB_FILE_TEST_PROVIDER_LOG=/absolute/provider.jsonl node scripts/verify-xiaowan-context-suite.mjs emulator-N NEW_OUTPUT_DIR');
  const providerLog = process.env.OOB_FILE_TEST_PROVIDER_LOG;
  assert(providerLog, 'Start the documented file-read provider and supply its log');
  const logStart = statSync(providerLog).size;
  const out = resolve(output);
  mkdirSync(dirname(out), {recursive: true});
  mkdirSync(out); // Never overwrite or accidentally reuse an old successful run.
  const report = {suite: suite.id, serial, kind: 'emulator-user-interface', passed: false,
    expectedSteps: suite.expectedSteps, completedSteps: 0, physicalDeviceAcceptance: 'pending', journeys: []};
  const results = [];
  try {
    for (const item of suite.journeys) {
      const childOut = resolve(out, item.id);
      execFileSync(process.execPath, [resolve(scripts, 'verify-agent-user-journey.mjs'), serial,
        resolve(directory, item.file), childOut], {stdio: 'inherit', timeout: 20 * 60 * 1000});
      const result = JSON.parse(readFileSync(resolve(childOut, 'result.json')));
      assertJourneyPassed(result, item, serial);
      results.push(result);
      report.journeys.push({id: item.id, runId: result.runId, passed: true, steps: result.steps.length});
      report.completedSteps += result.steps.length;
    }
    const rows = readFileSync(providerLog).subarray(logStart).toString('utf8').trim().split('\n')
      .filter(Boolean).map(line => JSON.parse(line));
    const current = assertProviderEvidence(rows, results);
    writeFileSync(resolve(out, 'provider.jsonl'), current.map(r => JSON.stringify(r)).join('\n') + '\n');
    report.passed = true;
  } catch (error) {
    report.error = error.message;
    process.exitCode = 1;
  } finally {
    writeFileSync(resolve(out, 'result.json'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
  }
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) await main();
