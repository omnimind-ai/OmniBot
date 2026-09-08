"""Consistent read-only snapshots for synthetic Android history assertions."""
import shlex, sqlite3, subprocess, tempfile, uuid
from pathlib import Path
from contextlib import contextmanager

@contextmanager
def agent_database_snapshot(serial):
    with tempfile.TemporaryDirectory(prefix='oob-checkpoint-') as directory:
        path = Path(directory) / 'history.db'
        # SQLite's online backup API owns a consistent read snapshot. Copying the
        # live DB and WAL separately can combine two different checkpoint epochs.
        remote = f"cache/oob-checkpoint-{uuid.uuid4().hex}.db"
        try:
            command = shlex.join(['run-as', 'cn.com.omnimind.bot', 'sqlite3',
                'databases/omnibot_cache_databaseoss', f".backup '{remote}'"])
            subprocess.run(['adb', '-s', serial, 'shell', command], check=True, capture_output=True, timeout=30)
            result = subprocess.run(['adb', '-s', serial, 'exec-out', 'run-as',
                'cn.com.omnimind.bot', 'cat', remote], check=True, capture_output=True, timeout=30)
            path.write_bytes(result.stdout)
        finally:
            subprocess.run(['adb', '-s', serial, 'shell', 'run-as', 'cn.com.omnimind.bot',
                'rm', '-f', remote], check=True, capture_output=True, timeout=10)
        with sqlite3.connect(path) as db:
            yield db
