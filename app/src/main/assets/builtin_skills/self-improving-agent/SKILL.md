---
name: self-improving-agent
description: Built-in self-improvement loop for Omnibot agents. Use to record non-trivial failures, user corrections, outdated assumptions, and reusable best practices into structured workspace learnings, then promote stable rules into memory.
---

# Self Improving Agent

This built-in skill is fixed-injected for Omnibot agent runs.

Use it to maintain a lightweight learning loop without interrupting the user's main task.

The native ACP/GUI model boundary records recognized provider failures in `data/provider-diagnostic.json`, even when the model cannot answer. This is diagnostic evidence, not a new task state. Tool failures should be logged with the script below; do not assume every Harness automatically invokes the legacy tool-failure hook.

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

Before retrying a failed operation, inspect the saved diagnostics and search the learning log. Use memory_search when that tool is available; do not assume skill-local files were automatically indexed by every Harness.

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


## Provider self-check and recovery

Use this workflow for GLM or other model errors, a failed GUI planner, or a request that appears stuck.

1. Read `data/provider-diagnostic.json` and the original task error. The latest 20 safe failure records are retained under `data/provider-diagnostics/`; consult them for recurrence, but do not assume different records belong to the same Provider or conversation. Identify the configured Provider, model, protocol, and failing operation using the existing settings owner. A GLM HTTP 400 with a gateway fallback timeout does not prove the key is wrong, the model lacks vision, or the phone froze. Preserve both facts; do not silently switch providers.
2. A model outage can prevent this skill from running. Native transport owns bounded pre-output recovery; after output/tool intent starts it must preserve the original failure. Never restart the full GUI task or replay taps to repair an HTTP request. Cancellation is not a failure to repair.
3. For OpenAI chat-completions providers, run `node <scriptsDir>/check-provider.mjs` with a JSON object on stdin containing the exact configured `endpoint` (full chat/completions URL), `model`, and the configured optional `apiKey`/`headers`. Anonymous Providers do not require an invented key. Custom headers override generated headers, just as in the native client. Obtain credentials only through an already authorized configuration surface; do not ask the user to paste a key into chat or put one in command arguments/history. If unavailable, report that the probe could not run. Other wire protocols need their existing official client, not this probe.
4. The probe makes one synthetic request with a 20-second timeout; set `probe` to `vision_tool` for a valid synthetic white PNG plus native tool-call verification, or omit it for text. It saves only a safe result in `data/provider-check.json`; no prompts, URLs, keys or response bodies are persisted. Passing proves text connectivity only. For GUI failures run `vision_tool`, then verify the actual operation with the existing GUI test path. Model listing alone is not an execution test.
5. Diagnose authentication, quota/rate limits, unsupported request fields, TLS, and upstream service failures separately. For 400 inspect the failed request's schema/capability evidence before changing anything. Do not remove tools/images, disable TLS, invent a model ID, or repeatedly retry rejected requests. Service outages may require waiting or the user's choice of another configured model.
6. Apply an evidenced configuration correction through the existing Provider/settings tool if available, then read it back. Never edit encrypted preferences, create a second provider store, or make temporary shell exports the durable repair. If no settings tool exists, explain the exact setting the user must change.
7. Verify the originally failing operation, a subsequent request, and restart/reload with the saved configuration. Only then log the concrete change and evidence in `ERRORS.md` and a short reusable learning. A one-off successful retry is transient recovery, not proof of a permanent repair. Leave unverified cases pending. Preserve original failed turns/history.

Builtin refresh and reinstall preserve this skill's `data/` directory. Keep observed failures separate from verified repairs; never mark a failure resolved solely because a text probe passed.


### Recovery boundaries

- Authentication or missing credentials: identify the selected Provider before
  changing anything. Never copy a key from another Provider. Use its settings
  page to save the correction, then refresh; empty authentication headers must
  be filled or removed. A probe is not a credential repair.
- Temporary transport failure: use only the existing bounded transport retry.
  If it is exhausted, end the failed request normally and allow a subsequent
  user request. Do not start a second retry loop from this skill.
- Persist verified repairs using the existing learning log, including the
  failing operation, concrete correction, verification result and restart check.
  Never mark the diagnostic history itself as a completed task or erase the
  original failure. If restart/original-operation verification is missing,
  retain `pending` and state the missing check.
- Diagnostic or learning-file failures must not prevent canonical prompt
  cleanup or block another conversation. Report unavailable persistence;
  never claim a repair was saved when its write failed.
