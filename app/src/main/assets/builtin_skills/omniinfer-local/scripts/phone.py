#!/usr/bin/env python3
"""Download a pinned APK or explicitly build upstream on this phone; serve for installation."""
import argparse
import fcntl
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile

SOURCE_REV = '6ad984d414d4b7c27ab5740e63e8ccb1b33e4046'
LLAMA_REV = '30b6a755e29692e8bc8e072885325716a2fee70f'
PACKAGE = 'cn.com.omnimind.localinfer'
ASSETS = {
    'host': ('OmniInfer-local-arm64-v0.1.0-test.1.apk',
             'https://api.github.com/repos/omnimind-ai/OmniBot/releases/assets/570236640',
             'c8152e7094b36de5b6ad32765c97b4e408c27a45a34917163593db571a93fcb2'),
    'ndk': ('android-ndk-r29-aarch64.tar.xz',
            'https://api.github.com/repos/lzhiyong/termux-ndk/releases/assets/512844358',
            '02e10e4ddfe8deaeb0bd0cf29d04c981ed5bc8a5d6b560ebb9e7661f472d684b'),
    'sdk': ('android-sdk-aarch64.tar.xz',
            'https://api.github.com/repos/lzhiyong/termux-ndk/releases/assets/512851526',
            '8a23d2a10897ad74e34e10d7d2647ed450fad194d622b8b46e1ebd44557171ad'),
    'gradle': ('gradle-8.9-bin.zip', 'https://services.gradle.org/distributions/gradle-8.9-bin.zip',
               'd725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab'),
    'source': ('omniinfer-source.tar.gz', f'https://codeload.github.com/omnimind-ai/OmniInfer/tar.gz/{SOURCE_REV}',
               'f3d56be5cc4390945c3436bc11559030df18e67c065790786035c25451c72fe1'),
    'llama': ('llama-source.tar.gz', f'https://codeload.github.com/ggml-org/llama.cpp/tar.gz/{LLAMA_REV}',
              'a51455ac63a77ef7416d9e2c4b372f1653c4c4e5da7631d67d44d949ca167f8c'),
}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for data in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(data)
    return digest.hexdigest()


def emit(**value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def check_terminal_loader():
    loader = os.environ.get('PROOT_LOADER')
    if loader and not Path(loader).is_file():
        raise ValueError('PRoot loader no longer exists; the host app may have been updated. '
                         'Open a fresh Ubuntu terminal session and resume from the verified cache.')


def run(args, **kwargs):
    check_terminal_loader()
    try:
        subprocess.run([str(arg) for arg in args], check=True, **kwargs)
    except (OSError, subprocess.CalledProcessError):
        check_terminal_loader()
        raise


def fetch(key, cache):
    name, url, expected = ASSETS[key]
    target = cache / name
    if target.is_file():
        if sha256(target) == expected:
            emit(stage='download', component=key, cached=True)
            return target
        raise ValueError(f'Existing {name} failed SHA-256; preserved for inspection')
    partial = target.with_name(target.name + '.part')
    emit(stage='download', component=key, cached=False)
    args = ['curl', '-fL', '--retry', '2', '--retry-delay', '2', '--connect-timeout', '15',
            '--max-time', '1800', '-C', '-', '-o', partial]
    if url.startswith('https://api.github.com/'):
        args += ['-H', 'Accept: application/octet-stream']
    run(args + [url])
    if sha256(partial) != expected:
        raise ValueError(f'{name} SHA-256 mismatch; partial file preserved')
    partial.replace(target)
    return target


def unpack(archive, destination, strip_root=False, receipt=None):
    """Verify paths and atomically publish a complete tree; interrupted staging is never trusted."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.omniinfer-extract-', dir=destination.parent) as temporary:
        stage = Path(temporary)
        if archive.suffix == '.zip':
            with zipfile.ZipFile(archive) as bundle:
                for member in bundle.infolist():
                    path = Path(member.filename)
                    if path.is_absolute() or '..' in path.parts:
                        raise ValueError('Unsafe ZIP entry')
                bundle.extractall(stage)
                for member in bundle.infolist():
                    if member.external_attr >> 16 & 0o111:
                        (stage / member.filename).chmod(0o755)
        else:
            with tarfile.open(archive) as bundle:
                bundle.extractall(stage, filter='data')
        entries = list(stage.iterdir())
        origin = entries[0] if strip_root else stage
        if strip_root and (len(entries) != 1 or not origin.is_dir()):
            raise ValueError('Source archive must have one root directory')
        if receipt:
            (origin / receipt[0]).write_text(receipt[1] + '\n')
        if destination.exists():
            if destination.is_dir() and not any(destination.iterdir()):
                destination.rmdir()
            else:
                raise ValueError(f'Extraction would overwrite {destination}')
        origin.rename(destination)


def ensure_unpacked(archive, destination, expected, strip_root=False):
    receipt = destination / ('.archive-' + archive.name + '.sha256')
    wanted = sha256(archive)
    if receipt.is_file() and receipt.read_text().strip() == wanted and expected.exists():
        return
    emit(stage='extract', archive=archive.name)
    unpack(archive, destination, strip_root, (receipt.name, wanted))
    if not expected.exists():
        raise ValueError(f'Archive missing expected entry: {expected}')
    emit(stage='extracted', archive=archive.name)


def check_host():
    if platform.machine() not in ('aarch64', 'arm64') or not Path('/system/bin/linker64').exists():
        raise ValueError('Run inside the Android phone ARM64 Ubuntu terminal')
    release = Path('/etc/os-release').read_text()
    if '\nID=ubuntu\n' not in '\n' + release:
        raise ValueError('Use the existing Ubuntu terminal environment for this build')
    if sys.version_info < (3, 12):
        raise ValueError('Python 3.12+ required for safe archive extraction')


def install_dependencies():
    names = ('java', 'javac', 'cmake', 'ninja', 'unzip', 'zip', 'curl')
    if all(shutil.which(name) for name in names):
        return
    env = dict(os.environ, DEBIAN_FRONTEND='noninteractive')
    run(['apt-get', 'update'], env=env)
    run(['apt-get', 'install', '-y', '--no-install-recommends', 'openjdk-17-jdk-headless',
         'cmake', 'ninja-build', 'unzip', 'zip', 'curl', 'ca-certificates'], env=env)


def build_components(work):
    source = work / 'source'
    toolchains = work / 'toolchains'
    return [
        ('sdk', toolchains / 'android-sdk', toolchains / 'android-sdk/build-tools/37.0.0/aapt2'),
        ('ndk', toolchains / 'android-ndk-r29', toolchains / 'android-ndk-r29/toolchains/llvm/prebuilt/linux-x86_64/bin/clang'),
        ('gradle', toolchains / 'gradle-8.9', toolchains / 'gradle-8.9/bin/gradle'),
        ('source', source, source / 'android/omniinfer-server/build.gradle.kts'),
        ('llama', source / 'framework/llama.cpp', source / 'framework/llama.cpp/CMakeLists.txt'),
    ]


def component_ready(key, destination, expected):
    name, _, digest = ASSETS[key]
    receipt = destination / ('.archive-' + name + '.sha256')
    return receipt.is_file() and receipt.read_text().strip() == digest and expected.is_file()


def prepare_components(work, cache):
    components = build_components(work)
    # Cold setup needs room for archives and atomic extraction; a verified
    # rebuild needs only new build outputs. Never waive the extraction reserve
    # based on a build-result.json or an incomplete directory alone.
    reserve_gib = 1 if all(component_ready(*item) for item in components) else 5
    if shutil.disk_usage(work).free < reserve_gib * 1024**3:
        raise ValueError(f'At least {reserve_gib} GiB free storage required before building')
    for key, destination, expected in components:
        if component_ready(key, destination, expected):
            emit(stage='extracted', component=key, cached=True)
        else:
            ensure_unpacked(fetch(key, cache), destination, expected, True)


def validate_apk(apk):
    with zipfile.ZipFile(apk) as package:
        names = set(package.namelist())
    required = {'lib/arm64-v8a/libomniinfer-jni.so',
                'lib/arm64-v8a/libggml-cpu-android_armv8.0_1.so',
                'lib/arm64-v8a/libggml-cpu-android_armv8.2_2.so'}
    missing = required - names
    if missing:
        raise ValueError('APK missing upstream native CPU backends: ' + ', '.join(sorted(missing)))


def build(work):
    check_host()
    work.mkdir(parents=True, exist_ok=True)
    with (work / 'build.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        install_dependencies()
        cache = Path.home() / '.cache/omniinfer-phone'
        cache.mkdir(parents=True, exist_ok=True)
        toolchains = work / 'toolchains'
        prepare_components(work, cache)
        source = work / 'source'
        sdk = toolchains / 'android-sdk'
        ndk = toolchains / 'android-ndk-r29'
        gradle = toolchains / 'gradle-8.9/bin/gradle'
        for args in [['java', '-version'], ['javac', '-version'],
                     [sdk / 'build-tools/37.0.0/aapt2', 'version'],
                     [sdk / 'cmake/bin/cmake', '--version'],
                     [ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/clang', '--version']]:
            run(args)
        template = Path(__file__).resolve().parents[1] / 'assets/phone-host'
        host = work / 'host'
        shutil.copytree(template, host, dirs_exist_ok=True)
        (host / 'local.properties').write_text(f'sdk.dir={sdk}\ncmake.dir={sdk}/cmake\n')
        with (host / 'gradle.properties').open('a') as config:
            config.write(f'\nomniinfer.source={source}\nomniinfer.ndk={ndk}\nandroid.aapt2FromMavenOverride={sdk}/build-tools/37.0.0/aapt2\n')
        emit(stage='build', source_revision=SOURCE_REV, llama_revision=LLAMA_REV)
        env = dict(os.environ, CMAKE_BUILD_PARALLEL_LEVEL='2')
        run([gradle, '--no-daemon', '--console=plain', ':app:assembleDebug'], cwd=host, env=env)
        apk = host / 'app/build/outputs/apk/debug/app-debug.apk'
        validate_apk(apk)
        result = dict(stage='apk_built', apk=str(apk), sha256=sha256(apk), package=PACKAGE,
                      source_revision=SOURCE_REV, installed=False, inference_verified=False,
                      component=PACKAGE + '/.MainActivity', operation='start', base_url='http://127.0.0.1:9099/v1')
        (work / 'build-result.json').write_text(json.dumps(result, indent=2) + '\n')
        emit(**result)


def prepare_prebuilt(work):
    """Download only the pinned host APK; never install a compiler or build."""
    work.mkdir(parents=True, exist_ok=True)
    destination = fetch('host', work)
    validate_apk(destination)
    expected = ASSETS['host'][2]
    result = dict(stage='apk_prepared', apk=str(destination.resolve()), sha256=expected,
                  package=PACKAGE, source_revision=SOURCE_REV, distribution='remote-debug',
                  installed=False, inference_verified=False)
    (work / 'build-result.json').write_text(json.dumps(result, indent=2) + '\n')
    emit(**result)


def artifact_handler(apk):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path != '/artifact.apk':
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header('Content-Type', 'application/vnd.android.package-archive')
            self.send_header('Content-Disposition', 'attachment; filename="OmniInfer-local.apk"')
            self.send_header('Content-Length', str(apk.stat().st_size))
            self.end_headers()
            with apk.open('rb') as data:
                shutil.copyfileobj(data, self.wfile)
    return Handler


def serve(work, port):
    result = json.loads((work / 'build-result.json').read_text())
    apk = Path(result['apk']).resolve()
    if not apk.is_relative_to(work.resolve()) or sha256(apk) != result['sha256']:
        raise ValueError('Artifact missing or changed since build')
    with HTTPServer(('127.0.0.1', port), artifact_handler(apk)) as server:
        emit(stage='artifact_serving', url=f'http://127.0.0.1:{server.server_port}/artifact.apk',
             sha256=result['sha256'], package=PACKAGE)
        server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'build', 'serve'])
    parser.add_argument('--workdir', type=Path, default=Path.home() / '.local/share/omniinfer-phone')
    parser.add_argument('--port', type=int, default=0)
    args = parser.parse_args()
    try:
        if args.action == 'prepare':
            prepare_prebuilt(args.workdir.resolve())
        elif args.action == 'build':
            build(args.workdir.resolve())
        else:
            serve(args.workdir.resolve(), args.port)
    except (OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile, tarfile.TarError) as error:
        emit(stage='failed', error=type(error).__name__, detail=str(error))
        return 1
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == '__main__':
    sys.exit(main())
