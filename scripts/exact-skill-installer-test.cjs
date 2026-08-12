'use strict';

const assert = require('node:assert/strict');
const path = require('node:path');

const installer = require(path.resolve(
  __dirname,
  '../app/src/main/assets/builtin_skills/find-install-skills/scripts/install_exact_skill.cjs',
));

function expectStableFailure(callback, expectedCode) {
  assert.throws(callback, (error) => error && error.code === expectedCode);
}

const commit = '0123456789abcdef0123456789abcdef01234567';
const confirmation = `owner/repository@${commit}:demo-skill`;
assert.deepEqual(
  installer.parseArguments([
    'https://github.com/owner/repository.git',
    '--commit', commit,
    '--skill', 'demo-skill',
    '--confirm-exact', confirmation,
  ]),
  {
    repository: {
      canonical: 'owner/repository',
      url: 'https://github.com/owner/repository.git',
    },
    commit,
    skill: 'demo-skill',
  },
);

for (const source of [
  'git@github.com:owner/repository',
  'http://github.com/owner/repository',
  'https://user@github.com/owner/repository',
  'https://github.com/owner/repository?ref=main',
  'https://github.com/owner/repository/extra',
  'https://example.invalid/owner/repository',
]) {
  expectStableFailure(
    () => installer.parseRepository(source),
    'SKILL_INSTALL_SOURCE_INVALID',
  );
}

expectStableFailure(
  () => installer.parseArguments([
    'owner/repository', '--commit', '0123456', '--skill', 'demo-skill',
    '--confirm-exact', confirmation,
  ]),
  'SKILL_INSTALL_COMMIT_REQUIRED',
);
expectStableFailure(
  () => installer.parseArguments([
    'owner/repository', '--commit', commit, '--skill', 'demo-skill',
    '--confirm-exact', `${confirmation}-changed`,
  ]),
  'SKILL_INSTALL_EXACT_CONFIRMATION_REQUIRED',
);

const objectId = 'a'.repeat(40);
const tree = Buffer.from(
  `100644 blob ${objectId}      20\tskills/demo-skill/SKILL.md\0` +
  `100755 blob ${objectId}      12\tskills/demo-skill/scripts/run.sh\0`,
  'utf8',
);
const entries = installer.parseTree(tree);
const selected = installer.selectSkill(entries, 'demo-skill');
assert.equal(selected.skillDirectory, 'skills/demo-skill');
assert.deepEqual(
  selected.selected.map((entry) => entry.installPath),
  ['SKILL.md', 'scripts/run.sh'],
);

expectStableFailure(
  () => installer.parseTree(Buffer.from(
    `120000 blob ${objectId}       7\tskills/demo-skill/link\0`,
    'utf8',
  )),
  'SKILL_INSTALL_REPOSITORY_UNSAFE',
);
expectStableFailure(
  () => installer.parseTree(Buffer.from(
    `100644 blob ${objectId}       1\t../escape\0`,
    'utf8',
  )),
  'SKILL_INSTALL_REPOSITORY_UNSAFE',
);
expectStableFailure(
  () => installer.parseTree(Buffer.from(
    `100644 blob ${objectId}       1\tSkill/File\0` +
    `100644 blob ${objectId}       1\tskill/file\0`,
    'utf8',
  )),
  'SKILL_INSTALL_REPOSITORY_UNSAFE',
);
expectStableFailure(
  () => installer.selectSkill([
    { mode: '100644', relativePath: 'a/demo-skill/SKILL.md', size: 1 },
    { mode: '100644', relativePath: 'b/demo-skill/SKILL.md', size: 1 },
  ], 'demo-skill'),
  'SKILL_INSTALL_SKILL_NOT_UNIQUE',
);

process.stdout.write('exact skill installer tests: PASS\n');
