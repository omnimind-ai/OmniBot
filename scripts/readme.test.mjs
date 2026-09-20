// Documentation regression: node --test scripts/readme.test.mjs
// Guards the bilingual product highlights requested on 2026-09-16.
// Does not assert runtime behavior or replace device acceptance.
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, existsSync} from 'node:fs';

for (const file of ['README.md', 'README.zh-CN.md']) {
  test(`${file}: product highlights remain visible before quick start`, () => {
    const text = readFileSync(new URL(`../${file}`, import.meta.url), 'utf8');
    const intro = text.split('<details>')[0];
    for (const name of ['Kimi Code', 'DeepSeek Harness', 'WebUI', 'Harness', 'Codex', 'Claude Code', 'OpenCode', 'subagent']) {
      assert(intro.includes(name), `Missing product highlight: ${name}`);
    }
    assert.match(intro, file === 'README.md' ? /parallel/ : /并行/);
    for (const [, link] of text.matchAll(/\]\(([^)]+)\)/g)) {
      if (/^(?:https?:|#)/.test(link)) continue;
      assert(existsSync(new URL(`../${link}`, import.meta.url)), `Broken local link: ${link}`);
    }
  });
}
