# Installed Release artifact audit

This independent instrumentation APK executes fixtures against the **installed,
minified target DEX** through the target ClassLoader. It does not compile or
package target classes, Gson, Room, Flutter, or AndroidX Test. There is no Gradle
dependency on `:app`, and building the harness does not rebuild the target.

The initial target for the 2026-10-29 audit is `developStandardRelease`, with
Flutter release AOT, R8/resource shrinking, compressed DEX, and local debug
signing. Its package is `cn.com.omnimind.bot`; it is not debuggable. The archived
APK is `/tmp/omnibot-apk-size-20261029/release-compressed.apk`, SHA-256:

```text
e48f8130e054cae47a1f4317c55f73d3bdceb59d34520d9c4a31592e1adf21fe
```

The completed audit used the same harness for both targets: the original APK
passed 19/25 fixtures; the fixed APK passed 25/25. The fixed APK SHA-256 is
`7f1c2ad5c213ca90e45774c809365f746557c2f7da0cb3cbf6ef0a7f6f3fd4a4`.
See [the audit report](../../../docs/release-shrinking-audit-2026-10-29.md) for
results and coverage limits. These historical passes do not validate later APKs.

The runner reports `targetApkSha256`. Compare it with the intended APK before
attributing results to that build. A rebuilt target requires a newly recorded
hash; the build script's `--target-apk` checks the certificate, not APK identity.

## Isolation and coverage

The manifest selects `cn.com.omnimind.bot:release_audit`. Android starts the
first named instrumentation process and selects providers by process name;
the target manifest need not declare a component in that process. The runner
substitutes plain `android.app.Application` and verifies its identity, the
process name, absence of providers, and the target's non-debuggable flag before
fixtures. Do not remove these guards or silently fall back to the main process:
the normal app initializes credentials, databases, workers, and cleanup jobs.

Fixtures cover synthetic Gson records/defaults/legacy aliases, the bundled skill
manifest, construction of the 14 registered Flutter plugin classes, a native
PTY subprocess running only `/system/bin/sh -c 'exit 7'`, and the generated Room
schema plus temporary SQL in an in-memory database. They do not open stored
conversations, change providers/credentials, attach plugins to a Flutter engine,
start Dart, activate a camera, run agents, or initialize a terminal distribution.
This coverage does not establish every UI, network, camera, or model workflow.

`am instrument` normally stops the target's current process. Use an idle device;
process interruption is separate from persistent-data isolation. Do not clear
app data, disable providers, or uninstall/reinstall the target for this audit.

## Build and run

Use Python 3.10+, a JDK, an installed Android SDK, and the existing standard
`~/.android/debug.keystore` (`androiddebugkey`). The target must have the same
debug certificate. The harness does not read release-signing credentials and
cannot instrument a differently signed production installation.

From the repository root, copy the installed base APK for identity/signature
checks, then build only the harness:

```bash
AUDIT_OUT=/tmp/omnibot-release-audit-20261029/harness
mkdir -p "$AUDIT_OUT"
AUDIT_BASE_APK="$(adb shell pm path cn.com.omnimind.bot | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
adb pull "$AUDIT_BASE_APK" "$AUDIT_OUT/installed-base.apk"
shasum -a 256 "$AUDIT_OUT/installed-base.apk"

python3 scripts/verification/release_artifact/build.py \
  --output "$AUDIT_OUT" \
  --target-apk "$AUDIT_OUT/installed-base.apk"
```

`--sdk` defaults to `local.properties`'s `sdk.dir`, then SDK environment
variables. `--output` must be absolute. The source defaults to the adjacent
`ReleaseArtifactInstrumentation.java`. The pipeline is `javac → d8 → aapt2 →
zipalign → apksigner`; output is `release-artifact-audit.apk` and `build.json`.
The latter records the harness hash, certificate, and tool versions. The script
does not install or run anything.

```bash
adb install -t -r "$AUDIT_OUT/release-artifact-audit.apk"
adb shell am instrument -w -r \
  cn.com.omnimind.releaseaudit/cn.com.omnimind.releaseaudit.ReleaseArtifactInstrumentation
```

On hosts with several devices, add the intended `-s SERIAL` to each `adb`
command. Read the returned `audit_json`, its target hash, every case, and
`failed`; the shell command's exit status alone is not test acceptance. Only
remove the test package when finished:

```bash
adb uninstall cn.com.omnimind.releaseaudit
```

## Room reflection boundary

The target retains `RoomDatabase.Builder(Context, Class, String)`, `build()`,
and `RoomDatabase.getOpenHelper()`. Use the builder with `name=null`, check the
helper's `getDatabaseName()` is null before opening, and check the opened
SQLite database path is `:memory:` before fixture SQL. Close the helper in a
`finally` block. `Room.inMemoryDatabaseBuilder()` and `RoomDatabase.close()` are
not retained in this APK; their absence is legitimate shrinking. DAO classes
and signatures can also be merged or rewritten, so source-level reflection
names alone are not evidence of erroneous removal.
