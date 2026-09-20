"""Read-only device regression: recording B must not overwrite saved Function A.

Supply the captured Function A before recording B, and A's original RunLog ID.
Uses existing physical-device opt-in and output environment variables. Run after
the real recording/registration journey, not against a fabricated catalog.
Failure is retained; missing provenance must never be inferred from a replay.
"""
import argparse
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location(
    'journey', Path(__file__).with_name('verify-execution-center-device.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def verify(baseline_path, source_run_id):
    j = module.Journey(SimpleNamespace())
    baseline = json.loads(Path(baseline_path).read_text())
    current = j.device.tool('get_function', function_id=baseline['function_id'])
    outcomes = [
        dict(case='unrelated-recording-preserves-existing-function',
             passed=current['steps'] == baseline['steps'],
             function_id=baseline['function_id'],
             before_steps=len(baseline['steps']), after_steps=len(current['steps'])),
        dict(case='source-provenance-survives-reload',
             passed=current.get('source_run_id') == source_run_id,
             expected_source_run_id=source_run_id,
             actual_source_run_id=current.get('source_run_id')),
    ]
    j.device.persist('provenance-regression.json', outcomes)
    if not all(result['passed'] for result in outcomes):
        raise AssertionError('Function identity/provenance regression failed; see provenance-regression.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--baseline-function', required=True)
    parser.add_argument('--source-run-id', required=True)
    args = parser.parse_args()
    verify(args.baseline_function, args.source_run_id)
