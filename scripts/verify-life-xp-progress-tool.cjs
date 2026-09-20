// Compare actual recorded business-tool outputs from one ordered acceptance turn.
// Usage: node scripts/verify-life-xp-progress-tool.cjs terminal-evidence.json
const {readFileSync} = require('node:fs');
const assert = require('node:assert/strict');
const entries = JSON.parse(readFileSync(process.argv[2], 'utf8'));
let habits, history, verified = 0;
for (const entry of entries) {
  if (entry.entryType !== 'tool_event') continue;
  const p = entry.payload;
  if (p.status !== 'success') continue;
  const name = p.toolName || '';
  if (!/^life_xp_autonomous_\d+_/.test(name)) continue;
  const envelope = typeof p.rawResultJson === 'string' ? JSON.parse(p.rawResultJson) : p.rawResultJson;
  const result = envelope?.result;
  if (name.endsWith('_list_habits')) habits = result;
  if (name.endsWith('_get_history')) history = result;
  if (!name.endsWith('_get_user_progress')) continue;
  assert(habits && history, 'Require preceding actual habit/history reads in this turn');
  assert(Array.isArray(habits.rows) && Array.isArray(history.rows));
  // This regression fixture is a small real dataset; a truncated result cannot
  // establish an all-time score. Large datasets need the separate pagination test.
  assert(habits.rows.length < 50 && history.rows.length < 50, 'Potentially truncated evidence');
  const rewards = new Map(habits.rows.map(r => [r.habit_id, Number(r.xp_reward)]));
  const expectedXP = history.rows.reduce((sum, row) => {
    assert(rewards.has(row.habit_id), 'Missing recorded habit definition');
    return sum + rewards.get(row.habit_id);
  }, 0);
  assert.equal(result.rows.length, 1, 'Expected one authoritative progress row');
  const actual = result.rows[0];
  console.log(JSON.stringify({entryId:entry.id, tool:name, expectedXP,
    reportedXP:actual.total_xp, expectedLevel:1+Math.floor(expectedXP/50),
    reportedLevel:actual.current_level, scope:'actual recorded business-tool results'}));
  assert.equal(Number(actual.total_xp), expectedXP, 'Progress tool disagrees with saved check-ins');
  assert.equal(Number(actual.current_level), 1+Math.floor(expectedXP/50));
  verified++;
}
assert(verified > 0, 'No real progress-tool acceptance result found');
