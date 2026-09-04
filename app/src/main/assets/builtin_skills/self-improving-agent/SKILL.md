---
name: self-improving-agent
description: Built-in self-improvement loop for Omnibot agents. Use to record non-trivial failures, user corrections, outdated assumptions, and reusable best practices into structured workspace learnings, then promote stable rules into memory.
---

# Self Improving Agent

This built-in skill is fixed-injected for Omnibot agent runs.

Use it to maintain a lightweight learning loop without interrupting the user's main task.

The runtime may auto-read this skill after a tool failure and auto-record the failure into `data/ERRORS.md`. Repeated failures with the same signature are merged into one bounded entry. If an argument/schema failure is followed by a successful call to the same tool in the same Agent run, the runtime closes that pending entry and distills the verified recovery into short-term memory.

## When To Record

Record after the immediate task is safe or complete when any of these happens:

1. a non-trivial command, tool, browser, or device action fails
2. the user corrects your understanding, path, rule, or project assumption
3. you discover an outdated Omnibot/runtime/project convention
4. you find a reusable workaround or best practice that will likely save future retries
5. the same mistake repeats in the same task or across tasks

Do not record ordinary chat, tiny one-off slips, or anything the user asked not to save.

## Default Storage

- skill-local learnings: `.omnibot/skills/self-improving-agent/data/`
- project-local learnings: `<project>/.learnings/` only when the lesson is repo-specific
- long-term memory: `.omnibot/memory/MEMORY.md` via `memory_upsert_longterm`
- short-term memory: `.omnibot/memory/short-memories/` via `memory_write_daily`

## Logging Workflow

1. Finish or stabilize the current user-facing step first.
2. Prefer the bundled `scripts/omnibot_auto_log.sh` for structured logging because it keeps IDs, headers, and append rules consistent.
3. Use skill scope by default.
4. Switch to `--project /workspace/<repo>` only when the lesson is clearly tied to one repository.
5. Use `learning` for corrected knowledge or best practices.
6. Use `error` for concrete failures with stderr, HTTP errors, stack traces, or invalid assumptions.
7. Use `feature` for recurring capability gaps the user actually wants.
8. Use `promote <ENTRY_ID>` only after the lesson looks reusable across tasks.

## Memory Promotion

As soon as you actually fix a failure — or the same failure recurs — write one short "遇到 X 先 Y" rule to memory (`memory_write_daily`, or `memory_upsert_longterm` when it is broadly stable) and back-fill the ERRORS entry's 建议修复 and 状态 (pending → resolved). Do not leave a resolved failure sitting as pending with an empty fix.

The runtime can automatically close a same-run argument/schema failure after verified success. An execution/runtime failure remains pending and still needs a concrete fix from you before promotion. Do not promote a generic “retry succeeded” observation as a stable rule.

Promote a lesson into memory only when it is stable, short, and broadly reusable.

Good candidates:

- a rule like “遇到 X 先检查 Y”
- a stable workspace convention
- a long-term user preference the user explicitly wants remembered

Prefer this order:

1. log into the skill data first
2. promote to the skill public area if it becomes broadly reusable
3. write the distilled rule with `memory_write_daily` or `memory_upsert_longterm`

Do not invent Minis-only paths or tools such as `/var/minis/...` or `memory_write`.

## Recall

Recorded failures and lessons are indexed into memory retrieval. Before retrying a tool, command, or environment step that has failed before — or when the injected memory context mentions a related pitfall — trust that recall (or call `memory_search`) and apply the known fix instead of repeating the failed step.

## Command Patterns

Use the bundled script through `sh`:

```bash
sh <scriptsDir>/omnibot_auto_log.sh init
sh <scriptsDir>/omnibot_auto_log.sh learning "摘要" "详情"
sh <scriptsDir>/omnibot_auto_log.sh error "摘要" "错误输出"
sh <scriptsDir>/omnibot_auto_log.sh feature "能力缺口" "用户背景"
sh <scriptsDir>/omnibot_auto_log.sh --project /workspace/my-repo learning "摘要" "详情"
sh <scriptsDir>/omnibot_auto_log.sh search 关键词
sh <scriptsDir>/omnibot_auto_log.sh promote LRN-20260409-ABC
```

If you need to refine an existing entry instead of appending a new one, use `read` and `edit`.

## Output Discipline

- keep summaries short and specific
- include the concrete command/tool/context that failed
- include the corrected rule, not only the symptom
- avoid logging secrets, tokens, and personal data
