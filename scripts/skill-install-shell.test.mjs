import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawnSync} from 'node:child_process';

const script = resolve('app/src/main/assets/builtin_skills/find-install-skills/scripts/install_with_skills_cli.sh');
function fixture(run) {
  const dir = mkdtempSync(join(tmpdir(), 'oob skill install '));
  const source = join(dir, 'source'), target = join(dir, 'target'), bin = join(dir, 'bin');
  for (const path of [source, target, bin]) mkdirSync(path);
  writeFileSync(join(bin, 'git'), '#!/bin/sh\nprintf "%s" "$4" > "$TEST_CLONE_URL"\ncp -R "$TEST_SKILL_SOURCE" "$5"\n', {mode: 0o755});
  const add = name => { mkdirSync(join(source, name), {recursive:true}); writeFileSync(join(source, name, 'SKILL.md'), '# fixture'); };
  const install = sourceArg => spawnSync('/bin/sh', [script, sourceArg], {encoding:'utf8', env:{...process.env,
    PATH:bin + ':' + process.env.PATH, TEST_CLONE_URL:join(dir, 'url'), TEST_SKILL_SOURCE:source,
    OMNIBOT_SKILLS_ROOT:target, TMPDIR:dir}});
  try { run({dir, source, target, add, install}); } finally { rmSync(dir, {recursive:true, force:true}); }
}

test('skill installation preserves paths containing spaces and refuses overwrite', () => fixture(({target, add, install}) => {
  add('a skill');
  const first = install('owner/repo');
  assert.equal(first.status, 0, first.stdout + first.stderr);
  assert.equal(readFileSync(join(target, 'a skill', 'SKILL.md'), 'utf8'), '# fixture');
  assert.notEqual(install('owner/repo').status, 0);
  assert.equal(readFileSync(join(target, 'a skill', 'SKILL.md'), 'utf8'), '# fixture');
}));

test('SSH clone URL is not mistaken for owner/repo@skill shorthand', () => fixture(({dir, add, install}) => {
  add('sample');
  const result = install('git@github.com:owner/repo.git');
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.equal(readFileSync(join(dir, 'url'), 'utf8'), 'git@github.com:owner/repo');
}));

test('duplicate skill IDs fail before any destination is copied', () => fixture(({target, add, install}) => {
  add('a/sample'); add('b/sample');
  assert.notEqual(install('owner/repo').status, 0);
  assert.deepEqual(readdirSync(target), []);
}));
