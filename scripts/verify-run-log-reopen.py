"""Reopen a real Settings replay after app restart; observe UI and disk only.

Usage: verify-run-log-reopen.py SERIAL RUN_ID STEP_COUNT OUTPUT [--legacy-snapshot] [--expect-action-labels]
Legacy mode requires an existing pre-fix obfuscated snapshot plus its real events.
"""
import hashlib, json, re, shlex, subprocess, sys, time
from datetime import datetime
from pathlib import Path
import xml.etree.ElementTree as E
from zoneinfo import ZoneInfo

serial, run_id, count, directory = sys.argv[1:5]
assert re.fullmatch(r'emulator-\d+', serial)
assert re.fullmatch(r'tool-[0-9a-f-]+', run_id)
flags = set(sys.argv[5:])
assert flags.issubset({'--legacy-snapshot', '--expect-action-labels', '--expect-action-point'})
legacy = '--legacy-snapshot' in flags; count = int(count); assert count > 0
out = Path(directory); out.mkdir(parents=True, exist_ok=True)
package = 'cn.com.omnimind.bot'
def adb(*args): return subprocess.check_output(['adb','-s',serial,*args], timeout=30)
assert adb('shell','id','-u').strip()==b'0'
root = '/data/user/0/'+package+'/files/run_logs/'
names = adb('shell','ls',root).decode().splitlines()
matches = [n for n in names if n.endswith('_'+run_id+'.json')]
assert len(matches)==1
path = root+matches[0]
before = adb('shell','cat',path)
snapshot = json.loads(before)
assert snapshot['run_id']==run_id
timezone = ZoneInfo(adb('shell','getprop','persist.sys.timezone').decode().strip())
started_label = 'Started '+datetime.fromtimestamp(snapshot['started_at_ms']/1000, timezone).strftime('%Y-%m-%d %H:%M:%S')
if legacy:
    assert 'status' not in snapshot and 'success' not in snapshot
    events = adb('shell','cat',path.removesuffix('.json')+'.events.ndjson')
    assert events, 'Legacy recovery requires original events'
else:
    assert snapshot['status']=='succeeded' and snapshot['success'] is True
    assert len(snapshot['steps'])==count
result = {'passed':False,'serial':serial,'runId':run_id,'legacySnapshot':legacy}
try:
    adb('shell','am','force-stop',package)
    adb('shell',shlex.join(['am','start','-n',package+'/.activity.MainActivity',
                          '--es','route','/task/run_log/'+run_id]))
    deadline = time.monotonic()+45
    while True:
        adb('shell','uiautomator','dump','/data/local/tmp/oob-run-reopen.xml')
        raw = adb('shell','cat','/data/local/tmp/oob-run-reopen.xml')
        groups = [n.get('text') or n.get('content-desc') or '' for n in E.fromstring(raw).iter('node')]
        labels = [line for group in groups for line in group.splitlines()]
        if 'Execution completed' in labels and f'Steps {count}' in labels: break
        assert time.monotonic()<deadline, 'Actual Run Log page did not recover completed steps'
        time.sleep(.3)
    assert any('complete_manual_recording_search' in text and 'wifi' in text for text in groups)
    assert started_label in labels, 'Page must show the selected run, not a previous replay'
    assert all(f'Step {i}' in labels for i in range(1,count+1))
    if '--expect-action-labels' in flags:
        assert 'Enter text · wifi' in labels
        assert any(text.startswith('Tap · ') for text in labels)
        assert 'Action' not in labels, 'Official action collapsed into a generic placeholder'
    (out/'reopened.xml').write_bytes(raw)
    (out/'reopened.png').write_bytes(adb('exec-out','screencap','-p'))
    if '--expect-action-point' in flags:
        assert not legacy, 'Coordinate assertion requires unobfuscated source steps'
        from PIL import Image
        import io
        step = next(s for s in snapshot['steps'] if s.get('action', {}).get('args', {}).get('x') is not None)
        state_id = step['before_state_id']
        assert re.fullmatch(r'state_[0-9a-f]+', state_id)
        state_names = adb('shell','ls',root+'states/').decode().splitlines()
        state_name, = [n for n in state_names if n.endswith('_'+state_id+'.json')]
        state = json.loads(adb('exec-out','cat',root+'states/'+state_name))
        source_image = adb('exec-out','cat',state['screenshot_path'])
        source_bitmap = Image.open(io.BytesIO(source_image)).convert('RGB')
        source_width, source_height = source_bitmap.size
        args = step['action']['args']
        ratio_x, ratio_y = float(args['x'])/1000, float(args['y'])/1000
        assert 0 <= ratio_x <= 1 and 0 <= ratio_y <= 1
        def fresh():
            adb('shell','uiautomator','dump','/data/local/tmp/oob-run-point.xml')
            xml = adb('exec-out','cat','/data/local/tmp/oob-run-point.xml')
            return xml, list(E.fromstring(xml).iter('node'))
        def label(n): return n.get('text') or n.get('content-desc') or ''
        def bounds(n): return list(map(int,re.findall(r'\d+',n.get('bounds',''))))
        def tap(n):
            x,y,xx,yy = bounds(n)
            assert xx>x and yy>y
            adb('shell','input','tap',str((x+xx)//2),str((y+yy)//2))
        raw, nodes = fresh()
        row, = [n for n in nodes if n.get('clickable')=='true' and label(n).startswith(f"Step {step['step_index']+1}\n")]
        tap(row)
        raw, nodes = fresh()
        control, = [n for n in nodes if n.get('clickable')=='true' and label(n)=='Action screenshot']
        tap(control)
        deadline = time.monotonic()+20
        while True:
            raw, nodes = fresh()
            images = [n for n in nodes if label(n)=='Recorded screen']
            markers = [n for n in nodes if label(n)=='Action location']
            if len(images)==len(markers)==1: break
            assert time.monotonic()<deadline, 'Actual screenshot or marker semantics missing'
            time.sleep(.3)
        picture = bounds(images[0]); marker = bounds(markers[0])
        image_width = picture[2]-picture[0]
        assert image_width>0
        # Android may clip semantic height at the scroll viewport. Derive the
        # rendered full height from the actual image aspect ratio and width.
        expected = [picture[0]+image_width*ratio_x,
                    picture[1]+image_width*source_height/source_width*ratio_y]
        # Flutter can expand the icon semantic rect to its whole positioned
        # layer. Inspect the actually painted redAccent marker independently.
        def marker_color(rgb):
            red, green, blue = rgb
            return red >= 250 and abs(green-82)<=3 and abs(blue-82)<=3
        assert not any(marker_color(rgb) for rgb in source_bitmap.getdata()), 'Choose a source screenshot without the marker color'
        painted = adb('exec-out','screencap','-p')
        bitmap = Image.open(io.BytesIO(painted)).convert('RGB')
        pixels = bitmap.load()
        points = [(x,y) for y in range(picture[1],min(picture[3],bitmap.height))
                  for x in range(picture[0],min(picture[2],bitmap.width)) if marker_color(pixels[x,y])]
        assert len(points)>=20, 'No actual painted action marker'
        ink = [min(x for x,y in points),min(y for x,y in points),
               max(x for x,y in points),max(y for x,y in points)]
        assert 5 <= ink[2]-ink[0] <= 100 and 5 <= ink[3]-ink[1] <= 100, 'Ambiguous marker pixels'
        actual = [(ink[0]+ink[2])/2,(ink[1]+ink[3])/2]
        (out/'action-point.xml').write_bytes(raw)
        (out/'action-point.png').write_bytes(painted)
        result['actionPoint'] = {'expected':expected,'actual':actual,'sourceSize':[source_width,source_height],
                                 'sourceRatio':[ratio_x,ratio_y],'imageBounds':picture,'markerSemanticsBounds':marker,
                                 'paintedMarkerBounds':ink,'matchingPixels':len(points)}
        assert all(abs(a-e)<=2 for a,e in zip(actual,expected)), 'Visible marker does not match original recorded action'
    after = adb('shell','cat',path)
    assert after==before, 'Read-only reopen must preserve the original snapshot'
    result.update(passed=True,steps=count,snapshotSha256=hashlib.sha256(after).hexdigest())
except Exception as error:
    result['error']=str(error)
    raise
finally:
    (out/'result.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
