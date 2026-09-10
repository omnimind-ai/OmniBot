#!/usr/bin/env python3
"""Execute the real host wrapper against a PRoot signal/stdio contract fixture."""
import os, signal, subprocess, sys, tempfile, unittest, selectors
from pathlib import Path

SCRIPT=Path(__file__).resolve().parents[1]/'ReTerminal/core/main/src/main/assets/init-host.sh'

class HostStopTest(unittest.TestCase):
    def test_stop_reaches_runtime_cleanup_and_preserves_stdin(self):
        self.run_contract(cancel=True)

    def test_nonzero_exit_and_stdin_are_preserved(self):
        self.run_contract(cancel=False)

    def run_contract(self, cancel):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ['local/alpine/bin/sh','local/alpine/etc/os-release','local/alpine/.omnibot-rootfs-ready']:
                path=root/name; path.parent.mkdir(parents=True,exist_ok=True); path.touch()
            linker=root/'linker'
            linker.write_text('#!'+sys.executable+'\n'+'''import os, signal, subprocess, sys, time
child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)'])
def cleanup(*args):
    child.kill(); child.wait(); sys.exit(0)
signal.signal(signal.SIGTERM, signal.SIG_IGN)
signal.signal(signal.SIGQUIT, cleanup)
print(str(os.getpid())+' '+str(child.pid), flush=True)
line=sys.stdin.readline()
print('STDIN:'+line.strip(), flush=True)
if line.strip() == 'EXIT7':
    child.kill(); child.wait(); sys.exit(7)
while True: time.sleep(.1)
''')
            linker.chmod(0o755)
            process=subprocess.Popen(['/bin/sh',str(SCRIPT)],env={**os.environ,'PREFIX':directory,'LINKER':str(linker),'OMNIBOT_TERMINAL_DISTRIBUTION':'alpine','OMNIBOT_HOST_WORKSPACE':'','OMNIBOT_MT_STORAGE_HOST':''},stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
            runtime=None
            def line():
                with selectors.DefaultSelector() as ready:
                    ready.register(process.stdout,selectors.EVENT_READ)
                    self.assertTrue(ready.select(3),'Wrapper did not produce expected output')
                    return process.stdout.readline().strip()
            try:
                runtime, child=map(int,line().split())
                message='PING' if cancel else 'EXIT7'
                process.stdin.write(message+'\n'); process.stdin.flush()
                self.assertEqual(line(),'STDIN:'+message,'Background launch lost stdin')
                if cancel: process.terminate()
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired: self.fail('Host TERM did not reach PRoot cleanup')
                self.assertEqual(process.returncode,130 if cancel else 7)
                with self.assertRaises(ProcessLookupError): os.kill(child,0)
            finally:
                if runtime:
                    try: os.kill(runtime,signal.SIGQUIT)
                    except ProcessLookupError: pass
                try: process.wait(timeout=3)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
                for stream in (process.stdin,process.stdout,process.stderr):stream.close()

if __name__=='__main__':unittest.main()
