#!/usr/bin/env python3
"""Measure process-cold Android launches without clearing application data.

Install the desired APK separately. Run identical settings for before/after.
Only startup timing lines are retained from logcat; never store full app logs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess
import time
from zipfile import ZipFile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--label', required=True)
    parser.add_argument('--mode', choices=('debug', 'profile', 'release'), required=True)
    parser.add_argument('--package', default='cn.com.omnimind.bot')
    parser.add_argument('--component', default='cn.com.omnimind.bot/.activity.LauncherActivity')
    parser.add_argument('--runs', type=int, default=5)
    parser.add_argument('--warmups', type=int, default=1)
    args = parser.parse_args()
    if args.runs < 3 or args.warmups < 0:
        parser.error('Use at least three measured runs and non-negative warmups')
    if args.output.exists():
        parser.error('Output exists; retain previous measurements and choose another file')

    def adb(*command):
        return subprocess.check_output(['adb', '-s', args.serial, *command], text=True, timeout=45).strip()

    def environment():
        battery = adb('shell', 'dumpsys', 'battery')
        thermal = adb('shell', 'dumpsys', 'thermalservice')
        return {
            'battery_temperature_tenths_c': re.findall(r'^\s*temperature: (\d+)', battery, re.M),
            'thermal_status': re.findall(r'^Thermal Status: (.*)', thermal, re.M),
        }

    with args.apk.open('rb') as apk_file:
        apk_sha = hashlib.file_digest(apk_file, 'sha256').hexdigest()
    with ZipFile(args.apk) as apk:
        names = apk.namelist()
        is_aot = any(name.endswith('/libapp.so') for name in names)
        has_kernel = 'assets/flutter_assets/kernel_blob.bin' in names
    if (args.mode == 'debug' and (not has_kernel or is_aot)) or (args.mode != 'debug' and (not is_aot or has_kernel)):
        raise RuntimeError('APK Flutter compiler mode does not match --mode')
    installed_paths = adb('shell', 'pm', 'path', args.package).splitlines()
    if len(installed_paths) != 1 or not installed_paths[0].startswith('package:'):
        raise RuntimeError('Expected one monolithic installed APK')
    installed_sha = adb('shell', 'sha256sum', installed_paths[0][8:]).split()[0]
    if installed_sha != apk_sha:
        raise RuntimeError('Installed APK does not match --apk; install the requested artifact first')

    result = {
        'label': args.label, 'mode': args.mode,
        'apk_sha256': apk_sha,
        'apk_bytes': args.apk.stat().st_size,
        'device': adb('shell', 'getprop', 'ro.product.model'),
        'android_sdk': adb('shell', 'getprop', 'ro.build.version.sdk'),
        'build_fingerprint': adb('shell', 'getprop', 'ro.build.fingerprint'),
        'component': args.component,
        'metric': 'am start -W TotalTime; process cold, existing app data and OS disk caches retained',
        'started_at': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
        'environment_before': environment(), 'trials': [],
    }
    for index in range(args.warmups + args.runs):
        adb('shell', 'am', 'force-stop', args.package)
        time.sleep(1)
        launch = adb('shell', 'am', 'start', '-W', '-n', args.component)
        fields = dict(re.findall(r'^(\w+):\s*(.*)$', launch, re.M))
        if fields.get('Status') != 'ok' or fields.get('LaunchState') != 'COLD':
            raise RuntimeError(f'Expected successful cold launch: {launch}')
        time.sleep(3)
        pid = adb('shell', 'pidof', args.package)
        if not pid.isdigit():
            raise RuntimeError('Expected one live application process')
        logs = adb('logcat', '-d', '--pid=' + pid, '-v', 'brief', '[Omni]AppStartup:V', 'flutter:I', '*:S')
        # Only static labels with integer durations; no arbitrary error/log text.
        timings = re.findall(r'((?:Startup theme and locale cost|Credentials and providers cost|Workspace and built-in skills cost|App onCreate total cost|MainActivity onCreate total cost|Main engine created, cost|Bootstrap to first rasterized frame): \d+ms)', logs)
        trial = {'index': index, 'warmup': index < args.warmups, 'launch': fields, 'timings': timings}
        result['trials'].append(trial)
        print(json.dumps(trial, ensure_ascii=False), flush=True)
    values = [int(t['launch']['TotalTime']) for t in result['trials'] if not t['warmup']]
    result['summary_ms'] = {'samples': values, 'median': statistics.median(values), 'min': min(values), 'max': max(values), 'mean': statistics.mean(values)}
    result['environment_after'] = environment()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps(result['summary_ms']))


if __name__ == '__main__':
    main()
