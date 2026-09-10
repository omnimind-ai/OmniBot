import test from 'node:test';
import assert from 'node:assert/strict';
import {loadSuite, assertJourneyPassed, assertProviderEvidence} from './verify-xiaowan-context-suite.mjs';

test('maintained suite composes exactly the existing 16 + 17 + 7 steps', () => {
  const suite = loadSuite();
  assert.equal(suite.expectedSteps, 40);
  assert.deepEqual(suite.journeys.map(j => j.expectedSteps), [16, 17, 7]);
});
test('a passed flag cannot hide missing or failed steps or another device', () => {
  const item = {id: 'example', expectedSteps: 2};
  const result = {serial: 'emulator-5560', passed: true, steps: [{index: 1, passed: true}, {index: 2, passed: true}]};
  assertJourneyPassed(result, item, result.serial);
  assert.throws(() => assertJourneyPassed({...result, steps: result.steps.slice(0, 1)}, item, result.serial));
  assert.throws(() => assertJourneyPassed({...result, steps: [{index: 1, passed: true}, {index: 2, passed: false}]}, item, result.serial));
  assert.throws(() => assertJourneyPassed(result, item, 'emulator-5554'));
});
test('old summary and image successes cannot satisfy the current suite', () => {
  const old = [{marker: 'OOB_FILE_CONTEXT_SUMMARY_LONG_1', summary: true},
    {marker: 'OOB_FILE_IMAGE_LARGE_1', validated: true, imageHash: 'a'.repeat(64)}];
  assert.throws(() => assertProviderEvidence(old, [{runId: '2'}]));
  const fresh = old.map(r => ({...r, marker: r.marker.replace(/_1$/, '_2')}));
  assert.equal(assertProviderEvidence([...old, ...fresh], [{runId: '2'}]).length, 2);
  assert.throws(() => assertProviderEvidence(fresh.filter(r => !r.summary), [{runId: '2'}]));
  assert.throws(() => assertProviderEvidence(fresh.filter(r => !r.imageHash), [{runId: '2'}]));
});
