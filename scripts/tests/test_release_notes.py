"""Run with python3 -m unittest discover -s scripts/tests -p test_release_notes.py."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / '.github/workflows/release.yml').read_text()


class ReleaseNotesTest(unittest.TestCase):
    def test_android_setup_does_not_request_removed_tools_package(self):
        setup = WORKFLOW.split("uses: android-actions/setup-android@v3", 1)[1].split("      - name:", 1)[0]
        self.assertIn("packages: platform-tools", setup)

    def payload(self, notes):
        block = WORKFLOW.split("<<'PY' > \"${payload_file}\"\n", 1)[1].split('\n          PY', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / 'assets'
            artifact.mkdir()
            apk = artifact / 'OpenOmniBot-v0.6.3.1-standard.apk'
            apk.write_bytes(b'test fixture')
            Path(str(apk) + '.sha256').write_text('abc123 fixture.apk')
            if notes is not None:
                (root / 'docs/releases').mkdir(parents=True)
                (root / 'docs/releases/0.6.3.1.md').write_text(notes)
            env = dict(os.environ, RELEASE_TAG_NAME='v0.6.3.1', SAFE_REF_NAME='v0.6.3.1',
                       GITHUB_REPOSITORY='example/repo', RELEASE_ASSET_DIR=str(artifact),
                       RELEASE_TRACK='beta', RELEASE_DRAFT='false', RELEASE_PRERELEASE='true')
            result = subprocess.run(['python3', '-c', textwrap.dedent(block)], cwd=root,
                                    env=env, text=True, capture_output=True, check=True)
            return json.loads(result.stdout)

    def test_curated_notes_reach_update_payload(self):
        notes = (ROOT / 'docs/releases/0.6.3.1.md').read_text()
        payload = self.payload(notes)
        self.assertEqual(payload['releaseNotes'], notes.strip())
        self.assertEqual(payload['track'], 'beta')
        self.assertTrue(payload['prerelease'])
        self.assertFalse(payload['draft'])

    def test_older_release_preserves_server_notes(self):
        self.assertNotIn('releaseNotes', self.payload(None))


if __name__ == '__main__':
    unittest.main()
