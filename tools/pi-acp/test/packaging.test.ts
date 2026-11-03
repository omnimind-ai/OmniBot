import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { gzipSync, gunzipSync } from "node:zlib";
import { matchesAndroidInstaller } from "../scripts/installer-validation.mjs";

const files = {
  "package-lock.json": '{"lockfileVersion":3}\n',
  "LICENSE": "Repository license\n",
  "dist/index.js": 'console.log("Pi ACP");\n',
};
const archive = JSON.stringify(files);
const packagePayload = (text: string, level: number = 9, os: number = 19) => {
  const gzip = gzipSync(text, { level });
  gzip[9] = os;
  return gzip.toString("base64");
};
const installer = (payload: string) => `set -eu
PI_ACP_PACKAGE_DIR=/runtime/versions/content-digest
node <<'EXTRACT'
const files = JSON.parse(require('node:zlib').gunzipSync(Buffer.from('${payload}', 'base64')).toString());
EXTRACT
node "$PI_ACP_PACKAGE_DIR/dist/index.js" --health
`;
const expected = installer(packagePayload(archive));

test("packaging accepts identical files across gzip platforms and compression levels", () => {
  for (const level of [0, 1, 9]) {
    for (const os of [3, 10, 19, 255]) {
      const candidate = installer(packagePayload(archive, level, os));
      assert.equal(matchesAndroidInstaller(candidate, expected, archive), true);
    }
  }
  assert.notEqual(installer(packagePayload(archive, 9, 3)), expected);
});

test("packaging rejects changed source, dependencies and license bytes", () => {
  for (const file of Object.keys(files)) {
    const modified = { ...files, [file]: "changed\n" };
    assert.equal(matchesAndroidInstaller(installer(packagePayload(JSON.stringify(modified))), expected, archive), false, file);
  }
  assert.equal(matchesAndroidInstaller(installer(packagePayload(JSON.stringify({ ...files, "unexpected.js": "extra" }))), expected, archive), false);
});

test("packaging still verifies installer commands and content revision", () => {
  for (const candidate of [
    expected.replace("--health", "--version"),
    expected.replace("content-digest", "old-digest"),
    expected.replace("gunzipSync", "inflateSync"),
    `${expected}\nnode unexpected.js\n`,
  ]) assert.equal(matchesAndroidInstaller(candidate, expected, archive), false);
});

test("packaging rejects absent, ambiguous or damaged payloads", () => {
  for (const candidate of [
    installer(""),
    installer(Buffer.from("not gzip").toString("base64")),
    installer(packagePayload(archive).slice(0, -12)),
    `${expected}\nBuffer.from('AA==', 'base64')\n`,
  ]) assert.equal(matchesAndroidInstaller(candidate, expected, archive), false);
});

test("the shipped Android installer verifies after Linux-style recompression", () => {
  const shipped = readFileSync(new URL("../../../app/src/main/assets/acp/install/pi.sh", import.meta.url), "utf8");
  const payload = shipped.match(/Buffer\.from\('([A-Za-z0-9+/=]+)', 'base64'\)/)![1];
  const contents = gunzipSync(Buffer.from(payload, "base64")).toString();
  const linux = shipped.replace(payload, packagePayload(contents, 1, 3));
  assert.notEqual(linux, shipped);
  assert.equal(matchesAndroidInstaller(shipped, linux, contents), true);
  assert.equal(matchesAndroidInstaller(linux, shipped, contents), true);
});
