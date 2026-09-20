#!/usr/bin/env python3
"""Execute production bootstrap with a process fixture; not Android acceptance."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'ReTerminal/core/main/src/main/assets/init-host.sh'

class BootstrapCases:
    distribution = "ubuntu"
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.native = self.root / 'native'
        self.native.mkdir()
        self.loader = self.native / 'libproot-loader.so'
        self.loader.write_text('fixture')
        self.loader.chmod(0o755)
        self.guest = self.root / ('local/' + self.distribution)
        self.guest.mkdir(parents=True)
        (self.guest / 'partial').write_text('preserve until execution works')
        (self.root / 'files').mkdir()
        (self.root / ('files/' + self.distribution + '.tar.gz')).touch()
        self.linker = self.root / 'linker'
        self.linker.write_text('#!' + sys.executable + '\n' + '''
import os, pathlib, sys
root = pathlib.Path(os.environ['PREFIX'])
with (root / 'calls').open('a') as f: f.write(' '.join(sys.argv[1:]) + '\\n')
assert os.environ['PROOT_LOADER'] == str(root / 'native/libproot-loader.so')
if '--help' in sys.argv:
    if os.environ.get('FAIL_BOOT'):
        print('proot error: execve("/system/bin/tar"): Permission denied', file=sys.stderr)
        sys.exit(13)
    sys.exit(0)
if '-xf' in sys.argv:
    if os.environ.get('FAIL_EXTRACT'):
        print('tar: truncated archive', file=sys.stderr)
        sys.exit(2)
    distro = os.environ['OMNIBOT_TERMINAL_DISTRIBUTION']
    members = ['bin/sh', 'etc/os-release', 'usr/bin/env']
    members += (['usr/bin/apt-get', 'var/lib/dpkg/status'] if distro == 'ubuntu'
                else ['sbin/apk', 'lib/apk/db/installed', 'etc/alpine-release'])
    if os.environ.get('INCOMPLETE_EXTRACT'): members = ['bin/sh']
    for name in members:
        p = root / 'local' / distro / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.touch()
sys.exit(0)
''')
        self.linker.chmod(0o755)
        self.env = {**os.environ, 'PREFIX': str(self.root), 'LINKER': str(self.linker),
                    'NATIVE_LIB_DIR': str(self.native), 'PROOT_LOADER': '/stale/previous-apk/loader',
                    'OMNIBOT_TERMINAL_DISTRIBUTION': self.distribution, 'OMNIBOT_HOST_WORKSPACE': '',
                    'OMNIBOT_MT_STORAGE_HOST': ''}

    def run_host(self, **extra):
        return subprocess.run(['/bin/sh', str(SCRIPT)], env={**self.env, **extra},
                              capture_output=True, text=True, timeout=10)

    def test_missing_loader_preserves_partial_files(self):
        self.loader.unlink()
        result = self.run_host()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('loader missing', result.stderr)
        self.assertTrue((self.guest / 'partial').exists())
        self.assertFalse((self.root / 'calls').exists())

    def test_exec_failure_keeps_errno_and_does_not_extract(self):
        result = self.run_host(FAIL_BOOT='1')
        self.assertEqual(result.returncode, 13)
        self.assertIn('Permission denied', result.stderr)
        self.assertIn('cannot start Android tar', result.stderr)
        self.assertTrue((self.guest / 'partial').exists())
        self.assertNotIn('-xf', (self.root / 'calls').read_text())
        self.assertFalse((self.guest / '.omnibot-rootfs-ready').exists())

    def test_archive_failure_never_marks_ready_and_next_launch_recovers(self):
        result = self.run_host(FAIL_EXTRACT='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('truncated archive', result.stderr)
        self.assertFalse((self.guest / '.omnibot-rootfs-ready').exists())
        result = self.run_host()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.guest / '.omnibot-rootfs-ready').exists())

    def test_current_apk_loader_replaces_stale_override_and_restart_skips_extract(self):
        result = self.run_host()
        self.assertEqual(result.returncode, 0, result.stderr)
        history = self.guest / 'root/keep.txt'
        history.parent.mkdir(exist_ok=True)
        history.write_text('user workspace')
        (self.root / 'calls').write_text('')
        result = self.run_host(FAIL_BOOT='1', FAIL_EXTRACT='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(history.read_text(), 'user workspace')
        calls = (self.root / 'calls').read_text()
        self.assertNotIn('--help', calls)
        self.assertNotIn('-xf', calls)

    def test_missing_archive_preserves_partial_installation(self):
        (self.root / ('files/' + self.distribution + '.tar.gz')).unlink()
        result = self.run_host()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Missing ' + self.distribution + ' rootfs archive', result.stderr)
        self.assertTrue((self.guest / 'partial').exists())
        self.assertFalse((self.root / 'calls').exists())

    def test_broken_ready_marker_checks_execution_before_cleanup(self):
        (self.guest / '.omnibot-rootfs-ready').touch()
        result = self.run_host(FAIL_BOOT='1')
        self.assertEqual(result.returncode, 13)
        self.assertTrue((self.guest / 'partial').exists())
        self.assertNotIn('-xf', (self.root / 'calls').read_text())

    def test_incomplete_extraction_cannot_be_marked_ready(self):
        result = self.run_host(INCOMPLETE_EXTRACT='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('rootfs is incomplete', result.stderr)
        self.assertFalse((self.guest / '.omnibot-rootfs-ready').exists())

    def test_legacy_complete_installation_skips_extraction(self):
        self.assertEqual(self.run_host().returncode, 0)
        (self.guest / '.omnibot-rootfs-ready').unlink()
        (self.root / 'calls').write_text('')
        result = self.run_host(FAIL_BOOT='1', FAIL_EXTRACT='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.guest / '.omnibot-rootfs-ready').exists())
        self.assertNotIn('-xf', (self.root / 'calls').read_text())

class UbuntuBootstrapTest(BootstrapCases, unittest.TestCase):
    distribution = 'ubuntu'

class AlpineBootstrapTest(BootstrapCases, unittest.TestCase):
    distribution = 'alpine'

if __name__ == '__main__':
    unittest.main()
