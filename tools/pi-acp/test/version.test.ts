import { test, type TestContext } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const entrypoint = fileURLToPath(new URL("../dist/index.js", import.meta.url));
const adapterPackage = JSON.parse(readFileSync(new URL("../package.json", import.meta.url), "utf8"));

function metadataOnlyInstall(t: TestContext, metadata?: string): string {
  const root = mkdtempSync(join(tmpdir(), "pi-version-"));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  cpSync(new URL("../dist", import.meta.url), join(root, "dist"), { recursive: true });
  writeFileSync(join(root, "package.json"), JSON.stringify({ type: "module" }));
  if (metadata !== undefined) {
    const packageDir = join(root, "node_modules/@earendil-works/pi-coding-agent");
    mkdirSync(packageDir, { recursive: true });
    writeFileSync(join(packageDir, "package.json"), metadata);
  }
  return join(root, "dist/index.js");
}

function probe(entry: string, ...args: string[]) {
  return spawnSync(process.execPath, [entry, ...args], {
    encoding: "utf8",
    timeout: 5000,
    // No Provider credentials or model configuration are needed for inventory.
    env: { PATH: process.env.PATH },
  });
}

test("version reads actual Pi package metadata without loading any SDK modules", (t) => {
  for (const version of ["1.4.0", "1.3.9", "2.0.0-rc.2+local"]) {
    const entry = metadataOnlyInstall(t, JSON.stringify({ name: "@earendil-works/pi-coding-agent", version }));
    const result = probe(entry, "--version");
    assert.equal(result.error, undefined);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, `${version}\n`);
    assert.equal(result.stderr, "");
  }
});

test("version rejects missing or invalid package metadata instead of reporting the pinned version", (t) => {
  for (const metadata of [undefined, "{", "null", "{}", '{"version":14}', '{"version":""}', '{"version":"unknown"}', '{"version":"1.4.0 garbage"}']) {
    const result = probe(metadataOnlyInstall(t, metadata), "--version");
    assert.equal(result.error, undefined);
    assert.notEqual(result.status, 0, String(metadata));
    assert.equal(result.stdout, "");
    assert.notEqual(result.stderr, "");
  }
});

test("health still loads the SDK even when package metadata is valid", (t) => {
  const entry = metadataOnlyInstall(t, JSON.stringify({ version: "1.4.0" }));
  for (const args of [["--health"], ["--version", "--health"]]) {
    const result = probe(entry, ...args);
    assert.equal(result.error, undefined);
    assert.notEqual(result.status, 0);
    assert.equal(result.stdout, "");
    assert.match(result.stderr, /ERR_MODULE_NOT_FOUND/);
  }
});

test("installed version and health identify the Pi package and adapter separately", () => {
  const expectedPiVersion = adapterPackage.dependencies["@earendil-works/pi-coding-agent"];
  const version = probe(entrypoint, "--version");
  assert.equal(version.status, 0, version.stderr);
  assert.equal(version.stdout, `${expectedPiVersion}\n`);
  const health = probe(entrypoint, "--health");
  assert.equal(health.status, 0, health.stderr);
  assert.equal(health.stdout, `omnibot-pi-acp ${adapterPackage.version} (Pi ${expectedPiVersion})\n`);
});
