import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
const source = readFileSync('scripts/install-dev.sh', 'utf8');

test('default install package matches the unsuffixed Android debug app', () => {
  const assignment = source.match(/^PACKAGE_NAME=.*$/m)[0];
  const env = {...process.env}; delete env.OOB_PACKAGE_NAME;
  const result = spawnSync('/bin/bash', ['-c', assignment + '\nprintf "%s" "$PACKAGE_NAME"'], {encoding:'utf8',env});
  const gradle = readFileSync('app/build.gradle.kts', 'utf8');
  const applicationId = gradle.match(/applicationId = "([^"]+)"/)[1];
  assert.equal(result.stdout, applicationId);
});

test('failed Android launch is returned to the caller', () => {
  const launch = source.match(/launch_installed_app\(\) \{[\s\S]*?\n\}/)[0];
  const result = spawnSync('/bin/bash', ['-c',
    'fake_adb() { return 9; }; ADB=(fake_adb); PACKAGE_NAME=fixture;\n' + launch + '\nlaunch_installed_app'], {encoding:'utf8'});
  assert.equal(result.status, 9);
});
