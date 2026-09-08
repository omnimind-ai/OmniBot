// Exercise the installed official adapter, without credentials or model requests.
// Usage: node scripts/verify-claude-terminal-output.mjs /path/to/claude-agent-acp/dist/tools.js
import assert from 'node:assert/strict';
import {pathToFileURL} from 'node:url';
import {resolve} from 'node:path';
import {readFileSync} from 'node:fs';

assert(process.argv[2], 'Pass the installed official adapter tools.js path');
const {toolUpdateFromToolResult} = await import(pathToFileURL(resolve(process.argv[2])));
const capabilityMeta = process.argv[3]
  ? JSON.parse(readFileSync(process.argv[3], 'utf8')).clientCapabilityMeta : {};
assert.notEqual(capabilityMeta.terminal_output, true, 'Host must not advertise an unconsumed output extension');
const tool = {name: 'Bash', id: 'cc-output-regression', input: {command: 'python3 -m unittest -v'}};
for (const failed of [false, true]) {
  const marker = failed ? 'FAILED: sample assertion' : 'Ran 6 tests\nOK';
  const result = {type: 'tool_result', tool_use_id: tool.id, content: marker, is_error: failed};
  const extension = toolUpdateFromToolResult(result, tool, true);
  const standard = toolUpdateFromToolResult(result, tool, capabilityMeta.terminal_output === true);
  assert(extension._meta.terminal_output.data.includes(marker));
  assert(standard.content.some(item => item.type === 'content' && item.content.text.includes(marker)));
  assert(!standard._meta?.terminal_output);
  console.log(JSON.stringify({failed, officialStandardOutputPreserved: true}));
}
