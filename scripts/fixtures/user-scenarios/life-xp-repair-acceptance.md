# Life XP repair acceptance

Existing project: /workspace/life-xp-autonomous-1789787755680
Published plugin: local.project.life-xp-autonomous-1789787755680
Repair this model-created project; do not create another project or clear its data. It has 3 default habits and one saved water check-in worth 10 XP.

The previous GLM-4.6V attempt was explicitly cancelled after repeatedly reading a mistyped path. It did not pass acceptance. Retain failures and run real tests instead of asserting success.

Required behavior:
- Chinese dark navy/mint habit RPG, local SVG avatar/icon, Today and History, animated XP bar and level-up celebration.
- Default water10/exercise30/reading20; positive-XP custom habits; level=1+floor(all-time XP/50).
- SQLite is authoritative. Unique habit/day, local calendar day, no duplicate rewards, saved records persist on reopen/update. Preserve existing water10.
- Completing water must not leave exercise/reading disabled. Restore controls after success/error; invalid input then valid input works.
- History contains dates, habits, earned XP, daily totals, chronological cumulative XP and milestones (including a single reward crossing multiple levels). Newest-first display must still accumulate oldest-first.
- Positive/negative timezones, midnight with the same page open, all-time XP after a new day, and more than500 history rows. Use isolated fixtures for long/multi-day synthetic records, never populate the real app with fake history.
- Follow the installed Builder Skill and project_contract: SQLite config only table; filters and pagination are declared tool arguments. Query max limit500; paginate with stable order. Do not invent joins, config.sql or browser window.omni in Node.
- Run /workspace/oob-independent-life-xp-audit.cjs against the actual app.js with both TZ=Asia/Shanghai and TZ=America/Los_Angeles. Do not edit this independent test. Add missing coverage by importing real production methods, not copied formulas. Await async tests and preserve nonzero exit codes.
- Run project_check and publish; exercise advertised real business tools, valid/invalid inputs, actual UI and reopen when available. Publishing is not acceptance. Report blocked capabilities honestly. An external supervisor verifies process restart; do not force-stop your own host.
- Fix failures autonomously, rerun and retain raw commands/results. Never call a failed or unexecuted test passed.
