"""Physical-device Execution Center controls audit. Does not remove user Functions.
Run with OOB_GUI_SERIAL/OOB_ALLOW_PHYSICAL_DEVICE/OOB_GUI_OUTPUT as in
verify-execution-center-device.py. Start on Execution Center, unlocked/authorized.
Uses the actual UI for recording, cancellation, detail and deletion dialogs.
Only test-created fixtures may be deleted. No test input is a mock completion.
"""
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import json
import time

spec = importlib.util.spec_from_file_location('execution_journey', Path(__file__).with_name('verify-execution-center-device.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class Audit(module.Journey):
    def __init__(self):
        super().__init__(SimpleNamespace())
        self.outcomes = []

    def case(self, name, action):
        try:
            detail = action()
            self.outcomes.append({'case': name, 'passed': True, 'detail': detail})
        except Exception as e:
            self.outcomes.append({'case': name, 'passed': False, 'error': str(e)})
            raise
        finally:
            self.device.persist('controls.json', self.outcomes)

    def cancel_recording(self, start=False):
        before = self.device.tool('list_functions')['functions']
        self.tap('手动录制')
        status = self.device.wait(self.device.recording_status, lambda s: s.get('recording_active'))
        assert status['recording_paused'], 'Not in READY state'
        run_id = status['run_id']
        if start:
            self.tap('开始')
            self.device.wait(self.device.recording_status, lambda s: s.get('recording_active') and not s.get('recording_paused'))
            self.tap('暂停')
            self.device.wait(self.device.recording_status, lambda s: s.get('recording_paused'))
        self.tap('取消')
        self.device.wait(self.device.recording_status, lambda s: not s.get('recording_active'))
        run = self.device.wait(lambda: self.device.run_log(run_id))
        assert run['status'] == 'cancelled', run['status']
        assert self.device.tool('list_functions')['functions'] == before
        self.device.persist(('active' if start else 'ready') + '-cancel.json', run)
        return {'run_id': run_id, 'status': run['status'], 'functions_unchanged': True}

    def cancel_delete(self):
        self.tap('Complete requested task')
        self.tap('删除')
        self.tap('取消')
        function = self.device.tool('get_function', function_id='complete_task')
        assert function['function_id'] == 'complete_task'
        return 'Existing Function retained after cancellation'

    def enhance(self):
        self.tap('Complete requested task')
        self.tap('增强')
        nodes = self.nodes()
        error = next((self.label(n) for n in nodes if 'Missing local source_run_id' in self.label(n)), None)
        assert error is None, error
        assert any(n.get('class') == 'android.widget.EditText' for n in nodes), 'Enhance did not open Agent chat'
        return 'Agent chat opened (completion must be checked separately)'

    def run(self):
        self.case('record-ready-cancel', lambda: self.cancel_recording(False))
        self.case('record-start-pause-cancel', lambda: self.cancel_recording(True))
        self.case('delete-dialog-cancel', self.cancel_delete)
        self.case('enhance-after-reload', self.enhance)

if __name__ == '__main__':
    Audit().run()
