# Workflow Validation Contract

Write a compact acceptance matrix before implementation, then keep executable tests and raw results in the project (`tests/`, `test-results/`). A report alone is not acceptance. Each row must name:

1. **Scenario** — a realistic user request, not a schema-only example.
2. **Provenance** — classify every input as user-entered, locally derived, fetched from a named URL, or AI-generated at runtime.
3. **Tool sequence** — list the exact Xiaowan-facing tools in expected order.
4. **Assertions** — state the returned, persisted, and visible outcomes that prove success.
5. **Execution and evidence** — exact available tool or test command, result artifact, and passed/failed/blocked status. No UI capability means UI acceptance is blocked; unit tests still run.
6. **Failure behavior** — state what users see and what remains unchanged when the dependency fails.

## Required Workflows

Run every applicable workflow with non-placeholder inputs:

- **Create and read round trip** — create one record from realistic user input, query it through a separate read tool, and assert stable identifiers and matching fields.
- **Update or idempotent retry** — update the created record, or retry the same operation when duplicates would be harmful; assert no silent duplicate or stale value.
- **AI event lifecycle** — call a Xiaowan-backed generation tool with current inputs/state, assert non-empty runtime output plus model/latency/usage metadata, review it, save it only when appropriate, then read it back. The generated text must not already exist as a fixed production value in source files.
- **Capability-source alignment** — trace every dynamic output field to values present in tool arguments or returned by the selected Connector. Reject instructions that ask Xiaowan to pretend it observed runtime status, current time, stored rows, device state, or external facts it never received.
- **Public data provenance** — fetch through the declared `http_json` Connector, assert source URL and retrieval time, then exercise unavailable or malformed data. Never replace failure with invented records.
- **Empty and retry state** — start with no business records, verify a useful empty state, force one dependency failure, and verify explicit retry without data loss.
- **Tool and Dashboard consistency** — compare a tool result with the dashboard backed by the same record/source. Field names, counts, timestamps, and status must agree; the dashboard must not use a second data array.
- **Publish and reopen** — require successful `project_check` and `project_publish`, verify the plugin is installed and enabled, open the installed entry when one exists, perform the real user action, leave and reopen it, and confirm saved values and plugin tools remain available. For lifecycle bugs also restart the application process and read back the same record; source inspection is not restart evidence.
- **Safety boundary** — verify that payments, messages, external writes, destructive actions, and sensitive-data disclosure stop for confirmation or remain unavailable.

Mark a workflow not applicable only when the product feature is absent, with a reason. Missing test tooling, permissions, or device access means blocked, not not-applicable. A UI-only visual check never substitutes for invoking the business tool. A static checker pass never substitutes for one realistic happy path and one real failure/retry path.

## No-Mock Audit

Before `project_check`, search project source for placeholder records, demo arrays, random business values, fixed timestamps, canned recommendations, hardcoded AI output, network-failure success fallbacks, and AI instructions that embed the answer they claim to discover. Remove them from production paths. If sample data is an explicit product feature, label it `Sample`, isolate it from real records, and provide one-action removal.

Do not reject fixed interface copy, enumerations, validation rules, CSS values, schemas, or deterministic formulas merely because they are constants. Reject only constants that impersonate user data, external facts, runtime AI output, or successful backend responses.


## Available execution paths

Inspect the active tool catalog and permissions before choosing an executor.

| Available capability | Execute | Do not claim |
| --- | --- | --- |
| `terminal_execute` | Run the project's real calculation module with its existing runner (e.g. `node --test`); keep commands and exit status. | A mocked bridge is not native persistence or device UI acceptance. |
| Published plugin tools | Discover the exact advertised names and schemas, invoke writes, then independent reads with real inputs. | `project_publish` does not guarantee new tools are visible in the current turn's catalog snapshot. |
| `vlm_task` | When enabled, authorized and its device runtime is available, open the plugin, operate controls, inspect visible results, leave and reopen. Use its actual schema. | A proposed action is not execution; reopening a page is not process restart. |
| Existing external device runner | Run the project's actual device acceptance including process restart; retain device/version/result evidence. | The phone Agent cannot force-stop its own host and then assert it supervised recovery. Without an external runner mark process restart blocked. |
| Existing authorized `subagent` | Review tests/results independently and return concrete failures to the builder. | A subagent inherits available tools; it cannot make undiscovered plugin tools or device permissions appear. |

If tools for a newly published plugin cannot be discovered in this turn, execute
remaining logic and available UI checks, preserve results, and report that direct
business-tool acceptance is blocked pending host discovery. Do not fabricate a
call, privately reopen the ACP session, or spawn a new loop to bypass this limit.
Do not perform a new install, force-stop, destructive operation or external write
just because it appears in a matrix; use the task's existing authorization.

## Run after publishing, not after the user finds a bug

Use the installed plugin's actual namespaced tools advertised by the host. Inspect
its returned tool definitions instead of guessing their names or argument schemas.
Exercise a create → independent read round trip, an invalid-input failure, then a
valid action to prove recovery. Direct database inspection is supplementary;
direct inserts are not evidence that the business tool or UI works.

For the UI, use only available authorized device/automation tools or the existing
project acceptance runner. Open the installed entry, operate each core control,
check the visible result against the saved tool result, and reopen it. When no
such capability is available, complete the business-tool and executable logic tests
and return “published; UI acceptance blocked” with the missing capability. Never
write "UI verified" because HTML exists or `project_check` passed.

Persist the exact failure as a test and repair it within this task. Run it again
against the updated source AND the republished plugin. Retain failed evidence and
identify which later run resolved it. Do not ask a supervising agent to provide
each next repair instruction. A supervisor may challenge the evidence, but only
actual executed results can satisfy the row.

## Regression examples from Life XP

For a habit/XP/history app, implement these as executable cases using the actual
calculation module and actual connector tools where available. Test fixtures for
pure calculations are allowed; label them and never present them as device or
production-data evidence.

| Case | Required assertion |
| --- | --- |
| Fresh state | XP 0, level 1, history empty; default habit definitions are not completed records. |
| Real check-in | Tool declares and receives habit ID and local calendar day; saved row has both; a separate query reads it back. |
| XP and history | Water 10, exercise 30, reading 20 produce totals 10/40/60, level 2, matching history values and day sum 60. Read XP from the actual stored/declared source, not a nonexistent check-in field. If habit rewards can be edited, preserve the earned reward on historical events and test that edits do not rewrite old XP. |
| Duplicate/in-flight | Same habit and day remains one row and one reward; controls disable during save and after completion. Invalid input leaves prior data intact, then valid input succeeds. |
| Milestones | Crossing 50 XP produces a visible level-up and a saved-event-derived historical milestone. Test multiple days and one reward crossing multiple levels; each milestone belongs to the event/day that crossed it. |
| Local date | Test either side of local midnight in positive and negative UTC offsets. `toISOString().slice(0,10)` is a UTC date, not a local-day implementation. Refresh the day for a page left open overnight. |
| Long history | Seed more than the connector's default query limit in an isolated test fixture. Total XP and history must not silently truncate at 100 rows; verify the actual supported query strategy before claiming unlimited history. |
| Add habit | Empty/invalid XP fails visibly; a valid new habit can be created, read back and used from the UI. |
| Reopen/update | Leave/reopen and restart: XP, level, completion state, custom habits and history persist. Republish retains the same records. Never clear user data to make this pass. |

SQLite contract traps: `config.table` selects a real schema table. Do not assume
`config.sql`, `values`, or `conflict` are executed; query returns `{rows,count}`,
not an array or an aggregate. Declare `_limit`/`_order_by` exactly when used.
Test the declared required arguments against actual NOT NULL/UNIQUE constraints.
