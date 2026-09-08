import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import test from "node:test";

const repositoryRoot = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  "..",
);
const memoryServicePath = path.join(
  repositoryRoot,
  "app/src/main/java/cn/com/omnimind/bot/agent/workspace/memory/WorkspaceMemoryService.kt",
);
const orchestratorPath = path.join(
  repositoryRoot,
  "app/src/main/java/cn/com/omnimind/bot/agent/runtime/AgentOrchestrator.kt",
);
const memoryIndexPath = path.join(
  repositoryRoot,
  "app/src/main/java/cn/com/omnimind/bot/agent/workspace/memory/MemoryIndex.kt",
);
const memoryToolHandlerPath = path.join(
  repositoryRoot,
  "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/MemoryToolHandler.kt",
);

async function memoryServiceSource() {
  return readFile(memoryServicePath, "utf8");
}

async function orchestratorSource() {
  return readFile(orchestratorPath, "utf8");
}

async function memoryIndexSource() {
  return readFile(memoryIndexPath, "utf8");
}

async function memoryToolHandlerSource() {
  return readFile(memoryToolHandlerPath, "utf8");
}

test("memory rollup keeps complete persisted memory and an unbounded candidate schema", async () => {
  const source = await memoryServiceSource();

  assert.match(source, /val longMemory = readLongTermMemory\(\)\.trim\(\)/);
  assert.match(source, /val todayDaily = summarizeTodayShortMemory\(\)/);
  assert.match(source, /val longTermSnapshot = readLongTermMemory\(\)\.trim\(\)/);
  assert.match(source, /val dailyBlock = dailyLines\.joinToString\("\\n"\) \{ "- \$it" \}/);
  assert.match(source, /Existing long-term memory \(to avoid duplicates\):\n\s+\$longTermBlock/);
  assert.match(source, /fun appendLongTermMemory\(text: String\): Boolean/);
  assert.match(source, /decodeBool\(KEY_ROLLUP_ENABLED, false\) \?: false/);
  assert.match(source, /return items\n\s*}/);

  assert.doesNotMatch(source, /truncateText\(/);
  assert.doesNotMatch(source, /MAX_ROLLUP_LONG_TERM_CANDIDATES/);
  assert.doesNotMatch(source, /put\("maxItems"/);
  assert.doesNotMatch(source, /appendDailyMemoryIfNovel/);
  assert.doesNotMatch(source, /upsertLongTermMemory/);
  assert.doesNotMatch(source, /isDuplicateNormalized/);
  assert.doesNotMatch(source, /\.take\((?:120|140|2400|2600|12000|30|8)\)/);
});

test("memory writes are explicit and scheduled rollup is opt-in", async () => {
  const [memorySource, orchestrator] = await Promise.all([
    memoryServiceSource(),
    orchestratorSource(),
  ]);

  assert.match(memorySource, /fun appendDailyMemory\(/);
  assert.match(memorySource, /fun appendLongTermMemory\(text: String\): Boolean/);
  assert.match(memorySource, /decodeBool\(KEY_ROLLUP_ENABLED, false\) \?: false/);
  assert.doesNotMatch(memorySource, /appendDailyMemoryIfNovel/);
  assert.doesNotMatch(memorySource, /upsertLongTermMemory/);
  assert.doesNotMatch(memorySource, /isDuplicateNormalized/);
  assert.doesNotMatch(orchestrator, /resolveFailureLearningAfterSuccess/);
});

test("an explicit long-term memory write has no host size quota or truncation", async () => {
  const [memorySource, handlerSource] = await Promise.all([
    memoryServiceSource(),
    memoryToolHandlerSource(),
  ]);

  assert.match(handlerSource, /"memory_upsert_longterm"\s*->\s*\{/);
  assert.match(handlerSource, /env\.workspaceMemoryService\.appendLongTermMemory\(text\)/);
  assert.match(memorySource, /fun appendLongTermMemory\(text: String\): Boolean/);
  assert.match(memorySource, /file\.appendText\("- \$normalized\\n"\)/);
  const upsertBranch = handlerSource.slice(
    handlerSource.indexOf('"memory_upsert_longterm" -> {'),
    handlerSource.indexOf('"memory_rollup_day" -> {'),
  );
  const appendLongTermMemory = memorySource.slice(
    memorySource.indexOf("fun appendLongTermMemory(text: String): Boolean"),
    memorySource.indexOf("fun buildPromptContext()"),
  );
  assert.doesNotMatch(upsertBranch, /MAX_(?:MEMORY|LONG_TERM)|max(?:Chars|Length|Bytes)|truncate|\.take\(/i);
  assert.doesNotMatch(appendLongTermMemory, /MAX_(?:MEMORY|LONG_TERM)|max(?:Chars|Length|Bytes)|truncate|\.take\(/i);
});

test("memory prompt does not maintain a second capped index summary", async () => {
  const [memorySource, indexSource] = await Promise.all([
    memoryServiceSource(),
    memoryIndexSource(),
  ]);

  assert.doesNotMatch(memorySource, /longTermIndexSummary|summaryForPrompt/);
  assert.match(indexSource, /fun list\(limit: Int\? = null\)/);
  assert.doesNotMatch(indexSource, /summaryForPrompt|maxEntries: Int = 80|maxCharsPerEntry: Int = 120/);
  assert.match(indexSource, /if \(limit != null && entries\.size >= limit\) break/);
});

test("a tool failure remains a visible turn result and never becomes hidden memory", async () => {
  const [memorySource, orchestrator] = await Promise.all([
    memoryServiceSource(),
    orchestratorSource(),
  ]);

  assert.match(orchestrator, /appendToolResultMessage\(/);
  assert.doesNotMatch(orchestrator, /SelfImprovingSkillFailureHook/);
  assert.doesNotMatch(orchestrator, /FailureLearningHookPayload/);
  assert.doesNotMatch(memorySource, /SelfImprovingSkillFailureHook/);
  assert.doesNotMatch(memorySource, /skill:self-improving-agent\/ERRORS/);
});
