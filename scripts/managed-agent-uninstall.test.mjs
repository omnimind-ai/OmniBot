import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, lstatSync, existsSync, realpathSync, rmSync, symlinkSync, readlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';

const require = createRequire(import.meta.url);
const { uninstallAgent, layoutFor } = require('../app/src/main/assets/acp/uninstall-agent.cjs');
const asset = readFileSync(new URL('../app/src/main/assets/acp/uninstall-agent.cjs', import.meta.url), 'utf8');
const piInstaller = readFileSync(new URL('../app/src/main/assets/acp/install/pi.sh', import.meta.url), 'utf8');
const definitions = {
  'claude-code': [['@anthropic-ai/claude-code', 'claude'], ['@agentclientprotocol/claude-agent-acp', 'claude-agent-acp']],
  codex: [['@openai/codex', 'codex'], ['@agentclientprotocol/codex-acp', 'codex-acp']],
  opencode: [['@opencode/cli', 'opencode'], ['opencode-ai', 'opencode2']],
  'deepseek-harness': [
    ['@deepseek-ai/dsh', 'dsh'],
    ['@openma/deepseek-harness-acp', 'dsh-acp'],
    ['@deepseek-ai/dsh-acp-demo', 'dsh-acp-demo'],
  ],
  pi: [],
  'kimi-code': [['@moonshot-ai/kimi-code', 'kimi']],
};

function fixture(t, distribution = 'alpine') {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'omnibot uninstall ')));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const layout = layoutFor(root);
  const external = join(root, 'external/bin');
  for (const directory of [layout.bin, layout.systemBin, external]) mkdirSync(directory, { recursive: true });
  const write = (file, content, executable = false) => {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content, { mode: executable ? 0o755 : 0o600 });
  };
  write(join(root, 'etc', distribution === 'alpine' ? 'alpine-release' : 'lsb-release'), distribution);
  const retained = [
    'root/.claude/settings.json', 'root/.claude/projects/session.jsonl',
    'root/.codex/auth.json', 'root/.codex/sessions/history.jsonl',
    'root/.config/opencode/opencode.json', 'root/.config/opencode/opencode.json.pre-v2.bak',
    'root/.local/share/opencode/storage/session.json',
    'root/.dsh/omnibot-acp/profiles/acp/package.json', 'root/.dsh/omnibot-acp/profiles/acp/node_modules/user-plugin/index.js',
    'root/.dsh/omnibot-acp/profiles/acp/node_modules/@openma/deepseek-harness-acp/package.json',
    'root/.dsh/omnibot-acp/omnibot-acp-headless.patch.yml', 'root/.dsh/settings.json', 'root/.dsh/sessions/history.jsonl',
    'root/.dsh/omnibot-acp/sessions/session.json', 'root/.pi/omnibot-acp/models.json',
    'root/.pi/omnibot-acp/sessions/session.jsonl', 'root/.kimi-code/omnibot/config.toml',
    'root/.kimi-code/sessions/session.jsonl', 'workspace/.omnibot/history.jsonl',
    'usr/bin/node', 'usr/bin/npm', 'usr/bin/python3', 'usr/bin/bash', 'usr/bin/rg',
    'other-distribution/root/.npm-global/lib/node_modules/@openai/codex/package.json',
  ].map((file) => join(root, file));
  for (const file of retained) write(file, `preserve:${file}`);
  const addPackage = (name, command) => {
    const packageRoot = join(layout.modules, name);
    const binFile = join(packageRoot, 'bin/cli.js');
    write(binFile, '#!/bin/sh\nexit 0\n', true);
    write(join(packageRoot, 'package.json'), JSON.stringify({
      name, version: '1.0.0', bin: { [command]: 'bin/cli.js' },
      scripts: { preuninstall: `touch ${join(root, 'UNEXPECTED_LIFECYCLE')}`, postuninstall: `touch ${join(root, 'UNEXPECTED_LIFECYCLE')}` },
    }));
    symlinkSync(relative(layout.bin, binFile), join(layout.bin, command));
    symlinkSync(join(layout.bin, command), join(layout.systemBin, command));
    return packageRoot;
  };
  addPackage('@fixture/shared-tool', 'shared-tool');
  retained.push(join(layout.modules, '@fixture/shared-tool/package.json'));
  const install = (strategy) => {
    for (const [name, command] of definitions[strategy]) addPackage(name, command);
    if (strategy === 'pi') {
      for (const digest of ['a'.repeat(20), 'b'.repeat(20)]) write(join(layout.piVersions, digest, 'dist/index.js'), 'SDK runtime', true);
      write(join(layout.bin, 'omnibot-pi-acp'), `#!/bin/sh\nexec node '${join(layout.piVersions, 'b'.repeat(20), 'dist/index.js')}' "$@"\n`, true);
      symlinkSync(join(layout.bin, 'omnibot-pi-acp'), join(layout.systemBin, 'omnibot-pi-acp'));
      write(layout.piInstaller, `#!/bin/sh\n${piInstaller}`);
    }
    if (strategy === 'deepseek-harness') {
      write(join(layout.bin, 'dsh-acp-android'), '#!/bin/sh\n' +
        'export DSH_BUNDLED_SKILL_DIR="${DSH_BUNDLED_SKILL_DIR:-$DSH_HOME/omnibot-bundled-skills}"\n' +
        `exec node --expose-internals ${join(layout.modules, '@deepseek-ai/dsh/lib/bin.js')} "$@"\n`, true);
    }
  };
  const npmCalls = [];
  const removePackage = (name) => {
    const packageRoot = join(layout.modules, name);
    const metadata = join(packageRoot, 'package.json');
    if (existsSync(metadata)) {
      const pkg = JSON.parse(readFileSync(metadata, 'utf8'));
      for (const command of Object.keys(pkg.bin || {})) rmSync(join(layout.bin, command), { force: true });
    }
    rmSync(packageRoot, { recursive: true, force: true });
  };
  const runNpm = (args) => {
    npmCalls.push(args);
    assert.deepEqual(args.slice(0, 8), ['uninstall', '-g', '--prefix', layout.prefix, '--ignore-scripts', '--offline', '--no-audit', '--no-fund']);
    for (const name of args.slice(8)) removePackage(name);
    return { status: 0, stdout: '', stderr: '' };
  };
  const options = { root, searchPath: [external], runNpm };
  const assertRetained = () => {
    for (const file of retained) assert.ok(existsSync(file), file);
    for (const file of retained.filter((file) => !file.endsWith('@fixture/shared-tool/package.json'))) assert.equal(readFileSync(file, 'utf8'), `preserve:${file}`);
    assert.equal(existsSync(join(root, 'UNEXPECTED_LIFECYCLE')), false);
    assert.ok(existsSync(join(layout.bin, 'shared-tool')));
  };
  return { root, layout, external, write, install, addPackage, npmCalls, runNpm, removePackage, options, assertRetained };
}

function tree(root) {
  const result = {};
  const visit = (directory) => {
    for (const name of readdirSync(directory).sort()) {
      const file = join(directory, name);
      const info = lstatSync(file);
      const key = relative(root, file);
      if (info.isSymbolicLink()) result[key] = { link: readlinkSync(file) };
      else if (info.isDirectory()) { result[key] = {}; visit(file); }
      else result[key] = readFileSync(file).toString('base64');
    }
  };
  visit(root);
  return result;
}

for (const distribution of ['alpine', 'ubuntu']) {
  for (const strategy of Object.keys(definitions)) {
    test(`${distribution}: ${strategy} removes only managed installation files and is idempotent`, (t) => {
      const f = fixture(t, distribution);
      f.install(strategy);
      const original = tree(f.root);
      const before = uninstallAgent(strategy, { ...f.options, probe: true });
      assert.equal(before.managedFilesRemain, true);
      assert.equal(before.commandPresent, true);
      assert.deepEqual(before.externalCommands, []);
      assert.deepEqual(tree(f.root), original, 'probe must be read-only');
      assert.deepEqual(f.npmCalls, []);
      assert.deepEqual(uninstallAgent(strategy, f.options), { ok: true, managedFilesRemain: false, commandPresent: false, externalCommands: [] });
      f.assertRetained();
      const calls = f.npmCalls.length;
      assert.equal(uninstallAgent(strategy, f.options).ok, true);
      assert.equal(f.npmCalls.length, calls, 'no npm invocation for absent packages');
      if (strategy === 'pi') {
        assert.equal(existsSync(f.layout.piVersions), false);
        assert.equal(existsSync(f.layout.piInstaller), false);
        assert.equal(calls, 0);
      }
    });
  }
}

for (const strategy of ['codex', 'deepseek-harness']) {
  test(`offline npm removes actual ${strategy} global packages without scripts or unrelated data`, (t) => {
    const f = fixture(t);
    f.install(strategy);
    if (strategy === 'deepseek-harness') {
      f.write(join(f.layout.bin, 'dsh-acp-android'), legacyDeepSeekWrapper(f.layout), true);
      symlinkSync(join(f.layout.bin, 'dsh-acp-android'), join(f.layout.systemBin, 'dsh-acp-android'));
    }
    const result = uninstallAgent(strategy, {
      ...f.options,
      runNpm: (args) => spawnSync('npm', args, {
        encoding: 'utf8', timeout: 20000,
        env: { ...process.env, npm_config_registry: 'http://127.0.0.1:1', npm_config_cache: join(f.root, 'npm-cache') },
      }),
    });
    assert.equal(result.ok, true, result.error);
    for (const [name] of definitions[strategy]) assert.equal(existsSync(join(f.layout.modules, name)), false);
    f.assertRetained();
  });
}

function legacyDeepSeekWrapper(layout) {
  const entry = join(layout.modules, '@deepseek-ai/dsh/lib/bin.js');
  const patch = join(layout.root, 'root/.dsh/omnibot-acp/omnibot-acp-headless.patch.yml');
  return `#!/bin/sh\nexec node --expose-internals ${entry} --patch "${patch}" "$@"\n`;
}

for (const installed of [true, false]) {
  test(`retired DeepSeek headless wrapper is cleaned with packages ${installed ? 'present' : 'already absent'}`, (t) => {
    const f = fixture(t);
    if (installed) f.install('deepseek-harness');
    const wrapper = join(f.layout.bin, 'dsh-acp-android');
    const alias = join(f.layout.systemBin, 'dsh-acp-android');
    const source = legacyDeepSeekWrapper(f.layout);
    assert.equal(Buffer.byteLength(source.replaceAll(f.root, '')), 171, 'match the shipped legacy wrapper');
    f.write(wrapper, source, true);
    symlinkSync(wrapper, alias);
    const original = tree(f.root);
    const before = uninstallAgent('deepseek-harness', { ...f.options, probe: true });
    assert.equal(before.managedFilesRemain, true);
    assert.deepEqual(before.externalCommands, []);
    assert.deepEqual(tree(f.root), original);
    const result = uninstallAgent('deepseek-harness', f.options);
    assert.equal(result.ok, true, result.error);
    assert.equal(existsSync(wrapper), false);
    assert.throws(() => lstatSync(alias), { code: 'ENOENT' });
    assert.equal(f.npmCalls.length, installed ? 1 : 0);
    if (installed) assert.deepEqual(f.npmCalls[0].slice(8), definitions['deepseek-harness'].map(([name]) => name));
    assert.equal(uninstallAgent('deepseek-harness', f.options).ok, true);
    assert.equal(f.npmCalls.length, installed ? 1 : 0);
    f.assertRetained();
  });
}

test('retired DeepSeek plain wrapper is cleaned without requiring the removed runtime', (t) => {
  const f = fixture(t);
  const wrapper = join(f.layout.bin, 'dsh-acp-android');
  f.write(wrapper, `#!/bin/sh\nexec node --expose-internals ${join(f.layout.modules, '@deepseek-ai/dsh/lib/bin.js')} "$@"\n`, true);
  const result = uninstallAgent('deepseek-harness', f.options);
  assert.equal(result.ok, true, result.error);
  assert.equal(existsSync(wrapper), false);
  assert.deepEqual(f.npmCalls, []);
  f.assertRetained();
});

test('retired DeepSeek dangling npm links and setup aliases are removed without npm', (t) => {
  const f = fixture(t);
  const entries = [];
  for (const [name, command] of definitions['deepseek-harness']) {
    const bin = join(f.layout.bin, command);
    const alias = join(f.layout.systemBin, command);
    symlinkSync(relative(f.layout.bin, join(f.layout.modules, name, 'bin/cli.js')), bin);
    symlinkSync(bin, alias);
    entries.push(bin, alias);
  }
  const result = uninstallAgent('deepseek-harness', f.options);
  assert.equal(result.ok, true, result.error);
  for (const file of entries) assert.throws(() => lstatSync(file), { code: 'ENOENT' });
  assert.deepEqual(f.npmCalls, []);
  f.assertRetained();
});

for (const change of ['external entry', 'different patch', 'appended command']) {
  test(`retired DeepSeek cleanup preserves an unknown wrapper with ${change}`, (t) => {
    const f = fixture(t);
    f.install('deepseek-harness');
    const wrapper = join(f.layout.bin, 'dsh-acp-android');
    let source = legacyDeepSeekWrapper(f.layout);
    if (change === 'external entry') source = source.replace(join(f.layout.modules, '@deepseek-ai/dsh/lib/bin.js'), '/custom/dsh/lib/bin.js');
    if (change === 'different patch') source = source.replace('omnibot-acp-headless.patch.yml', 'user.patch.yml');
    if (change === 'appended command') source += 'echo user customization\n';
    f.write(wrapper, source, true);
    const alias = join(f.layout.systemBin, 'dsh-acp-android');
    symlinkSync(wrapper, alias);
    const result = uninstallAgent('deepseek-harness', f.options);
    assert.equal(result.ok, false);
    assert.equal(result.managedFilesRemain, false);
    assert.equal(result.commandPresent, true);
    assert.ok(result.externalCommands.includes(wrapper));
    assert.ok(result.externalCommands.includes(alias));
    assert.equal(readFileSync(wrapper, 'utf8'), source);
    assert.equal(readlinkSync(alias), wrapper);
    f.assertRetained();
  });
}

test('retired DeepSeek cleanup preserves external legacy adapter installations', (t) => {
  const f = fixture(t);
  f.install('deepseek-harness');
  const external = join(f.external, 'dsh-acp');
  f.write(external, '#!/bin/sh\nexit 0\n', true);
  const result = uninstallAgent('deepseek-harness', f.options);
  assert.equal(result.ok, false);
  assert.equal(result.managedFilesRemain, false);
  assert.deepEqual(result.externalCommands, [external]);
  assert.equal(readFileSync(external, 'utf8'), '#!/bin/sh\nexit 0\n');
  f.assertRetained();
});

test('partial npm failure reports disk state and a later attempt completes cleanup', (t) => {
  const f = fixture(t);
  f.install('codex');
  const first = uninstallAgent('codex', { ...f.options, runNpm: () => {
    f.removePackage('@openai/codex');
    return { status: 1, stderr: 'fixture: could not remove adapter' };
  } });
  assert.equal(first.ok, false);
  assert.equal(first.managedFilesRemain, true);
  assert.equal(first.commandPresent, true);
  assert.match(first.error, /could not remove adapter/);
  assert.equal(uninstallAgent('codex', f.options).ok, true);
  f.assertRetained();
});

test('missing npm does not fall back to recursively deleting npm installations', (t) => {
  const f = fixture(t);
  f.install('kimi-code');
  const original = tree(f.root);
  const result = uninstallAgent('kimi-code', { ...f.options, runNpm: () => ({ error: new Error('npm ENOENT') }) });
  assert.equal(result.ok, false);
  assert.match(result.error, /npm ENOENT/);
  assert.deepEqual(tree(f.root), original);
});

test('a successful npm exit does not hide remaining managed files', (t) => {
  const f = fixture(t);
  f.install('kimi-code');
  const result = uninstallAgent('kimi-code', { ...f.options, runNpm: () => ({ status: 0 }) });
  assert.equal(result.ok, false);
  assert.equal(result.managedFilesRemain, true);
  assert.match(result.error, /installation files or commands remain/);
  f.assertRetained();
});

test('a failed npm exit still reports that the actual package files are gone', (t) => {
  const f = fixture(t);
  f.install('kimi-code');
  // No setup alias is left in this fixture, so the post-failure inventory is empty.
  rmSync(join(f.layout.systemBin, 'kimi'));
  const result = uninstallAgent('kimi-code', { ...f.options, runNpm: () => {
    f.removePackage('@moonshot-ai/kimi-code');
    return { status: 1, stderr: 'late npm failure' };
  } });
  assert.equal(result.ok, false);
  assert.equal(result.managedFilesRemain, false);
  assert.equal(result.commandPresent, false);
  assert.match(result.error, /late npm failure/);
});

test('package manifests cannot redirect npm cleanup outside the owned command area', (t) => {
  const f = fixture(t);
  f.install('codex');
  const metadata = join(f.layout.modules, '@openai/codex/package.json');
  const pkg = JSON.parse(readFileSync(metadata, 'utf8'));
  pkg.bin = { '../keep.txt': 'bin/cli.js' };
  f.write(metadata, JSON.stringify(pkg));
  const original = tree(f.root);
  const result = uninstallAgent('codex', f.options);
  assert.equal(result.ok, false);
  assert.match(result.error, /Invalid installed package command name/);
  assert.deepEqual(f.npmCalls, []);
  assert.deepEqual(tree(f.root), original);
});

test('an external PATH installation survives removal and is explicitly reported', (t) => {
  const f = fixture(t);
  f.install('codex');
  const external = join(f.external, 'codex');
  f.write(external, '#!/bin/sh\nexit 0\n', true);
  const result = uninstallAgent('codex', f.options);
  assert.equal(result.ok, false);
  assert.equal(result.managedFilesRemain, false);
  assert.equal(result.commandPresent, true);
  assert.deepEqual(result.externalCommands, [external]);
  assert.match(result.error, /Other installations were preserved/);
  assert.equal(readFileSync(external, 'utf8'), '#!/bin/sh\nexit 0\n');
  f.assertRetained();
});

for (const replacement of ['symlink', 'file']) {
  test(`a foreign ${replacement} replacing an npm bin prevents npm from deleting it`, (t) => {
    const f = fixture(t);
    f.install('codex');
    const command = join(f.layout.bin, 'codex');
    rmSync(command);
    if (replacement === 'symlink') {
      const external = join(f.external, 'other-codex');
      f.write(external, '#!/bin/sh\nexit 0\n', true);
      symlinkSync(external, command);
    } else f.write(command, '#!/bin/sh\necho externally managed\n', true);
    const original = tree(f.root);
    const result = uninstallAgent('codex', f.options);
    assert.equal(result.ok, false);
    assert.match(result.error, /non-managed command/);
    assert.deepEqual(f.npmCalls, []);
    assert.deepEqual(tree(f.root), original);
  });
}

test('an unknown Pi wrapper is retained while its managed version directories are removed', (t) => {
  const f = fixture(t);
  f.install('pi');
  const command = join(f.layout.bin, 'omnibot-pi-acp');
  f.write(command, '#!/bin/sh\nexec node /custom/pi/index.js "$@"\n', true);
  const result = uninstallAgent('pi', f.options);
  assert.equal(result.ok, false);
  assert.equal(result.managedFilesRemain, false);
  assert.equal(result.commandPresent, true);
  assert.ok(result.externalCommands.includes(command));
  assert.ok(lstatSync(join(f.layout.systemBin, 'omnibot-pi-acp')).isSymbolicLink());
  assert.equal(readFileSync(command, 'utf8'), '#!/bin/sh\nexec node /custom/pi/index.js "$@"\n');
  f.assertRetained();
});

test('foreign /usr/local/bin links are preserved even when their command name matches', (t) => {
  const f = fixture(t);
  f.install('kimi-code');
  const command = join(f.layout.systemBin, 'kimi');
  rmSync(command);
  const external = join(f.external, 'user-kimi');
  f.write(external, '#!/bin/sh\nexit 0\n', true);
  symlinkSync(external, command);
  const result = uninstallAgent('kimi-code', f.options);
  assert.equal(result.ok, false);
  assert.equal(result.managedFilesRemain, false);
  assert.equal(readlinkSync(command), external);
  f.assertRetained();
});

for (const redirect of ['scope', 'package', 'pi-versions', 'bin']) {
  test(`redirected ${redirect} directory is rejected before any mutation`, (t) => {
    const f = fixture(t);
    const strategy = redirect === 'pi-versions' ? 'pi' : 'codex';
    f.install(strategy);
    const target = redirect === 'scope' ? join(f.layout.modules, '@openai')
      : redirect === 'package' ? join(f.layout.modules, '@openai/codex')
      : redirect === 'pi-versions' ? f.layout.piVersions : f.layout.bin;
    rmSync(target, { recursive: true, force: true });
    const external = join(f.root, `redirect-${redirect}`);
    f.write(join(external, 'keep.txt'), 'foreign');
    symlinkSync(external, target);
    const original = tree(f.root);
    const result = uninstallAgent(strategy, f.options);
    assert.equal(result.ok, false);
    assert.equal(result.managedFilesRemain, true);
    assert.match(result.error, /redirected installation directory/);
    assert.deepEqual(f.npmCalls, []);
    assert.deepEqual(tree(f.root), original);
  });
}

test('recursive Pi cleanup does not follow symlinks inside its owned version directory', (t) => {
  const f = fixture(t);
  f.install('pi');
  const external = join(f.root, 'user-files');
  f.write(join(external, 'keep.txt'), 'must survive');
  symlinkSync(external, join(f.layout.piVersions, 'a'.repeat(20), 'external'));
  const result = uninstallAgent('pi', f.options);
  assert.equal(result.ok, true, result.error);
  assert.equal(readFileSync(join(external, 'keep.txt'), 'utf8'), 'must survive');
});

test('partial package directories and dangling managed aliases can be cleaned', (t) => {
  const f = fixture(t);
  mkdirSync(join(f.layout.modules, '@opencode/cli'), { recursive: true });
  symlinkSync('../lib/node_modules/@opencode/cli/bin/opencode.exe', join(f.layout.bin, 'opencode'));
  symlinkSync(join(f.layout.bin, 'opencode'), join(f.layout.systemBin, 'opencode'));
  assert.equal(uninstallAgent('opencode', f.options).ok, true);
  f.assertRetained();
});

test('unknown strategies and unexpected CLI arguments fail without selecting another Agent', (t) => {
  const f = fixture(t);
  f.install('pi');
  const original = tree(f.root);
  assert.equal(uninstallAgent('custom-command', f.options).ok, false);
  assert.deepEqual(tree(f.root), original);
  for (const args of [['unknown-agent'], ['pi', '--path', f.root]]) {
    const result = spawnSync(process.execPath, ['-e', asset, '--', ...args], { encoding: 'utf8', timeout: 5000 });
    assert.equal(result.status, 1, result.stderr);
    const lines = result.stdout.trim().split('\n');
    assert.equal(lines.length, 1);
    assert.ok(lines[0].startsWith('__OMNI_AGENT_UNINSTALL__'));
    assert.equal(JSON.parse(lines[0].slice('__OMNI_AGENT_UNINSTALL__'.length)).ok, false);
  }
});
