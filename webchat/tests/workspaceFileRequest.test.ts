import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const appSourcePath = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "../src/App.tsx",
);

test("opening a workspace file requests its complete content by default", async () => {
  const appSource = await readFile(appSourcePath, "utf8");
  const openFile = appSource.slice(
    appSource.indexOf("async function openWorkspaceFile"),
    appSource.indexOf("function workspaceParentPath"),
  );

  assert.match(openFile, /query:\s*\{\s*path\s*\}/);
  assert.doesNotMatch(openFile, /maxChars/);
});
