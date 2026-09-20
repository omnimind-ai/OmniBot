"""Consistent read-only snapshots for synthetic Android history assertions."""
import os, re, shlex, sqlite3, subprocess, tempfile, uuid
from pathlib import Path
from contextlib import contextmanager

@contextmanager
def agent_database_snapshot(serial, database_relative='databases/omnibot_cache_databaseoss'):
    if database_relative != 'databases/omnibot_cache_databaseoss' and not re.fullmatch(
            r'files/plugin-data/local\.project\.[A-Za-z0-9_-]+/project\.db', database_relative):
        raise ValueError('Only conversation or local project databases may be observed')
    release_read = os.environ.get('OOB_TEST_RELEASE_READ') == '1'
    if release_read:
        if not re.fullmatch(r'emulator-\d+', serial):
            raise ValueError('Release root observation is restricted to an explicit emulator')
        uid = subprocess.check_output(['adb', '-s', serial, 'shell', 'id', '-u'],
                                      text=True, timeout=10).strip()
        if uid != '0':
            raise RuntimeError('Release observation requires an already rooted test emulator; no automatic escalation')
    with tempfile.TemporaryDirectory(prefix='oob-checkpoint-') as directory:
        path = Path(directory) / 'history.db'
        # SQLite's online backup API owns a consistent read snapshot. Copying the
        # live DB and WAL separately can combine two different checkpoint epochs.
        remote = f"cache/oob-checkpoint-{uuid.uuid4().hex}.db"
        owner = ['run-as', 'cn.com.omnimind.bot']
        database = database_relative
        read_flags = []
        if release_read:
            owner = []
            remote = '/data/local/tmp/' + Path(remote).name
            database = '/data/user/0/cn.com.omnimind.bot/' + database
            read_flags = ['-readonly']
        try:
            command = shlex.join(owner + [os.environ.get('OOB_ANDROID_SQLITE', 'sqlite3')] + read_flags +
                [database, f".backup '{remote}'"])
            if release_read:
                command = 'umask 077; ' + command
            subprocess.run(['adb', '-s', serial, 'shell', command], check=True, capture_output=True, timeout=30)
            result = subprocess.run(['adb', '-s', serial, 'exec-out'] + owner +
                ['cat', remote], check=True, capture_output=True, timeout=30)
            path.write_bytes(result.stdout)
        finally:
            subprocess.run(['adb', '-s', serial, 'shell'] + owner +
                ['rm', '-f', remote], check=True, capture_output=True, timeout=10)
        with sqlite3.connect(path) as db:
            yield db
