#!/usr/bin/env node
import { Console } from "node:console";
import { readFileSync } from "node:fs";
import { Readable, Writable } from "node:stream";

function installedPiVersion(): string {
  const installed = JSON.parse(readFileSync(new URL("../node_modules/@earendil-works/pi-coding-agent/package.json", import.meta.url), "utf8"));
  const version = installed?.version;
  if (typeof version !== "string" || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/.test(version)) {
    throw new Error("Installed Pi SDK package has no valid version.");
  }
  return version;
}

const healthCheck = process.argv.includes("--health");
if (process.argv.includes("--version") && !healthCheck) {
  // Package inventory must not load the SDK or depend on Provider/runtime health.
  process.stdout.write(`${installedPiVersion()}\n`);
} else {
  if (healthCheck) {
    const [major, minor] = process.versions.node.split(".").map(Number);
    if (major < 22 || (major === 22 && minor < 19)) throw new Error("Pi requires Node.js >= 22.19.0.");
  } else {
    // Only ACP records may use stdout, including during SDK/extension startup.
    globalThis.console = new Console(process.stderr, process.stderr);
  }
  // Loading the complete runtime remains part of the structural health check.
  const [{ ndJsonStream }, { PI_VERSION, readLaunchConfig, VERSION }, { createPiAgent }] = await Promise.all([
    import("@agentclientprotocol/sdk/unstable"), import("./config.js"), import("./server.js"),
  ]);
  if (healthCheck) {
    if (installedPiVersion() !== PI_VERSION) throw new Error(`Pi SDK version mismatch: expected ${PI_VERSION}.`);
    process.stdout.write(`omnibot-pi-acp ${VERSION} (Pi ${PI_VERSION})\n`);
  } else {
    const runtime = createPiAgent(readLaunchConfig());
    const connection = runtime.app.connect(ndJsonStream(
      Writable.toWeb(process.stdout), Readable.toWeb(process.stdin),
    ));
    let shutdown: Promise<void> | undefined;
    const stop = () => shutdown ??= runtime.close().finally(() => connection.close());
    process.once("SIGINT", () => { void stop(); });
    process.once("SIGTERM", () => { void stop(); });
    await connection.closed;
    await stop();
  }
}
