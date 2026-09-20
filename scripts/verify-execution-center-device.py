"""PJE110 Chinese UI acceptance; runs a previously recorded harmless Settings flow.

OOB_GUI_SERIAL=b49f281b OOB_ALLOW_PHYSICAL_DEVICE=1 \
 OOB_GUI_OUTPUT=/tmp/execution-acceptance python3 scripts/verify-execution-center-device.py \
 --function-id complete_task --recorded-run-id human_...

Precondition: unlocked phone, current debug APK, accessibility enabled, Execution
Center open (or the Settings search result left by a previous run). The supplied
function must only open the system Settings search page. No test artifacts are
injected. All execution starts by tapping the real UI. Debug calls only read.
Never clears data, deletes functions, alters providers, or replaces OmniTransfer.
"""
import argparse
import json
import os
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET
from test_omniflow_device_lifecycle import OmniFlowDeviceLifecycle


class Journey:
    def __init__(self, options):
        self.options = options
        self.device = OmniFlowDeviceLifecycle()
        self.device.serial = os.environ['OOB_GUI_SERIAL']
        if not self.device.serial.startswith('emulator-') and os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') != '1':
            raise ValueError('Physical device requires explicit opt-in')
        self.device.adb_path = os.environ.get('ADB', 'adb')
        self.device.output = Path(os.environ['OOB_GUI_OUTPUT'])
        self.device.output.mkdir(parents=True, exist_ok=True)
        self.results = []

    def nodes(self):
        return list(ET.fromstring(self.device.observe()['xml']).iter('node'))

    @staticmethod
    def label(node):
        return node.get('text') or node.get('content-desc') or ''

    def matching(self, label):
        return [n for n in self.nodes() if label in self.label(n).splitlines()]

    def tap_node(self, node):
        b = list(map(int, re.findall(r'-?\d+', node.get('bounds', ''))))
        assert len(b) == 4 and b[0] >= 0 and b[1] >= 0, 'Target not fully on screen'
        self.device.adb('shell', 'input', 'tap', str((b[0]+b[2])//2), str((b[1]+b[3])//2))

    def tap(self, label):
        nodes = self.device.wait(lambda: self.matching(label), lambda n: len(n) == 1)
        # Avoid reading Flutter's intermediate route-transition bounds.
        time.sleep(.4)
        nodes = self.matching(label)
        assert len(nodes) == 1, f'Ambiguous control {label}'
        self.tap_node(nodes[0])

    def launch(self):
        self.device.adb('shell', 'am', 'start', '-W', '-n', self.device.package + '/.activity.LauncherActivity')
        time.sleep(.7)

    def center(self, restarted=False):
        self.launch()
        if restarted:
            # Home's hamburger currently has no label. Select the actual current
            # top-left clickable node, not a remembered screen coordinate.
            nodes = self.nodes()
            candidates = []
            for n in nodes:
                b = list(map(int, re.findall(r'-?\d+', n.get('bounds', ''))))
                if len(b) == 4 and n.get('clickable') == 'true' and n.get('package') == self.device.package and b[0] >= 0 and b[2] <= 180 and 100 <= b[1] < 320:
                    candidates.append(n)
            assert len(candidates) == 1, 'Home menu control not unique'
            self.tap_node(candidates[0])
            self.tap('设置')
            self.tap('执行中心')
        self.device.wait(lambda: self.matching('手动录制'), bool)

    def settings_home(self):
        self.device.adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
        time.sleep(.6)
        # Settings may restore its search child. Navigate back without editing.
        for _ in range(4):
            if self.matching('搜索历史'):
                self.tap('取消')
                time.sleep(.5)
            else:
                break
        assert self.matching('搜索设置项'), 'Settings home search field missing'
        assert not self.matching('搜索历史'), 'Search destination already active before run'

    def execute(self, index):
        self.settings_home()
        self.center()
        self.tap('刷新')
        self.device.wait(lambda: self.matching(self.function['name']), bool)
        self.tap(self.function['name'])
        self.device.wait(lambda: self.matching('复用指令详情'), bool)
        assert self.matching(self.options.function_id), 'Wrong Function detail'
        baseline = self.device.tool('list_run_logs', limit=1)['runs'][0]['run_id']
        started = time.monotonic()
        self.tap('执行')
        if self.function.get('input_schema', {}).get('properties'):
            raise AssertionError('Fixture must require no parameters')
        deadline = time.monotonic() + 120
        run = None
        while time.monotonic() < deadline:
            latest = self.device.tool('list_run_logs', limit=1)['runs'][0]
            if latest['run_id'] != baseline and latest.get('status') in ('succeeded', 'failed', 'cancelled'):
                run = latest
                break
            time.sleep(1)
        assert run is not None, 'No terminal run; timeout is not success'
        self.device.persist(f'replay-{index}.json', run)
        assert run['status'] == 'succeeded', run
        assert run['diagnostics']['function_id'] == self.options.function_id
        assert run['diagnostics']['tool_name'] == 'run_function'
        self.device.wait(lambda: self.matching('搜索历史'), bool)
        self.results.append({'run_id': run['run_id'], 'seconds': round(time.monotonic()-started, 2), 'destination': 'Settings search history visible'})
        self.device.persist(f'run-{index}-steps.json', self.device.run_log(run['run_id']))

    def run(self):
        self.function = self.device.tool('get_function', function_id=self.options.function_id)
        source = self.device.run_log(self.options.recorded_run_id)
        assert source and source['status'] == 'succeeded'
        assert len(source['steps']) == 1, 'Recording controls leaked into source actions'
        assert source['steps'][0]['metadata']['evidence_complete'] is True
        self.device.persist('manual-recording.json', source)
        self.device.persist('function.json', self.function)
        if self.options.resume_after_authorization:
            previous = json.loads((self.device.output / 'replay-1.json').read_text())
            assert previous['status'] == 'succeeded'
            self.results.append({'run_id': previous['run_id'], 'previous_phase': True})
        else:
            self.execute(1)
            self.center()
            (self.device.output / 'execution-center.png').write_bytes(self.device.adb('exec-out', 'screencap', '-p'))
            self.device.adb('shell', 'am', 'force-stop', self.device.package)
        self.center(restarted=True)
        persisted = self.device.tool('get_function', function_id=self.options.function_id)
        assert persisted == self.function, 'Function changed after restart'
        self.execute(2)
        assert len({r['run_id'] for r in self.results}) == 2
        self.center()
        self.device.persist('result.json', {'success': True, 'device': self.device.serial, 'model': self.device.adb('shell', 'getprop', 'ro.product.model').decode().strip(), 'runs': self.results, 'restart_persistence': True, 'system_reauthorization_required': self.options.resume_after_authorization})
        print(json.dumps(self.results, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--function-id', required=True)
    parser.add_argument('--recorded-run-id', required=True)
    parser.add_argument('--resume-after-authorization', action='store_true',
                        help='Resume failed force-stop phase after user/system permission UI reauthorization; retains failure.json')
    journey = Journey(parser.parse_args())
    try:
        journey.run()
    except Exception as error:
        journey.device.persist('failure.json', {'success': False, 'error': str(error), 'completed_runs': journey.results})
        raise
