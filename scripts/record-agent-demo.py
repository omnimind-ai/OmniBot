#!/usr/bin/env python3
"""Record bounded Android segments; create OUTPUT/stop-recording to finish."""
import subprocess,time,json,sys,re
from pathlib import Path
serial,directory=sys.argv[1:]
assert re.fullmatch(r'[A-Za-z0-9._:-]+',serial)
out=Path(directory).resolve();out.mkdir(parents=True,exist_ok=True)
assert not (out/'stop-recording').exists(), 'Use a fresh output directory'
assert not list(out.glob('raw-*.mp4')), 'Do not overwrite previous recordings'
base=['adb','-s',serial];segments=[]
for i in range(30):
 if (out/'stop-recording').exists(): break
 remote=f'/sdcard/life-xp-record-{time.time_ns()}.mp4'
 p=subprocess.Popen(base+['shell',f'echo $$; exec screenrecord --size 720x1600 --bit-rate 2000000 --time-limit 170 {remote}'],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
 pid=p.stdout.readline().strip()
 while p.poll() is None:
  if (out/'stop-recording').exists():
   if pid.isdigit():subprocess.run(base+['shell','kill','-2',pid],capture_output=True)
   break
  time.sleep(1)
 p.communicate(timeout=20)
 local=out/f'raw-{i:02}.mp4'
 subprocess.run(base+['pull',remote,str(local)],check=True,stdout=subprocess.DEVNULL)
 segments.append(local.name);(out/'segments.json').write_text(json.dumps(segments))
