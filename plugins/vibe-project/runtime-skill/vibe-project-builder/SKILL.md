---
name: vibe-project-builder
description: Build a standalone phone-first HTML App with an SVG icon, Xiaowan skill, Agent-callable tools, reusable connectors, real data paths, and optional SQLite, then validate, publish, and add it to the Android home screen as an independent plugin. Use when the user asks "做*工具", "创建*应用", "创建*app", "做*app", "开发*插件", "搭建*助手", "build * tool", or "create * app", and for existing projects the user wants Xiaowan to operate directly.
tool-routing: workspace-direct
completion-start-tools: file_write, file_edit, terminal_execute
completion-tools: project_check, project_publish
---

# Vibe Project Builder

Build the project freely in the current workspace. Every project is a standalone phone-first HTML App with its own SVG icon, plus a Xiaowan `SKILL.md` and Agent-callable tools bound to connectors. SQLite remains optional storage, not the plugin backend. Every business action that should work from chat must be declared as a tool; never make Xiaowan open the App and simulate taps to use plugin functionality.

Before writing code, read [references/product-writing.md](references/product-writing.md) and [references/workflow-validation.md](references/workflow-validation.md). Write the compact product contract and acceptance matrix they require. Treat this as the writing phase of the project: decide the real user outcome, truthful data source, complete interaction states, safety boundary, tool surface, and observable workflow evidence before implementing the dashboard.

## Build, test, repair — owned by the builder

You own implementation **and acceptance**. Do not ask the user or supervising
agent to diagnose ordinary failures, click every button for you, or send a new
"continue/fix it" prompt. Within the active task, run the available tests, inspect
failures, make targeted repairs, and rerun them before reporting completion.
A supervisor reviews evidence; it does not replace your test execution.

1. Call `project_contract` before writing the toolkit. Read the product and
   workflow references above once; record a short acceptance matrix and the
   actual tools/commands available to execute each row. Missing UI/device access
   is a blocked test, not a pass. Do not invent a testing tool.
2. Build one complete core workflow. Keep business calculations testable with
   the project's existing test runner (for plain JavaScript, `node --test` when Node is available).
   Check the runner exists; reuse available tooling or mark execution blocked.
   Persist executable tests alongside the project; a prose checklist is not a test.
   Include the relevant regressions in the workflow reference, not just the happy path.
3. Write the Skill, toolkit, local HTML/assets, hand-authored SVG icon, and optional
   schema. Never call an image-generation model for icons. Audit Data / Tool / Display consistency:
   tool arguments, database fields and displayed values must agree. Frontmatter
   name equals the slug; database permission requires `schema_path`.
4. Run the executable tests and `project_check`. Fix the reported boundary with
   a small edit. Read the relevant file range or whole small file once; do not
   repeatedly page through unchanged files or rewrite the project to fix one field.
5. Call `project_publish` to make the candidate available for runtime testing.
   **Publishing is installation, not acceptance or task completion.** Invoke its
   actual published business tools and read back saved results. If the current tool
   snapshot cannot discover them, follow the blocked-test rule in the workflow
   reference; a subagent does not bypass that limit. Then run the real
   UI/reopen workflows using available authorized device/test capabilities.
6. On failure, save the input, expected/actual result and error; turn it into an
   executable regression. Repair it yourself, republish if needed, and rerun the
   failed case plus affected create/read/history/reopen cases. Keep existing data.
   Do not reset the database, replace assertions, or insert fixtures to hide a failure.
7. Finish only with evidence for every required row: passed, failed, blocked, or
   not applicable with reason. Save commands, exit codes, tool results and UI/device
   evidence under `test-results/`; refer to these paths in the final answer.
   "All fixed", "ready to use", and "fully tested" are forbidden while a required
   row is failed or unexecuted. State emulator vs physical device; without physical
   bug-fix acceptance use **待真机验证**.

For a repeated identical failure, change the hypothesis or inspect the owning
contract instead of repeating the same write/check call. After three attempts with
no new evidence, report the specific blocker and preserve the project and test
results. A provider/runtime terminal error is owned by ACP: do not add a private
retry loop, replay a completed turn, or claim a Skill can recover after that turn
has already ended. A later explicitly admitted continuation uses saved evidence.

If delegation is available and authorized, use one existing subagent for an
independent review of the acceptance matrix and evidence. Give it the project
path, requirements, test commands and failing results; it returns concrete missing
checks and failures to you. You remain the repair owner. Only one agent controls
one device at a time. Do not create another Agent runtime or assume subagents exist.

Re-publishing preserves connector-owned data. Open the installed plugin detail
page to use “Open App” or “Add to Home Screen”; shortcut pinning requires Android
confirmation and is separate from publishing and acceptance.

## Real Data and AI Events

Start production state empty. Populate business records only from explicit user input, a declared Connector response, a deterministic derivation from those inputs, or a runtime Xiaowan call. Never ship a hidden fallback array, random record generator, fixed score, fixed recommendation, or canned AI answer and present it as a successful production result. Static labels, empty-state copy, schemas, and visibly labeled removable demo data are not business-result mocks.

Match every promised output field to data the chosen Connector can actually observe. A `xiaowan` tool sees its declared instruction and the current tool arguments; it cannot inspect plugin installation state, current time, SQLite rows, device state, or remote facts unless another real capability supplies those values. Never put a desired version, timestamp, score, tool list, or status into the instruction and ask the model to repeat it as if observed. If the runtime cannot source a field truthfully, remove that promise or choose a Connector that can.

Use Xiaowan as a real backend capability when the product benefits from generated plans, explanations, summaries, simulations, or personalized events. Generate them at tool invocation time from current user inputs and stored state; do not paste a prewritten result into HTML or JavaScript. Keep generation separate from facts:

- Label generated content as AI-generated and preserve the source inputs.
- Return the resolved model, elapsed time, and usage supplied by the Connector.
- Never ask AI to invent live scores, prices, medical facts, transactions, or other externally verifiable state.
- For durable generated events, expose a chat-first sequence such as `generate_event` → user/Agent review → `save_event` → `get_event` instead of silently saving unreviewed text.
- On model failure, preserve user data and return a retryable error; never fall back to a canned success value.

Example: a useful `generate_next_challenge` tool may receive recent check-ins as arguments and ask Xiaowan to generate the next challenge from those inputs. A fake `get_runtime_status` tool must not ask Xiaowan to claim a fixed version, current timestamp, or installed state that it cannot inspect.

For products centered on an evolving experience, prefer meaningful AI-generated events over static screens. Examples include the next coaching challenge derived from recent check-ins, a narrative milestone derived from a user's saved choices, or a daily reflection derived from actual journal entries. The event is a generated artifact with provenance, not a factual claim about the outside world.

`executor.connector` always references a declared `connectors[].id`; it is never
a connector type. The built-in types are `sqlite` with
`insert/query/update/delete`, `xiaowan` with `invoke`, and `http_json` with
safe read-only `get` for declared public HTTPS data sources. If this contract is
uncertain, call `project_contract` once. Do not search the repository or guess
Connector, action, permission, or executor names.

Do not pass source code through the publish tool. Use normal workspace file and terminal tools to build, check and publish the candidate; republish after repairs.

## Minimal Project

```text
weekly-coach/
├── index.html
├── styles.css
├── app.js
├── icon.svg
├── skill/
│   ├── SKILL.md
│   └── references/
│       └── coaching-rules.md
└── toolkit.json
```

The manifest is publish metadata, not another project file:

```json
{
  "slug": "weekly-coach",
  "name": "每周教练",
  "description": "把目标和限制整理成可执行的一周计划",
  "entry_path": "index.html",
  "icon_path": "icon.svg",
  "permissions": ["xiaowan"]
}
```

`toolkit.json` is the connector contract exposed directly in Xiaowan's
`tools[]`. Tool names are automatically namespaced with the plugin slug. Named
connectors let multiple tools share one backend capability without coupling the
Skill to a concrete implementation:

```json
{
  "schemaVersion": 1,
  "connectors": [
    {
      "id": "coach",
      "type": "xiaowan",
      "config": {
        "system": "You are a concise, practical planning coach."
      }
    }
  ],
  "tools": [
    {
      "name": "create_weekly_plan",
      "displayName": "生成一周计划",
      "description": "Turn a goal and constraints into a practical weekly plan.",
      "parameters": {
        "type": "object",
        "required": ["goal"],
        "properties": {
          "goal": {"type": "string"},
          "constraints": {"type": "array", "items": {"type": "string"}}
        },
        "additionalProperties": false
      },
      "executor": {
        "connector": "coach",
        "action": "invoke",
        "config": {
          "instruction": "Create a seven-day plan from the supplied goal and constraints. Return concise Markdown.",
          "reasoning_effort": "none",
          "temperature": 0.4
        }
      }
    }
  ]
}
```

The host connector registry is the extension point. The runtime includes the
`xiaowan`, `sqlite`, and public read-only `http_json` connectors. Credentialed
services, device automation, MCP, and other native capabilities can add
connectors without changing the Xiaowan-facing tool format or the generic
`ToolHandler`.

## Chat-First Contract

The chat Agent is the default input and operation route. The dashboard is a thin view and trigger surface over the same tools and stored facts; every Dashboard business action must call `window.omni.tools.call` with a local tool declared in `toolkit.json`. It must not contain a second mock backend or business actions unavailable to Xiaowan. A user should be able to create, query, update, and summarize the useful state in natural language without opening the dashboard. After a successful publish, surface the direct Dashboard entry instead of making the user rediscover it in the plugin market.

## Public Data Link

Use `http_json` when the product promises live public facts such as sports,
weather, transit, or open-government data. Declare the exact HTTPS origin and
query mapping; never ask Xiaowan to invent data when retrieval fails:

```json
{
  "connectors": [
    {
      "id": "score_feed",
      "type": "http_json",
      "config": {"base_url": "https://example.org/api"}
    }
  ],
  "tools": [
    {
      "name": "list_games",
      "displayName": "查询比赛",
      "description": "Fetch current games from the declared public source.",
      "parameters": {
        "type": "object",
        "properties": {"date": {"type": "string"}},
        "additionalProperties": false
      },
      "executor": {
        "connector": "score_feed",
        "action": "get",
        "config": {
          "path": "/games",
          "query": {"date": "$date"},
          "response_path": "data.games",
          "max_items": 50
        }
      }
    }
  ]
}
```

Declare the `network` permission. The host enforces HTTPS, public hosts,
timeouts, response limits, no redirects, and returns source URL plus retrieval
time. Keep private API keys out of project files; credentialed sources must use
a host-managed Connector.

Every project must add `entry_path` and `icon_path` to the manifest. The icon
must be an SVG file authored directly from vector shapes and text paths; do not
call an image-generation model and do not create a raster source asset. For
local structured data, add `schema_path` and the `database` permission.

## Shared Tool Link

Only plugins that need local structured data should declare the `database`
permission and `schema_path`. For those plugins, the Linker creates an isolated
SQLite database. Declare every read and write operation once in `toolkit.json`,
then call that same local tool name from the Dashboard. Xiaowan receives the
namespaced form automatically, while Dashboard code stays short:

```js
const inserted = await window.omni.tools.call('record_workout', {
  exercise: '深蹲',
  weight: 100,
});

const result = await window.omni.tools.call('list_workouts', {
  exercise: '深蹲',
  _order_by: 'created_at DESC',
  _limit: 50,
});
const rows = result.rows; // Query returns {rows, count}, NOT an array.
```

SQLite insert arguments are the column values directly and its result is `{rowId}`.
Query arguments are equality filters, except `_limit` and `_order_by`; declare
these exact names in the tool parameter schema if used. Do not invent `limit`,
`order_by`, `database_path`, or a config `columns/values` substitution mechanism.
Render `result.rows`, not `result`; test an actual save and subsequent query before
claiming the App works. `project_check` validates structure, not browser behavior.

Use only the unprefixed `tools[].name` inside the Dashboard; the host binds it
to the current plugin and rejects unknown tools or undeclared arguments. Never
call the low-level database bridge from new project HTML or JavaScript. It
exists only to reopen legacy installed projects and bypasses the shared Tool
contract, so `project_check` rejects it in new or republished source.

Use SQL constraints such as `NOT NULL`, `CHECK`, `UNIQUE`, defaults, foreign
keys, indexes, and triggers when they make the backend more reliable. Do not
build a REST API or expose raw database paths.

## Xiaowan capability bridge

Declare the `xiaowan` permission when the frontend invokes Xiaowan. The frontend must use the shared Xiaowan capability bridge for AI-native interactions. It reuses Xiaowan's conversation history, Skills, registered plugin Tools, streaming transport, provider configuration, and retries. Do not build a second Agent loop in JavaScript and do not expose provider credentials.

Use `reasoningEffort: 'none'` by default. Choose `low` or `medium` only when the request genuinely needs multi-step tool selection or synthesis. Fast one-shot generation through `window.omni.ai.generate` always disables provider thinking so a small token budget cannot be consumed before visible output.

Every AI action must show loading immediately, disable repeated submission,
await the supported Promise, and display success or the actual error before
re-enabling controls. `window.omni.xiaowan.invoke` returns a Promise; do not invent
`send`, `runId`, event listeners, stream events, or a cancellation API. Do not
promise cancellation unless a declared available tool actually supports it.

```js
const result = await window.omni.xiaowan.invoke({
  text: '根据我的训练记录制定下周计划',
  context: { page: 'weekly-plan', selectedWeek: '2026-W32' },
  reasoningEffort: 'low',
});
renderResult(result);
```

`window.omni.xiaowan.invoke` is the supported generic capability entry point. It is backed by the ACP runtime and the project's declared MCP/plugin tools; there is no standalone external-App event bus, run API, or state bridge. The frontend never receives an API key. If AI is unavailable, show a useful retry state while keeping local data features usable.

## Frontend Quality

Custom HTML/CSS/JavaScript is the user-visible experience and is mandatory. Prefer a specific, phone-first product over a generic admin panel. Make the primary action reachable with one thumb, and include deliberate empty, loading, AI-working, success, and error states. Any button that can invoke Xiaowan must immediately disable repeated submission and show a visible status region until completion or failure. Local frameworks and bundled assets are allowed; the Linker does not prescribe component structure.

Create `icon.svg` directly in code with a square `viewBox`, a deliberate background, and a simple high-contrast symbol that remains legible at launcher size. Do not invoke image generation. The SVG stays the canonical source; Android rasterizes it locally only when constructing the launcher shortcut.

Published projects run offline by default. Bundle scripts, styles, fonts, and media inside the project instead of loading CDNs or calling remote APIs. Use `window.omni.xiaowan.invoke` for Xiaowan Agent access; network credentials and arbitrary outbound requests are intentionally unavailable to project JavaScript.

## Shining Patterns

- Fitness pet: every workout grows a creature; SQLite stores facts and streak state, while Xiaowan creates adaptive plans.
- Tiny relationship garden: meaningful moments become a visual garden, with private searchable history.
- Field notebook: photos and structured observations become a durable personal record with AI summaries.

Avoid games with no persistent value. The strongest project combines a memorable frontend with useful private state.
