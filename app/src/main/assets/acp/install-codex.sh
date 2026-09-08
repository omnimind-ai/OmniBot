#!/bin/sh
set -eu
npm install -g --prefix /root/.npm-global --no-audit --no-fund @openai/codex@latest @agentclientprotocol/codex-acp@1.10.0

# Temporary, version-checked upstream compatibility patch. Official item/completed
# carries authoritative text even when a Responses gateway omits text deltas.
# Keep projection inside CodexEventHandler: no host replay or second lifecycle.
node - /root/.npm-global/lib/node_modules/@agentclientprotocol/codex-acp <<'OOB_CODEX_PATCH'
const fs = require('node:fs');
const path = require('node:path');
const root = process.argv[2];
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
if (pkg.version !== '1.10.0') throw Error('Unsupported Codex ACP completion patch version');
const file = path.join(root, 'dist/index.js');
let source = fs.readFileSync(file, 'utf8');
const marker = '// OOB Codex completed message projection v1';
if (source.includes(marker)) process.exit(0);
function replaceOnce(before, after) {
  if (source.split(before).length !== 2) throw Error('Codex ACP completion patch source mismatch');
  source = source.replace(before, after);
}
replaceOnce('  agentMessagePhases = /* @__PURE__ */ new Map();',
  `  ${marker}\n  agentMessageTextByItem = new Map();\n  agentMessagePhases = /* @__PURE__ */ new Map();`);
replaceOnce('  async createTextEvent(event) {\n',
  `  async createTextEvent(event) {
    this.agentMessageTextByItem.set(event.itemId, (this.agentMessageTextByItem.get(event.itemId) ?? "") + event.delta);
`);
replaceOnce(`      case "agentMessage":
        this.rememberAgentMessagePhase(event.item);
        return null;
      case "plan": {`,
`      case "agentMessage": {
        this.rememberAgentMessagePhase(event.item);
        const emitted = this.agentMessageTextByItem.get(event.item.id) ?? "";
        this.agentMessageTextByItem.delete(event.item.id);
        const text = event.item.text;
        if (typeof text !== "string" || text === emitted) return null;
        if (!text.startsWith(emitted)) throw Error("Codex completed message differs from streamed text");
        return createAgentTextMessageChunk(text.slice(emitted.length), event.item.id, createCodexMessagePhaseMeta(event.item.phase));
      }
      case "plan": {`);
fs.writeFileSync(file, source);
OOB_CODEX_PATCH
