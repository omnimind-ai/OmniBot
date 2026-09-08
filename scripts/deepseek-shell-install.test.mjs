import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, readFileSync, writeFileSync, rmSync, existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';

const installer = readFileSync('app/src/main/assets/acp/install/deepseek-harness.sh', 'utf8');
const preflight = installer.split('export PATH=')[0];
const agents = JSON.parse(readFileSync('app/src/main/assets/acp/agents.json', 'utf8'));

function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'oob-dsh-shell-'));
  const put = (name, body) => writeFileSync(join(root, name), '#!/bin/sh\n' + body, {mode: 0o755});
  const execute = command => spawnSync('/bin/sh', ['-c', command ?? preflight], {
    encoding: 'utf8', env: {...process.env, PATH: root, TEST_ROOT: root},
  });
  try { run({root, put, execute}); } finally { rmSync(root, {recursive: true, force: true}); }
}

for (const manager of ['apk', 'apt-get']) {
  test(`missing bash is installed through ${manager} and repeated preparation is offline`, () => {
    fixture(({root, put, execute}) => {
      put(manager, `printf '%s\n' "$*" >> "$TEST_ROOT/packages"\n` +
        `/bin/cat > "$TEST_ROOT/bash" <<'SHELL'\n#!/bin/sh\nexit 0\nSHELL\n` +
        '/bin/chmod 755 "$TEST_ROOT/bash"\n');
      assert.equal(execute().status, 0);
      const calls = readFileSync(join(root, 'packages'), 'utf8');
      assert.equal(calls, manager === 'apk' ? 'add --no-cache bash\n' :
        'install -y --no-install-recommends bash\n');
      assert.equal(execute().status, 0);
      assert.equal(readFileSync(join(root, 'packages'), 'utf8'), calls);
    });
  });
}

test('package failure and a missing or non-runnable bash cannot pass preparation', () => {
  for (const behavior of ['exit 9', 'exit 0',
    `printf '#!/bin/sh\nexit 7\n' > "$TEST_ROOT/bash"; /bin/chmod 755 "$TEST_ROOT/bash"`]) {
    fixture(({put, execute}) => {
      put('apk', behavior);
      assert.notEqual(execute().status, 0);
    });
  }
  fixture(({execute}) => assert.notEqual(execute().status, 0));
});

test('DSH health rejects a profile with no working bash', () => {
  const profiles = Array.isArray(agents) ? agents : agents.agents;
  const health = profiles.find(agent => agent.id === 'deepseek-harness-acp').runtime.managedAdapterHealthCommand;
  // Run the production shell check with an isolated PATH; later profile/native
  // checks are covered by device acceptance, not simulated as installed here.
  const shellCheck = health.match(/bash --noprofile --norc -c ':'[^&]*2>&1/)[0];
  fixture(({root, put, execute}) => {
    assert.equal(existsSync(join(root, 'bash')), false);
    assert.notEqual(execute(shellCheck).status, 0);
    put('bash', 'exit 0');
    assert.equal(execute(shellCheck).status, 0);
    put('bash', 'exit 7');
    assert.notEqual(execute(shellCheck).status, 0);
  });
});
