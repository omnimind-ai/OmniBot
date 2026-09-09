import test from 'node:test';
import * as requireFs from 'node:fs';
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

const referenceScript = installer.split("node <<'OMNIBOT_DSH_PLUGIN_REFERENCE'\n")[1]
  .split('\nOMNIBOT_DSH_PLUGIN_REFERENCE')[0];
test('fresh and repeated preparation exposes installed official plugin docs without rewriting user profiles', () => {
  const root = mkdtempSync(join(tmpdir(), 'oob-dsh-reference-'));
  try {
    const packageRoot = join(root, 'package');
    const home = join(root, 'home');
    // Build source docs rather than simulating a successful DSH launch.
    const fs = requireFs;
    fs.mkdirSync(join(packageRoot, 'node_modules/@deepseek-ai/dsh-tool-cordis'), {recursive:true});
    fs.mkdirSync(home, {recursive:true});
    writeFileSync(join(packageRoot, 'package.json'), JSON.stringify({version:'0.1.2-rc.1'}));
    writeFileSync(join(packageRoot, 'README.md'), '---\ndescription: upstream\n---\n# CLI\ndsh plugin --profile acp add\n');
    const toolDoc = join(packageRoot, 'node_modules/@deepseek-ai/dsh-tool-cordis/README.md');
    writeFileSync(toolDoc, '# Dynamic\ncordis_define cordis_run cordis_stop\n');
    writeFileSync(join(home, 'cordis.patch.yml'), 'USER_PROFILE');
    const run = () => spawnSync(process.execPath, ['-e', referenceScript], {
      encoding:'utf8', env:{...process.env, DSH_PACKAGE_ROOT:packageRoot, DSH_HOME:home},
    });
    for (let i=0;i<2;i++) assert.equal(run().status, 0);
    const skill = readFileSync(join(home, 'omnibot-bundled-skills/dsh-plugins/SKILL.md'), 'utf8');
    assert.match(skill, /name: dsh-plugins/);
    assert.match(skill, /0\.1\.2-rc\.1/);
    assert.match(skill, /dsh plugin --profile acp add/);
    assert.match(skill, /cordis_define cordis_run cordis_stop/);
    assert.equal(readFileSync(join(home, 'cordis.patch.yml'), 'utf8'), 'USER_PROFILE');
    rmSync(toolDoc);
    assert.notEqual(run().status, 0, 'missing upstream documentation must fail preparation');
  } finally { rmSync(root, {recursive:true, force:true}); }
});
