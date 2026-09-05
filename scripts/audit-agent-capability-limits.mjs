// Discovery aid, not a proof of unbounded execution. Review each match in context.
// Walk source directories directly: gitignore may exclude the runtime/ package.
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const roots = [
  "app/src/main", "assists/src/main", "baselib/src/main",
  "ReTerminal", "ui/lib", "webchat/src",
];
const excluded = new Set(["build", ".gradle", ".git", "node_modules", ".dart_tool"]);
const patterns = [
  ["content/count", /maxChars|maxBytes|maxLength|maxDepth|MAX_[A-Z_]*(?:CHAR|BYTE|TOKEN|COUNT|SIZE|DEPTH|ROUND|DIMENSION)|truncat|\.take(?:Last)?\(\s*\d|coerceAtMost|substring\(/i],
  ["policy/lifecycle", /maxRounds|maxIterations|retry|timeout|allowlist|denylist|whitelist|blacklist|allowedTools|disabledTools|stopReason|finishReason|permissionRequired/i],
];
const findings = [];
async function walk(relative) {
  let entries;
  try { entries = await readdir(path.join(root, relative), { withFileTypes: true }); }
  catch (error) { if (error.code === "ENOENT") return; throw error; }
  for (const entry of entries.sort((a, b) => a.name.localeCompare(b.name))) {
    if (excluded.has(entry.name) || entry.isSymbolicLink()) continue;
    const file = path.join(relative, entry.name);
    if (entry.isDirectory()) { await walk(file); continue; }
    if (!/\.(kt|java|dart|ts|tsx|js|mjs)$/.test(file)) continue;
    const lines = (await readFile(path.join(root, file), "utf8")).split("\n");
    for (const [index, line] of lines.entries()) {
      for (const [category, pattern] of patterns) {
        if (pattern.test(line)) findings.push({ file, line: index + 1, category, source: line.trim() });
      }
    }
  }
}
for (const directory of roots) await walk(directory);
if (process.argv.includes("--json")) {
  process.stdout.write(JSON.stringify({ kind: "review-candidates-not-confirmed-limits", findings }, null, 2) + "\n");
} else {
  for (const hit of findings) console.log(`${hit.file}:${hit.line} [${hit.category}] ${hit.source}`);
  console.log(`\n${findings.length} candidates. Explicit request ranges, protocol rules, identity hashes and UI layout are not automatically capability limits.`);
}
