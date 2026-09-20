#!/usr/bin/env python3
"""Check exported real-device/emulator evidence, not synthesize GUI success.

Usage: python3 scripts/verify-gui-lifecycle-evidence.py EVIDENCE_DIRECTORY
Export run.json (manual recording), registration.log (registration logger),
agent-run.json (goal-driven GUI task). This does not automate UI interaction,
prove replay, or replace physical-device acceptance.
"""
import json
from pathlib import Path
import sys
import unittest

evidence = Path(sys.argv.pop(1))


class GuiStopEvidence(unittest.TestCase):
    @unittest.skipUnless((evidence / 'cancel-run.json').exists(),
                         'Planning-stop evidence not supplied; not verified')
    def test_planning_stop_remains_terminal_after_restart(self):
        before = json.loads((evidence / 'cancel-run.json').read_text())
        after = json.loads((evidence / 'cancel-run-after-restart.json').read_text())
        self.assertEqual(before, after, 'Stopped run changed after restart')
        self.assertFalse(after['success'])
        self.assertEqual(after['diagnostics']['done_reason'], 'function_stopped')
        self.assertEqual(after['steps'], [], 'This case stops before the first action')
        events = [json.loads(line) for line in
                  (evidence / 'cancel-events.ndjson').read_text().splitlines()]
        self.assertTrue(events)
        self.assertTrue(all(event['run_id'] == after['run_id'] for event in events))
        terminal = [event for event in events if event['event_type'] == 'run_finished']
        self.assertEqual(len(terminal), 1)
        self.assertEqual(events[-1], terminal[0], 'Late events after terminal')
        self.assertEqual(terminal[0]['payload']['done_reason'], 'function_stopped')


class GuiLifecycleEvidence(unittest.TestCase):
    def test_manual_recording_commits_observed_actions(self):
        run = json.loads((evidence / 'run.json').read_text())
        self.assertIs(run['success'], True)
        self.assertEqual(run['status'], 'succeeded')
        self.assertGreater(len(run['steps']), 0)
        diagnostics = run['diagnostics']['manual_recording']
        self.assertEqual(diagnostics['received_action_count'], len(run['steps']))
        self.assertEqual(diagnostics['committed_action_count'], len(run['steps']))
        for key in ('failed_action_count', 'pending_action_count', 'incomplete_state_count'):
            self.assertEqual(diagnostics[key], 0, key)
        for step in run['steps']:
            self.assertTrue(step['before_state_id'])
            self.assertTrue(step['after_state_id'])
            self.assertTrue(step['action'])

    def test_latest_registration_for_recorded_run_succeeds(self):
        run = json.loads((evidence / 'run.json').read_text())
        attempts = [line for line in (evidence / 'registration.log').read_text().splitlines()
                    if 'run_id=' + run['run_id'] in line]
        self.assertTrue(attempts, 'Missing actual registration result')
        self.assertIn('save_function completed', attempts[-1], attempts[-1])
        self.assertIn('success=true', attempts[-1])

    def test_goal_driven_gui_run_completes(self):
        run = json.loads((evidence / 'agent-run.json').read_text())
        self.assertIs(run['success'], True, run.get('error'))
        self.assertEqual(run['status'], 'succeeded')
        self.assertGreater(len(run['steps']), 0)
        self.assertTrue(run['final_state_id'])


if __name__ == '__main__':
    unittest.main()
