"""Prepare an isolated synthetic 16 MiB input, never overwrite an unrelated file.

Usage: python3 prepare-auto-context-fixture.py SERIAL OUTPUT_MANIFEST
Requires an already rooted emulator; writes test input only, never Agent history.
"""
import hashlib, json, re, shlex, subprocess, sys, tempfile
from pathlib import Path

serial, output = sys.argv[1:]
assert re.fullmatch(r'emulator-\d+', serial)
def adb(*args): return subprocess.check_output(['adb','-s',serial,*args],timeout=60)
assert adb('shell','id','-u').strip()==b'0'
workspace='/data/user/0/cn.com.omnimind.bot/workspace'
directory=workspace+'/oob-auto-context-release-fixture'
remote=directory+'/large.html'
owner=adb('shell','stat','-c','%u:%g',workspace).decode().strip()
assert re.fullmatch(r'\d+:\d+',owner)
with tempfile.TemporaryDirectory(prefix='oob-auto-context-') as temp:
    fixtures=Path(temp)/'fixtures'
    subprocess.run([sys.executable,str(Path(__file__).parent/'fixtures/generate-context-files.py'),str(fixtures)],check=True,capture_output=True)
    local=fixtures/'large.html'; data=local.read_bytes(); digest=hashlib.sha256(data).hexdigest()
    assert len(data)>60*65536
    exists=subprocess.run(['adb','-s',serial,'shell','test','-e',remote],capture_output=True).returncode==0
    if exists:
        assert adb('shell','sha256sum',remote).decode().split()[0]==digest, 'Existing independent fixture differs; not overwriting'
    else:
        adb('shell','mkdir','-p',directory)
        adb('push',str(local),remote)
        adb('shell','chown',owner,directory,remote)
        adb('shell','chmod','700',directory)
        adb('shell','chmod','600',remote)
    actual=adb('shell','sha256sum',remote).decode().split()[0]
    assert actual==digest
    result={'serial':serial,'path':'/workspace/oob-auto-context-release-fixture/large.html',
            'bytes':len(data),'sha256':digest,'pagesRequired':60,'pageChars':65536,'inputOnly':True}
out=Path(output);out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(result,indent=2))
print(json.dumps(result))
