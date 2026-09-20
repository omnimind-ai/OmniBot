"""Live emulator regression using existing debug entry points and real UI taps.

OOB_GUI_SERIAL=emulator-5580 OOB_GUI_OUTPUT=/tmp/gui-evidence \
  python3 -m unittest discover -s scripts -p test_omniflow_device_lifecycle.py -v

Requires a configured GUI model, enabled accessibility and an idle debug App.
Never clears App data or replaces the Planner/Transfer. Model calls are real.
The Settings fixture requires English system labels. A failed assertion is a
failed acceptance, even when a partial RunLog says succeeded.
"""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import time
import unittest
import uuid
import xml.etree.ElementTree as ET


@unittest.skipUnless(os.environ.get('OOB_GUI_SERIAL'), 'Live emulator not selected')
class OmniFlowDeviceLifecycle(unittest.TestCase):
    package = 'cn.com.omnimind.bot'

    @classmethod
    def setUpClass(cls):
        cls.serial = os.environ['OOB_GUI_SERIAL']
        if not re.fullmatch(r'emulator-\d+', cls.serial):
            raise ValueError('Use an isolated emulator for this mutable regression')
        cls.output = Path(os.environ['OOB_GUI_OUTPUT'])
        cls.output.mkdir(parents=True, exist_ok=True)
        cls.adb_path = os.environ.get('ADB', 'adb')
        cls.recorded_run = None
        cls.registered_ids = []

    def adb(self, *args, check=True):
        return subprocess.run([self.adb_path, '-s', self.serial, *args],
                              capture_output=True, timeout=30, check=check).stdout

    def persist(self, name, value):
        (self.output / name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
        return value

    def read(self, path):
        raw = self.adb('shell', 'run-as', self.package, 'cat', path, check=False)
        try:
            return json.loads(raw)
        except (ValueError, UnicodeError):
            return None

    def wait(self, read, predicate=lambda value: value is not None, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            value = read()
            if predicate(value):
                return value
            time.sleep(.3)
        self.fail(f'Acceptance deadline exceeded ({seconds}s); no success inferred')

    def request(self, receiver, result, **values):
        path = 'files/' + result
        self.adb('shell', 'run-as', self.package, 'rm', '-f', path)
        args = ['shell', 'am', 'broadcast', '-n', self.package + '/.debug.' + receiver]
        for key, value in values.items():
            # adb shell joins its arguments; base64 carries all free text.
            if not re.fullmatch(r'[A-Za-z0-9_./=+:-]+', str(value)):
                raise ValueError('Debug request requires safe/base64 arguments')
            args.extend(['--es', key, str(value)])
        self.adb(*args)
        return path

    def tool(self, name, **arguments):
        encoded = base64.b64encode(json.dumps(arguments).encode()).decode()
        path = self.request('DebugOmniFlowToolReceiver', 'debug-omniflow-tool-result.json',
                            name=name, argumentsBase64=encoded)
        return self.wait(lambda: self.read(path), seconds=600)

    def observe(self):
        request_id = uuid.uuid4().hex
        path = self.request('DebugOmniFlowObserveReceiver',
                            'debug-omniflow-observe-result-' + request_id + '.json',
                            requestId=request_id)
        result = self.wait(lambda: self.read(path))
        self.assertTrue(result.get('success'), result.get('error'))
        return result['state']

    def tap(self, label):
        # Driver-only UI selection. This never supplies a target to OmniTransfer.
        def matching():
            return [n for n in ET.fromstring(self.observe()['xml']).iter('node')
                    if label in (n.get('text'), n.get('content-desc'))]
        # Rendering/attachment is asynchronous. Wait for the actual control;
        # never replay a tap when its outcome is unknown.
        nodes = self.wait(matching, lambda nodes: len(nodes) > 0)
        self.assertEqual(len(nodes), 1, f'Expected unique current UI control: {label}')
        bounds = [int(x) for x in re.findall(r'\d+', nodes[0].get('bounds', ''))]
        self.assertEqual(len(bounds), 4)
        self.adb('shell', 'input', 'tap', str((bounds[0]+bounds[2])//2),
                 str((bounds[1]+bounds[3])//2))

    def recording_status(self):
        path = self.request('DebugHumanRecordingReceiver',
                            'debug-human-recording-result.json', op='status')
        return self.wait(lambda: self.read(path))

    def run_log(self, run_id):
        names = self.adb('shell', 'run-as', self.package, 'ls', 'files/run_logs').decode().splitlines()
        name = next((n for n in names if n.endswith('_' + run_id + '.json')), None)
        return self.read('files/run_logs/' + name) if name else None

    def start_goal(self, goal):
        return self.request('DebugVlmTaskReceiver', 'debug-vlm-task-result.json',
                            goalBase64=base64.b64encode(goal.encode()).decode())

    def test_01_management_rejects_invalid_writes_without_changing_store(self):
        before = self.tool('list_functions', include_hidden=True)
        self.assertTrue(before['success'])
        self.assertFalse(self.tool('clear_functions', confirm=False)['success'])
        self.assertFalse(self.tool('get_function', function_id='missing-' + uuid.uuid4().hex)['success'])
        self.assertFalse(self.tool('save_function')['success'])
        after = self.tool('list_functions', include_hidden=True)
        self.assertEqual(before, after)
        self.persist('management.json', {'before': before, 'after': after})

    def test_02_manual_pause_resume_finish_and_semantic_registration(self):
        self.adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
        self.wait(self.observe, lambda s: 'Search settings' in s['xml'])
        path = self.request('DebugHumanRecordingReceiver', 'debug-human-recording-result.json',
                            op='start', name='Open_Battery', description='Open_Battery_page_from_Settings')
        start = self.wait(lambda: self.read(path))
        self.assertTrue(start['success'])
        run_id = start['run_id']
        try:
            self.tap('Pause')
            self.assertTrue(self.wait(self.recording_status, lambda s: s['recording_paused'])['recording_active'])
            self.tap('Resume')
            self.wait(self.recording_status, lambda s: not s['recording_paused'])
            self.tap('Battery')
            self.wait(self.observe, lambda s: 'Battery usage' in s['xml'])
            self.tap('Finish')
            run = self.wait(lambda: self.run_log(run_id),
                            lambda r: r is not None and r.get('status') == 'succeeded')
        finally:
            if self.recording_status()['recording_active']:
                path = self.request('DebugHumanRecordingReceiver',
                                    'debug-human-recording-result.json', op='finish')
                self.wait(lambda: self.read(path))
        self.persist('manual-run.json', run)
        self.assertEqual(len(run['steps']), 1, 'Control buttons must not be recorded as task actions')
        self.assertTrue(run['steps'][0]['metadata']['evidence_complete'])
        self.assertTrue(run['steps'][0]['before_state_id'])
        self.assertTrue(run['steps'][0]['after_state_id'])
        for side in ('before_state_id', 'after_state_id'):
            state_id = run['steps'][0][side]
            state = self.tool('get_run_log_state', state_id=state_id)
            self.persist('manual-' + side + '.json', state)
            self.assertTrue(state.get('image_base64'), 'Recording requires actual screenshot evidence')
        type(self).recorded_run = run
        saved = self.persist('registration.json', self.tool('save_function', run_id=run_id))
        self.assertTrue(saved.get('registered'), saved)
        type(self).registered_ids = saved['function_ids']
        semantics = [(f['name'] + ' ' + f['description']).lower() for f in saved['functions']]
        self.assertTrue(any('battery' in text for text in semantics), 'Task capability missing')
        self.assertFalse(any('recording' in text for text in semantics),
                         'Recording controls must not become task capabilities')

    def test_03_goal_execution_and_automatic_registration(self):
        self.adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
        self.wait(self.observe, lambda s: 'Search settings' in s['xml'])
        path = self.start_goal('Open the Battery page from the current Android Settings page. '
                               'Finish when Battery usage is visible. Do not change settings.')
        try:
            result = self.persist('goal-result.json', self.wait(lambda: self.read(path), seconds=600))
        finally:
            # A timeout is not a cancellation; release this test's active GUI
            # through its real control before the next test is admitted.
            if self.read(path) is None:
                self.tap('停止')
                self.wait(lambda: self.read(path), seconds=30)
        self.assertTrue(result.get('success'), result)
        self.persist('goal-final-state.json', self.wait(
            self.observe, lambda s: 'Battery usage' in s['xml']))
        if not result.get('recall_hit'):
            self.assertTrue(result.get('auto_registered'), result.get('registration_error'))

    def test_04_takeover_stop_and_restart_are_terminal(self):
        path = self.start_goal('Open Android Settings and find the Battery page. Do not change settings.')
        self.wait(self.observe, lambda s: '接管' in s['xml'])
        self.tap('接管')
        self.wait(self.observe, lambda s: '继续' in s['xml'])
        self.tap('继续')
        self.wait(self.observe, lambda s: '接管' in s['xml'])
        self.tap('停止')
        result = self.persist('stop-result.json', self.wait(lambda: self.read(path), seconds=30))
        self.assertFalse(result.get('success'), result)
        run_id = result['run_id']
        before = self.run_log(run_id)
        self.assertIsNotNone(before)
        functions = self.tool('list_functions', include_hidden=True)
        services = self.adb('shell', 'settings', 'get', 'secure',
                            'enabled_accessibility_services').decode().strip()
        self.adb('shell', 'am', 'force-stop', self.package)
        self.adb('shell', 'am', 'start', '-n', self.package + '/.activity.LauncherActivity')
        after = self.run_log(run_id)
        self.assertEqual(before, after, 'Restart must not restart/overwrite stopped work')
        restored = self.wait(lambda: self.tool('list_functions', include_hidden=True),
                             lambda r: r.get('success') is True, seconds=30)
        self.assertEqual(functions, restored)
        if self.recorded_run:
            self.assertEqual(self.recorded_run, self.run_log(self.recorded_run['run_id']))
        self.persist('restart.json', {'run_before': before, 'run_after': after,
                                      'functions_before': functions})
        # Android can unbind accessibility on force-stop. Restore this isolated
        # test device's prior permission; this is not App recovery behavior.
        if services not in ('', 'null'):
            self.adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', services)
            self.adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')

    def test_05_plugin_disable_update_reinstall_keeps_user_functions(self):
        def change(action):
            path = self.request('DebugSandboxProjectReceiver', 'debug-sandbox-project-result.json',
                                operation='plugin', action=action,
                                pluginId='com.omnimind.omni-vlm-lite')
            result = self.wait(lambda: self.read(path), seconds=120)
            self.persist('plugin-' + action + '.json', result)
            self.assertTrue(result.get('success'), result)
            return result
        before = self.tool('list_functions', include_hidden=True)
        try:
            disabled = change('disable')
            self.assertFalse(disabled['enabled'])
            self.assertEqual(disabled['tools'], [])
            enabled = change('enable')
            self.assertTrue(enabled['enabled'])
            self.assertIn('save_function', enabled['tools'])
            self.assertTrue(change('update')['enabled'])
            self.assertEqual(before, self.tool('list_functions', include_hidden=True))
            self.assertFalse(change('uninstall')['installed'])
            self.assertTrue(change('install')['enabled'])
            self.assertEqual(before, self.tool('list_functions', include_hidden=True))
        finally:
            state = change('status')
            if not state['installed']:
                change('install')
            elif not state['enabled']:
                change('enable')

    def test_06_goal_selects_and_replays_a_registered_function(self):
        self.adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
        self.wait(self.observe, lambda s: 'Search settings' in s['xml'])
        path = self.start_goal('Use a suitable saved GUI Function, if available, to open '
                               'the Battery page from the current Settings page. '
                               'Finish when Battery usage is visible. Do not change settings.')
        try:
            result = self.persist('replay-result.json', self.wait(lambda: self.read(path), seconds=600))
        finally:
            if self.read(path) is None:
                self.tap('停止')
                self.wait(lambda: self.read(path), seconds=30)
        self.assertTrue(result.get('success'), result)
        self.assertTrue(result.get('recall_hit'), 'GUI fallback is not successful Function replay')
        self.persist('replay-final-state.json', self.wait(
            self.observe, lambda s: 'Battery usage' in s['xml']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
