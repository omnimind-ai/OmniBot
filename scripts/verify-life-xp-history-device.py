#!/usr/bin/env python3
"""Read-only actual emulator history acceptance; open the Life XP app first."""
import json, re, sqlite3, subprocess, sys, time
from agent_test_database import agent_database_snapshot
from pathlib import Path
import xml.etree.ElementTree as E

serial, output, baseline = sys.argv[1:4]
slug = sys.argv[4] if len(sys.argv) == 5 else 'life-xp-demo'
assert slug == 'life-xp-demo' or re.fullmatch(r'life-xp-autonomous-[0-9]+', slug)
assert re.fullmatch(r'emulator-\d+', serial)
out = Path(output); out.mkdir(parents=True, exist_ok=True)
base = ['adb', '-s', serial]
def adb(*args): return subprocess.check_output(base + list(args), timeout=30)
def snapshot():
    adb('shell', 'uiautomator', 'dump', '/data/local/tmp/life-history-test.xml')
    raw = adb('shell', 'cat', '/data/local/tmp/life-history-test.xml')
    return raw, list(E.fromstring(raw).iter('node'))
def label(node): return node.get('text') or node.get('content-desc') or ''
result = {'passed': False, 'serial': serial, 'project': slug}
try:
    for _ in range(10):
        raw, nodes = snapshot()
        buttons = [n for n in nodes if label(n) == '历史']
        if len(buttons) == 1: break
        time.sleep(.3)
    assert len(buttons) == 1, 'Open the actual Life XP app first'
    x, y, xx, yy = map(int, re.findall(r'\d+', buttons[0].get('bounds')))
    adb('shell', 'input', 'tap', str((x+xx)//2), str((y+yy)//2))
    for _ in range(10):
        raw, nodes = snapshot(); labels = [label(n) for n in nodes]
        if ('累计: 75 XP' in labels if slug == 'life-xp-demo' else '累计总计' in labels): break
        time.sleep(.3)
    if slug == 'life-xp-demo':
        for name, xp in [('DemoWalk',75), ('读书',60), ('运动',40), ('喝水',10)]:
            index = labels.index(name)
            following = next(s for s in labels[index+1:] if s.startswith('累计:'))
            assert following == f'累计: {xp} XP', (name, following)
        assert '2026-09-17 - 总计: +75 XP' in labels
        assert '达到等级 2' in labels and '75' in labels
    (out/'history.xml').write_bytes(raw)
    (out/'history.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    with agent_database_snapshot(serial, f'files/plugin-data/local.project.{slug}/project.db') as db:
        db.row_factory = sqlite3.Row
        tables = {'habits':'id', 'check_ins':'id'} if slug == 'life-xp-demo' else {
            'habits':'habit_id', 'check_ins':'check_in_id', 'level_milestones':'milestone_id'}
        records = {table: [dict(row) for row in db.execute('SELECT * FROM '+table+' ORDER BY '+pk)]
                   for table,pk in tables.items()}
    expected = json.loads(Path(baseline).read_text())
    normalize = lambda data: {table: [{k:str(v) for k,v in row.items()} for row in rows] for table,rows in data.items()}
    assert normalize(records) == normalize(expected), 'Original records changed'
    if slug != 'life-xp-demo':
        habits = {r['habit_id']:r for r in records['habits']}
        days = {}
        for row in records['check_ins']:
            days.setdefault(row['check_in_date'], []).append(habits[row['habit_id']])
        assert days, 'This acceptance requires saved check-ins'
        cumulative = 0
        for day, entries in sorted(days.items()):
            earned = sum(r['xp_reward'] for r in entries)
            cumulative += earned
            indices = [i for i,s in enumerate(labels) if s.startswith(day.replace('-', '/'))]
            assert len(indices) == 1, ('Saved day missing from actual history', day)
            i = indices[0]
            assert labels[i+1:i+6] == [', '.join(r['name'] for r in entries),
                str(earned),str(earned),str(cumulative),str(1+cumulative//50)], ('Incorrect history row',day)
            for n in nodes[i:i+6]:
                x,y,xx,yy = map(int,re.findall(r'\d+',n.get('bounds')))
                assert xx>x and yy>y, 'History cells must actually be visible'
    (out/'records.json').write_text(json.dumps(records, ensure_ascii=False, indent=2))
    result.update(passed=True, scope='actual published Android page and unchanged original records')
except Exception as error:
    result['error'] = str(error)
    raise
finally:
    (out/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps(result, ensure_ascii=False))
