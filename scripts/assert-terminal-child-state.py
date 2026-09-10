#!/usr/bin/env python3
"""Read-only observation of a synthetic terminal child; never kills processes."""
import json, os, re, subprocess, sys, time
from pathlib import Path


def parse_pid(text):
    if text is None or not text.strip(): return None
    assert re.fullmatch(r'\d+\s*', text) and int(text) > 1, 'Invalid synthetic PID: '+repr(text[:80])
    return int(text)


def parse_stat(text):
    end = text.rfind(')')
    assert end > 0, 'Invalid process stat'
    parts = text[end + 1:].split()
    assert len(parts) >= 20, 'Incomplete process stat'
    return {'state': parts[0], 'startTime': parts[19]}


def validate_age(start_time, uptime, ticks):
    assert ticks > 0, 'Invalid clock tick frequency'
    age = uptime - int(start_time) / ticks
    assert 0 <= age < 120, 'Child is too old to distinguish cancellation from natural expiry'
    return age


def validate_child(stat, command, status, owner_status):
    identity = parse_stat(stat)
    uid = re.search(r'^Uid:\s+(\d+)', status, re.M)
    owner = re.search(r'^Uid:\s+(\d+)', owner_status, re.M)
    assert uid and owner and uid[1] == owner[1], 'Child does not belong to this App UID'
    args = command.rstrip('\0').split('\0')
    assert args[-1:] == ['173'] and (len(args) == 2 and Path(args[0]).name == 'sleep' or
        len(args) == 3 and Path(args[0]).name == 'busybox' and args[1] == 'sleep'), 'Not the synthetic sleep child'
    return {**identity, 'uid': int(uid[1]), 'command': args}


def decode_read(returncode, stdout, stderr, missing_ok=False):
    # adb exec-out can report remote cat failure with host exit code zero.
    if returncode or stdout.startswith(b'cat: '):
        error = stderr + stdout
        if missing_ok and b'No such file or directory' in error: return None
        raise AssertionError('Process observation unavailable: '+error.decode(errors='replace')[:200])
    return stdout.decode(errors='strict')


def main():
    serial, marker, mode, record = sys.argv[1:]
    assert re.fullmatch(r'emulator-\d+', serial) or (os.environ.get('OOB_ALLOW_PHYSICAL_DEVICE') == '1' and re.fullmatch(r'[A-Za-z0-9._:-]+', serial))
    assert re.fullmatch(r'OOB_LIVE_TERMINAL_CHILD_\d+', marker)
    assert mode in ('started', 'stopped')
    def read(path, missing_ok=False):
        result = subprocess.run(['adb','-s',serial,'exec-out','run-as','cn.com.omnimind.bot','cat',path], capture_output=True, timeout=10)
        return decode_read(result.returncode, result.stdout, result.stderr, missing_ok)
    def app_pid():
        return subprocess.check_output(['adb','-s',serial,'shell','pidof','cn.com.omnimind.bot'],timeout=10).decode().strip()
    reference = json.loads(Path(record).read_text()) if mode == 'stopped' else None
    if reference:
        assert reference['marker'] == marker
        assert reference['appPid'] == app_pid(), 'App restarted; child exit cannot prove Stop cleanup'
        uptime = float(subprocess.check_output(['adb','-s',serial,'shell','cat','/proc/uptime'],timeout=10).split()[0])
        validate_age(reference['startTime'], uptime, reference['clockTicks'])
    deadline = time.monotonic() + (60 if mode == 'started' else 10)
    while True:
        pid_text = str(reference['pid']) if reference else read('workspace/'+marker+'.pid', True)
        pid = parse_pid(pid_text)
        if pid is not None:
            stat = read(f'/proc/{pid}/stat', True)
            identity = parse_stat(stat) if stat is not None else None
            exited = identity is None or identity['state'] in ('Z','X') or (reference and identity['startTime'] != reference['startTime'])
            if mode == 'stopped' and exited:
                assert reference['appPid'] == app_pid(), 'App restarted during child observation'
                print(json.dumps({'passed': True, 'mode': mode, 'marker': marker, 'pid': pid, 'state': identity})); return
            if mode == 'started' and not exited:
                snapshot = validate_child(stat, read(f'/proc/{pid}/cmdline'), read(f'/proc/{pid}/status'), read('/proc/self/status'))
                ticks = float(subprocess.check_output(['adb','-s',serial,'shell','getconf','CLK_TCK'],timeout=10))
                uptime = float(subprocess.check_output(['adb','-s',serial,'shell','cat','/proc/uptime'],timeout=10).split()[0])
                snapshot['ageSeconds'] = validate_age(snapshot['startTime'], uptime, ticks)
                snapshot['clockTicks'] = ticks
                snapshot.update(marker=marker,pid=pid,appPid=app_pid())
                Path(record).write_text(json.dumps(snapshot,indent=2)+'\n')
                print(json.dumps({'passed': True, 'mode': mode, **snapshot})); return
        if time.monotonic() >= deadline:
            raise AssertionError('Synthetic child did not reach expected state: '+mode)
        time.sleep(.25)

if __name__ == '__main__': main()
