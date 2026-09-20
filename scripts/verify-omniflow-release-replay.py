"""Replay the existing Settings search Function through Release UI on a rooted emulator.

No debug receiver, injected Function, tool execution or database writes.
Usage: python3 scripts/verify-omniflow-release-replay.py SERIAL OUTPUT [--recover-accessibility]
"""
import hashlib, json, re, shlex, subprocess, sys, time
from pathlib import Path
import xml.etree.ElementTree as E

serial, output = sys.argv[1:3]
assert sys.argv[3:] in ([], ['--recover-accessibility'])
recover_accessibility = bool(sys.argv[3:])
assert re.fullmatch(r'emulator-\d+', serial)
out = Path(output); out.mkdir(parents=True, exist_ok=True)
package = 'cn.com.omnimind.bot'
root = '/data/user/0/' + package + '/'
function_id = 'complete_manual_recording_search'
def adb(*args):
    return subprocess.check_output(['adb', '-s', serial, *args], timeout=30)
assert adb('shell', 'id', '-u').strip() == b'0', 'Already rooted test emulator required'
def read(path): return json.loads(adb('shell', 'cat', root + path))
def store(): return read('workspace/.omnibot/omniflow/omniflow.json')['functions']
def nodes():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/oob-release-replay.xml')
    return list(E.fromstring(adb('shell', 'cat', '/data/local/tmp/oob-release-replay.xml')).iter('node'))
def label(n): return n.get('content-desc') or n.get('text') or ''
def wait(read_value, predicate, seconds=30):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = read_value()
        if predicate(value): return value
        time.sleep(.3)
    raise AssertionError('Observation deadline exceeded; no action replayed')
def tap_node(n):
    x,y,xx,yy = map(int, re.findall(r'\d+', n.get('bounds')))
    adb('shell', 'input', 'tap', str((x+xx)//2), str((y+yy)//2))
def tap(text):
    found = wait(lambda: [n for n in nodes() if text in label(n).splitlines()], lambda ns:len(ns)==1)
    tap_node(found[0])
def run_names():
    return {n for n in adb('shell', 'ls', root+'files/run_logs').decode().splitlines() if n.endswith('.json')}
def save(name, value): (out/name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
result = {'passed':False, 'serial':serial, 'function_id':function_id,
          'verifierSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
try:
    original = store()[function_id]
    assert len(original['steps']) == 2 and 'search_term' in original['input_schema']['properties']
    save('original-function.json', original)
    adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
    for _ in range(3):
        ns = nodes()
        if any(label(n)=='Search settings' for n in ns): break
        back = [n for n in ns if n.get('package')=='com.google.android.settings.intelligence' and label(n)=='Back']
        assert len(back)==1, 'Settings homepage not available'
        tap_node(back[0])
    assert any(label(n)=='Search settings' for n in nodes())
    route = '/task/omniflow?functionId='+function_id
    adb('shell', shlex.join(['am','start','-n',package+'/.activity.MainActivity','--es','route',route]))
    wait(nodes, lambda ns:any(function_id in label(n) for n in ns))
    before = run_names()
    tap('Run')
    if recover_accessibility:
        wait(nodes, lambda ns:any(label(n)=='Enable accessibility permission' for n in ns))
        assert run_names()==before, 'Permission-blocked action unexpectedly executed'
        (out/'accessibility-required.png').write_bytes(adb('exec-out','screencap','-p'))
        tap('Open Accessibility settings')
        tap('Omnibot')
        tap('Use Omnibot')
        wait(nodes, lambda ns:any(label(n)=='Allow Omnibot to have full control of your device?' for n in ns))
        tap('Allow')
        adb('shell', 'am', 'start', '-a', 'android.settings.SETTINGS')
        wait(nodes, lambda ns:any(label(n)=='Search settings' for n in ns))
        adb('shell', shlex.join(['am','start','-n',package+'/.activity.MainActivity','--es','route',route]))
        tap('Run')
        result['accessibilityRecoveredThroughUi'] = True
    fields = wait(lambda:[n for n in nodes() if n.get('class')=='android.widget.EditText'], lambda ns:len(ns)==1)
    tap_node(fields[0])
    wait(nodes, lambda ns:any(n.get('class')=='android.widget.EditText' and n.get('focused')=='true' for n in ns))
    # Stock UIAutomator may omit IME windows and has no visible-to-user attribute.
    wait(lambda:adb('shell','dumpsys','input_method').decode(), lambda value:'mInputShown=true' in value)
    adb('shell','input','text','wifi')
    wait(nodes, lambda ns:any(n.get('class')=='android.widget.EditText' and label(n)=='wifi' for n in ns))
    tap('Run')
    # Do not attach UIAutomation while the application's accessibility replay owns the screen.
    def completed():
        new = run_names()-before
        if not new: return None
        assert len(new)==1, 'Expected one user-triggered replay'
        run = read('files/run_logs/'+next(iter(new)))
        required = {'schema_version','run_id','goal','status','success','steps','diagnostics'}
        if not required.issubset(run):
            save('invalid-run-snapshot.json', run)
            raise AssertionError('Persisted canonical protocol fields missing; possible Release obfuscation')
        return run if run.get('status') in ('succeeded','failed','cancelled') else None
    run = wait(completed, bool, seconds=180)
    save('replay-run.json', run)
    assert run['status']=='succeeded', 'Canonical replay did not succeed'
    main_steps = [s for s in run['steps'] if s.get('metadata',{}).get('origin')=='action']
    assert [s['metadata'].get('function_step_index') for s in main_steps]==[0,1], 'Expected the complete two-step Function once'
    assert [s['action']['tool'] for s in main_steps]==['click','input_text']
    assert main_steps[1]['action']['args'].get('text')=='wifi'
    assert all(s.get('metadata',{}).get('origin') in ('action','checker') and s['result'].get('success') is True for s in run['steps'])
    assert any(n.get('package') in ('com.android.settings','com.google.android.settings.intelligence') and label(n)=='wifi' for n in nodes())
    (out/'final.png').write_bytes(adb('exec-out','screencap','-p'))
    assert store()[function_id]==original, 'Replay altered saved Function'
    result.update(passed=True, run_id=run['run_id'], query='wifi')
except Exception as error:
    result['error'] = str(error)
    raise
finally:
    save('result.json', result)
print(json.dumps(result))
