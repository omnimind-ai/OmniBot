'use strict';

const childProcess = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const WORKSPACE_ROOT = '/workspace';
const SKILLS_ROOT = '/workspace/.omnibot/skills';
const SAFE_PATH = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin';
const MAX_REPOSITORY_FILES = 10000;
const MAX_REPOSITORY_BYTES = 100 * 1024 * 1024;
const MAX_FILE_BYTES = 25 * 1024 * 1024;
const MAX_SKILL_FILES = 2000;
const MAX_SKILL_BYTES = 25 * 1024 * 1024;
const MAX_SKILL_DEPTH = 10;
const MAX_PATH_BYTES = 512;
const MAX_GIT_OUTPUT_BYTES = 160 * 1024 * 1024;
const GIT_TIMEOUT_MS = 180000;

class StableFailure extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

function fail(code) {
  throw new StableFailure(code);
}

function parseRepository(source) {
  if (typeof source !== 'string' || source.length < 3 || source.length > 240) {
    fail('SKILL_INSTALL_SOURCE_INVALID');
  }
  let owner;
  let repo;
  if (source.startsWith('https://')) {
    let parsed;
    try {
      parsed = new URL(source);
    } catch (_) {
      fail('SKILL_INSTALL_SOURCE_INVALID');
    }
    if (
      parsed.protocol !== 'https:' ||
      parsed.hostname.toLowerCase() !== 'github.com' ||
      parsed.port !== '' ||
      parsed.username !== '' ||
      parsed.password !== '' ||
      parsed.search !== '' ||
      parsed.hash !== ''
    ) {
      fail('SKILL_INSTALL_SOURCE_INVALID');
    }
    const parts = parsed.pathname.split('/').filter(Boolean);
    if (parts.length !== 2) fail('SKILL_INSTALL_SOURCE_INVALID');
    owner = parts[0];
    repo = parts[1].replace(/\.git$/i, '');
  } else {
    const parts = source.split('/');
    if (parts.length !== 2) fail('SKILL_INSTALL_SOURCE_INVALID');
    [owner, repo] = parts;
    repo = repo.replace(/\.git$/i, '');
  }
  const component = /^[A-Za-z0-9](?:[A-Za-z0-9_.-]{0,99})$/;
  if (
    !component.test(owner) ||
    !component.test(repo) ||
    owner === '.' || owner === '..' || repo === '.' || repo === '..'
  ) {
    fail('SKILL_INSTALL_SOURCE_INVALID');
  }
  return {
    canonical: `${owner}/${repo}`,
    url: `https://github.com/${owner}/${repo}.git`,
  };
}

function parseArguments(argv) {
  if (argv.length < 1) fail('SKILL_INSTALL_USAGE');
  const repository = parseRepository(argv[0]);
  let commit = '';
  let skill = '';
  let confirmation = '';
  for (let index = 1; index < argv.length; index += 1) {
    const option = argv[index];
    if (!['--commit', '--skill', '--confirm-exact'].includes(option)) {
      fail('SKILL_INSTALL_USAGE');
    }
    if (index + 1 >= argv.length) fail('SKILL_INSTALL_USAGE');
    const value = argv[index + 1];
    index += 1;
    if (option === '--commit') {
      if (commit !== '') fail('SKILL_INSTALL_USAGE');
      commit = value.toLowerCase();
    } else if (option === '--skill') {
      if (skill !== '') fail('SKILL_INSTALL_USAGE');
      skill = value;
    } else {
      if (confirmation !== '') fail('SKILL_INSTALL_USAGE');
      confirmation = value;
    }
  }
  if (!/^[0-9a-f]{40}$/.test(commit)) fail('SKILL_INSTALL_COMMIT_REQUIRED');
  if (!/^[a-z0-9](?:[a-z0-9._-]{0,63})$/.test(skill)) {
    fail('SKILL_INSTALL_SKILL_ID_INVALID');
  }
  const expectedConfirmation = `${repository.canonical}@${commit}:${skill}`;
  if (
    confirmation.length !== expectedConfirmation.length ||
    !crypto.timingSafeEqual(Buffer.from(confirmation), Buffer.from(expectedConfirmation))
  ) {
    fail('SKILL_INSTALL_EXACT_CONFIRMATION_REQUIRED');
  }
  return { repository, commit, skill };
}

function safeGitEnvironment(tempHome) {
  return {
    HOME: tempHome,
    PATH: SAFE_PATH,
    LANG: 'C',
    LC_ALL: 'C',
    GIT_ASKPASS: '/bin/false',
    GIT_CONFIG_GLOBAL: '/dev/null',
    GIT_CONFIG_NOSYSTEM: '1',
    GIT_CONFIG_SYSTEM: '/dev/null',
    GIT_LFS_SKIP_SMUDGE: '1',
    GIT_TERMINAL_PROMPT: '0',
    XDG_CONFIG_HOME: tempHome,
  };
}

function runGit(repositoryDir, tempHome, args) {
  const result = childProcess.spawnSync(
    'git',
    [
      '-c', 'core.hooksPath=/dev/null',
      '-c', 'protocol.file.allow=never',
      '-c', 'protocol.ext.allow=never',
      '-c', 'fetch.fsckObjects=true',
      '-c', 'transfer.fsckObjects=true',
      '-c', 'submodule.recurse=false',
      ...args,
    ],
    {
      cwd: repositoryDir,
      env: safeGitEnvironment(tempHome),
      encoding: null,
      windowsHide: true,
      timeout: GIT_TIMEOUT_MS,
      maxBuffer: MAX_GIT_OUTPUT_BYTES,
    },
  );
  if (result.error || result.status !== 0 || result.signal) {
    fail('SKILL_INSTALL_GIT_FAILED');
  }
  return Buffer.isBuffer(result.stdout) ? result.stdout : Buffer.alloc(0);
}

function validateRelativePath(relativePath, seenNames) {
  if (
    relativePath.length === 0 ||
    Buffer.byteLength(relativePath, 'utf8') > MAX_PATH_BYTES ||
    relativePath.startsWith('/') ||
    relativePath.includes('\\') ||
    /[\u0000-\u001f\u007f]/u.test(relativePath)
  ) {
    fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
  }
  const parts = relativePath.split('/');
  if (
    parts.some((part) =>
      part === '' || part === '.' || part === '..' ||
      part.length > 120 || part.toLowerCase() === '.git'
    )
  ) {
    fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
  }
  const collisionKey = relativePath.normalize('NFC').toLowerCase();
  if (seenNames.has(collisionKey)) fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
  seenNames.add(collisionKey);
}

function parseTree(treeBuffer) {
  const decoded = treeBuffer.toString('utf8');
  if (!Buffer.from(decoded, 'utf8').equals(treeBuffer)) {
    fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
  }
  const records = decoded.split('\0');
  if (records.pop() !== '') fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
  if (records.length === 0 || records.length > MAX_REPOSITORY_FILES) {
    fail('SKILL_INSTALL_REPOSITORY_LIMIT');
  }
  const seenNames = new Set();
  let totalBytes = 0;
  const entries = records.map((record) => {
    const match = /^([0-7]{6}) (blob|commit) ([0-9a-f]{40,64})\s+([0-9-]+)\t([\s\S]+)$/u.exec(record);
    if (!match || match[2] !== 'blob' || !['100644', '100755'].includes(match[1])) {
      fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
    }
    const size = Number(match[4]);
    if (!Number.isSafeInteger(size) || size < 0 || size > MAX_FILE_BYTES) {
      fail('SKILL_INSTALL_REPOSITORY_LIMIT');
    }
    const relativePath = match[5];
    validateRelativePath(relativePath, seenNames);
    totalBytes += size;
    if (totalBytes > MAX_REPOSITORY_BYTES) fail('SKILL_INSTALL_REPOSITORY_LIMIT');
    return { mode: match[1], relativePath, size };
  });
  return entries;
}

function assertOwnedDirectory(directory, expectedParent) {
  const stat = fs.lstatSync(directory);
  if (!stat.isDirectory() || stat.isSymbolicLink()) fail('SKILL_INSTALL_TARGET_UNSAFE');
  if (typeof process.getuid === 'function' && stat.uid !== process.getuid()) {
    fail('SKILL_INSTALL_TARGET_UNSAFE');
  }
  const resolved = fs.realpathSync(directory);
  if (expectedParent && path.dirname(resolved) !== expectedParent) {
    fail('SKILL_INSTALL_TARGET_UNSAFE');
  }
  return resolved;
}

function prepareSkillsRoot() {
  const workspace = fs.realpathSync(WORKSPACE_ROOT);
  if (workspace !== WORKSPACE_ROOT) fail('SKILL_INSTALL_TARGET_UNSAFE');
  const omnibotRoot = path.join(workspace, '.omnibot');
  const skillsRoot = path.join(omnibotRoot, 'skills');
  fs.mkdirSync(omnibotRoot, { mode: 0o700 });
  assertOwnedDirectory(omnibotRoot, workspace);
  fs.chmodSync(omnibotRoot, 0o700);
  fs.mkdirSync(skillsRoot, { mode: 0o700 });
  assertOwnedDirectory(skillsRoot, omnibotRoot);
  fs.chmodSync(skillsRoot, 0o700);
  if (fs.realpathSync(skillsRoot) !== SKILLS_ROOT) fail('SKILL_INSTALL_TARGET_UNSAFE');
  return skillsRoot;
}

function verifyCheckout(repositoryDir, entries) {
  const expected = new Map(entries.map((entry) => [entry.relativePath, entry]));
  const actual = new Set();
  const walk = (directory, relativeBase) => {
    const directoryEntries = fs.readdirSync(directory, { withFileTypes: true });
    for (const directoryEntry of directoryEntries) {
      if (relativeBase === '' && directoryEntry.name === '.git') continue;
      const relativePath = relativeBase === ''
        ? directoryEntry.name
        : `${relativeBase}/${directoryEntry.name}`;
      const absolutePath = path.join(directory, directoryEntry.name);
      const stat = fs.lstatSync(absolutePath);
      if (stat.isSymbolicLink()) fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
      if (stat.isDirectory()) {
        walk(absolutePath, relativePath);
      } else if (stat.isFile() && stat.nlink === 1) {
        const expectedEntry = expected.get(relativePath);
        if (!expectedEntry || stat.size !== expectedEntry.size) {
          fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
        }
        actual.add(relativePath);
      } else {
        fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
      }
    }
  };
  walk(repositoryDir, '');
  if (actual.size !== expected.size) fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
  for (const relativePath of expected.keys()) {
    if (!actual.has(relativePath)) fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
  }
}

function selectSkill(entries, skillId) {
  const matches = entries.filter((entry) => {
    if (path.posix.basename(entry.relativePath) !== 'SKILL.md') return false;
    const directory = path.posix.dirname(entry.relativePath);
    const depth = directory === '.' ? 0 : directory.split('/').length;
    return depth <= 4 && path.posix.basename(directory) === skillId;
  });
  if (matches.length !== 1) fail('SKILL_INSTALL_SKILL_NOT_UNIQUE');
  const skillDirectory = path.posix.dirname(matches[0].relativePath);
  const prefix = skillDirectory === '.' ? '' : `${skillDirectory}/`;
  const selected = entries.filter((entry) =>
    prefix === '' || entry.relativePath.startsWith(prefix)
  ).map((entry) => ({
    ...entry,
    installPath: prefix === '' ? entry.relativePath : entry.relativePath.slice(prefix.length),
  }));
  let totalBytes = 0;
  if (selected.length === 0 || selected.length > MAX_SKILL_FILES) {
    fail('SKILL_INSTALL_SKILL_LIMIT');
  }
  for (const entry of selected) {
    totalBytes += entry.size;
    if (totalBytes > MAX_SKILL_BYTES) fail('SKILL_INSTALL_SKILL_LIMIT');
    if (entry.installPath.split('/').length > MAX_SKILL_DEPTH) {
      fail('SKILL_INSTALL_SKILL_LIMIT');
    }
  }
  return { skillDirectory, selected };
}

function validateSkillDocument(repositoryDir, skillDirectory) {
  const skillDocument = path.join(repositoryDir, skillDirectory, 'SKILL.md');
  const bytes = fs.readFileSync(skillDocument);
  if (bytes.length === 0 || bytes.length > 1024 * 1024 || bytes.includes(0)) {
    fail('SKILL_INSTALL_SKILL_INVALID');
  }
  const text = bytes.toString('utf8');
  if (!Buffer.from(text, 'utf8').equals(bytes) || !text.startsWith('---\n')) {
    fail('SKILL_INSTALL_SKILL_INVALID');
  }
}

function fsyncFile(filePath) {
  const descriptor = fs.openSync(filePath, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    fs.fsyncSync(descriptor);
  } finally {
    fs.closeSync(descriptor);
  }
}

function copySkill(repositoryDir, skillDirectory, selected, skillsRoot, args) {
  const target = path.join(skillsRoot, args.skill);
  try {
    fs.lstatSync(target);
    fail('SKILL_INSTALL_TARGET_EXISTS');
  } catch (error) {
    if (error instanceof StableFailure) throw error;
    if (!error || error.code !== 'ENOENT') fail('SKILL_INSTALL_TARGET_UNSAFE');
  }
  const staging = fs.mkdtempSync(path.join(skillsRoot, '.incoming-'));
  let committed = false;
  try {
    assertOwnedDirectory(staging, skillsRoot);
    fs.chmodSync(staging, 0o700);
    for (const entry of selected) {
      const source = path.join(repositoryDir, skillDirectory, entry.installPath);
      const destination = path.join(staging, entry.installPath);
      const sourceStat = fs.lstatSync(source);
      if (!sourceStat.isFile() || sourceStat.isSymbolicLink() || sourceStat.nlink !== 1) {
        fail('SKILL_INSTALL_REPOSITORY_UNSAFE');
      }
      fs.mkdirSync(path.dirname(destination), { recursive: true, mode: 0o700 });
      fs.copyFileSync(source, destination, fs.constants.COPYFILE_EXCL);
      fs.chmodSync(destination, entry.mode === '100755' ? 0o700 : 0o600);
      fsyncFile(destination);
    }
    const metadataPath = path.join(staging, '.omnibot-source.json');
    const metadata = JSON.stringify({
      schemaVersion: 1,
      repository: args.repository.canonical,
      commit: args.commit,
      skill: args.skill,
    }) + '\n';
    const descriptor = fs.openSync(
      metadataPath,
      fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | fs.constants.O_NOFOLLOW,
      0o600,
    );
    try {
      fs.writeFileSync(descriptor, metadata, { encoding: 'utf8' });
      fs.fsyncSync(descriptor);
    } finally {
      fs.closeSync(descriptor);
    }
    const stagingDescriptor = fs.openSync(staging, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    try {
      fs.fsyncSync(stagingDescriptor);
    } finally {
      fs.closeSync(stagingDescriptor);
    }
    fs.renameSync(staging, target);
    committed = true;
    const rootDescriptor = fs.openSync(skillsRoot, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
    try {
      fs.fsyncSync(rootDescriptor);
    } finally {
      fs.closeSync(rootDescriptor);
    }
  } finally {
    if (!committed) guardedRemove(staging, skillsRoot, '.incoming-');
  }
}

function guardedRemove(target, parent, prefix) {
  if (!target || path.dirname(target) !== parent || !path.basename(target).startsWith(prefix)) {
    fail('SKILL_INSTALL_CLEANUP_REFUSED');
  }
  try {
    fs.rmSync(target, { recursive: true, force: true, maxRetries: 0 });
  } catch (_) {
    fail('SKILL_INSTALL_CLEANUP_FAILED');
  }
}

function install(argv) {
  if (Number(process.versions.node.split('.')[0]) < 22) {
    fail('SKILL_INSTALL_RUNTIME_UNAVAILABLE');
  }
  const args = parseArguments(argv);
  const skillsRoot = prepareSkillsRoot();
  const tempParent = fs.realpathSync(os.tmpdir());
  const tempRoot = fs.mkdtempSync(path.join(tempParent, 'omnibot-skill-'));
  try {
    assertOwnedDirectory(tempRoot, tempParent);
    fs.chmodSync(tempRoot, 0o700);
    const tempHome = path.join(tempRoot, 'home');
    const repositoryDir = path.join(tempRoot, 'repository');
    fs.mkdirSync(tempHome, { mode: 0o700 });
    fs.mkdirSync(repositoryDir, { mode: 0o700 });
    runGit(repositoryDir, tempHome, ['init', '--quiet']);
    runGit(repositoryDir, tempHome, ['remote', 'add', 'origin', args.repository.url]);
    runGit(repositoryDir, tempHome, [
      'fetch', '--quiet', '--no-tags', '--depth=1', '--filter=blob:limit=26214401',
      'origin', args.commit,
    ]);
    const fetched = runGit(repositoryDir, tempHome, ['rev-parse', '--verify', 'FETCH_HEAD^{commit}'])
      .toString('utf8').trim().toLowerCase();
    if (fetched !== args.commit) fail('SKILL_INSTALL_COMMIT_MISMATCH');
    const entries = parseTree(runGit(repositoryDir, tempHome, [
      'ls-tree', '-rlz', '--full-tree', args.commit,
    ]));
    const selection = selectSkill(entries, args.skill);
    runGit(repositoryDir, tempHome, ['checkout', '--quiet', '--detach', '--force', args.commit]);
    verifyCheckout(repositoryDir, entries);
    validateSkillDocument(repositoryDir, selection.skillDirectory);
    copySkill(
      repositoryDir,
      selection.skillDirectory,
      selection.selected,
      skillsRoot,
      args,
    );
  } finally {
    guardedRemove(tempRoot, tempParent, 'omnibot-skill-');
  }
  process.stdout.write(`SKILL_INSTALL_OK ${args.skill}\n`);
}

if (require.main === module) {
  try {
    install(process.argv.slice(2));
  } catch (error) {
    const code = error instanceof StableFailure
      ? error.code
      : 'SKILL_INSTALL_FAILED';
    process.stderr.write(`${code}\n`);
    process.exitCode = 1;
  }
}

module.exports = { parseArguments, parseRepository, parseTree, selectSkill };
