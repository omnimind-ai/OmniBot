import { execFileSync, spawn } from "node:child_process";
import { pathToFileURL } from "node:url";

const quote = (value) => `'${value.replaceAll("'", "'\\''")}'`;

/** Start the same app-owned PRoot launcher used by LocalAcpRuntime. */
export function deviceProcess(serial, distribution, command) {
  if (!/^emulator-\d+$/.test(serial || "")) throw new Error("An explicit disposable emulator is required.");
  if (!["alpine", "ubuntu"].includes(distribution)) throw new Error("Expected alpine or ubuntu.");
  const adb = process.env.ADB || "adb";
  const pkg = "cn.com.omnimind.bot";
  const apk = execFileSync(adb, ["-s", serial, "shell", "pm", "path", pkg], { encoding: "utf8", timeout: 15000 }).trim();
  if (!/^package:\/[^\n]+\/base\.apk$/.test(apk)) throw new Error("Expected one installed debug APK.");
  const nativeDir = apk.slice(8, -9) + "/lib/arm64";
  const prefix = `/data/user/0/${pkg}`;
  const script = `set -eu
cd ${quote(prefix)}
mkdir -p cache/pi-acp-probe tmp
export PREFIX=${quote(prefix)} LINKER=/system/bin/linker64
export LD_LIBRARY_PATH="$PREFIX/local/lib" PROOT_LOADER=${quote(`${nativeDir}/libproot-loader.so`)}
export PROOT_TMP_DIR="$PREFIX/cache/pi-acp-probe" TMPDIR="$PREFIX/tmp"
export OMNIBOT_TERMINAL_DISTRIBUTION=${distribution} OMNIBOT_HEADLESS=1
if [ -s "$PREFIX/local/omnibot-resolv.conf" ]; then
  export OMNIBOT_DNS_FILE="$PREFIX/local/omnibot-resolv.conf"
fi
exec /system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc ${quote(`export HOME=/root; export PATH=/root/.npm-global/bin:$PATH; ${command}`)}
`;
  return spawn(adb, ["-s", serial, "shell", "-T", "run-as", pkg, "sh", "-c", quote(script)], { stdio: ["pipe", "pipe", "pipe"] });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [serial, distribution, command] = process.argv.slice(2);
  if (!command) throw new Error("Usage: node scripts/device-process.mjs emulator-N alpine|ubuntu 'command'");
  const child = deviceProcess(serial, distribution, command);
  process.stdin.pipe(child.stdin);
  child.stdout.pipe(process.stdout);
  child.stderr.pipe(process.stderr);
  child.on("error", (error) => { console.error(error.message); process.exitCode = 1; });
  child.on("close", (code) => { process.exitCode = code ?? 1; process.stdin.destroy(); });
}
