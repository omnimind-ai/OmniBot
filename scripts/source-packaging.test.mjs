import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';

test('release sources are not silently omitted by local Git ignore rules', () => {
  const ignored = execFileSync('git', ['ls-files', '--others', '--ignored',
    '--exclude-standard', '--', 'app/src', 'baselib/src', 'assists/src',
    'uikit/src', 'ui/lib', 'ui/test', 'ReTerminal/core/*/src'], {encoding:'utf8'})
    .split('\n').filter(path => /\.(kt|java|dart|kts|c|cpp|h)$/.test(path));
  assert.deepEqual(ignored, [], 'Untracked ignored source files would be missing from a clean release checkout');
});
