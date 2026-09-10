import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, writeFileSync, rmSync, readFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawnSync} from 'node:child_process';

test('bundled ACP diagnostic reports missing runtimes without invoking installers', () => {
  const directory = mkdtempSync(join(tmpdir(), 'acp diagnostic '));
  try {
    for (const name of ['uname', 'cat']) {
      writeFileSync(join(directory, name), '#!/bin/sh\nexec /usr/bin/' + name + ' "$@"\n', {mode: 0o755});
    }
    writeFileSync(join(directory, 'apk'), '#!/bin/sh\nexit 99\n', {mode: 0o755});
    const manifest = JSON.parse(readFileSync('app/src/main/assets/builtin_skills/manifest.json', 'utf8'));
    const entry = manifest.skills.find(skill => skill.id === 'install-acp-agent');
    assert.ok(entry);
    const script = resolve('app/src/main/assets', entry.assetPath, 'scripts/inspect-runtime.sh');
    for (let attempt = 0; attempt < 2; attempt++) {
      const result = spawnSync('/bin/sh', [script], {encoding: 'utf8', env: {PATH: directory}});
      assert.equal(result.status, 0, result.stderr);
      assert.match(result.stdout, /apk=available/);
      assert.match(result.stdout, /node=missing/);
      assert.match(result.stdout, /npm=missing/);
    }
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
});
