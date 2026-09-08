# Accumulated fixes integration and emulator acceptance (tested integration candidate)

Integration branch: `codex/integrate-local-fixes`; base local snapshot `8c16e38d9`, incoming main `ccada8b1b`. Original dirty worktree is preserved. The existing published v0.6.2.1 is not replaced. The integration debug APK retains version 0.6.2.1 (13); distinguish it by source and APK hash.

Device: emulator-5560, OobCleanInstall20260907, Android 13 ARM64, Alpine. User explicitly accepted emulator testing. This is not physical-device acceptance.

## Findings

- Xiaowan advertises `/compact`, with model/reasoning configuration. Host `/init` is a prompt shortcut to write workspace guidance, not ACP initialize. The former hardcoded `/plan` and `/review` entries falsely implied supported harness capabilities. Plan now requires an advertised exact configuration value and writes through the existing session config owner. Unsupported shortcuts cannot activate a local-only mode.
- Continuous ACP updates reset the history persistence debounce. The automatic summary ran, but its completed tool boundary was not yet in the journal. A failing coordinator regression reproduced starvation. The existing queue now batches updates within the original timer and merges persistence flags; urgent writes can advance it. No new lifecycle, reducer, or retry path.
- Initial terminal negative test incorrectly asserted `exitCode`; native terminal result uses `resultCode`. Corrected the fixture, without changing production behavior. Actual exit 7 and timeout now recover within the same task.
- Copying a live SQLite database and WAL separately produced inconsistent test evidence. The checkpoint assertion now uses SQLite online backup, removes only its unique temporary backup, and leaves original history untouched.

## Executed checks

- Native unit suite: 998 passed before the additional Flutter persistence fix.
- Flutter final full suite: 1181 passed; affected coordinator suite 98 passed, slash command suite 4 passed. Local Flutter resolves SDK-pinned meta/test_api versions; the committed lock retains the existing main/CI versions.
- Node scripts final suite: 80 passed, including single/double-quoted UIAutomator completion text.
- Android SQLite instrumentation: 13 passed (migration, large entries, checkpoint/version boundaries).
- Debug APK builds and data-preserving install succeeded. No uninstall or app-data reset.
- File read UI journey: 16 steps passed, including 16 MiB HTML ranges, 17 MiB original PNG hash, binary PDF metadata, cancellation, repeat, restart.
- Provider failure UI journey: 32 steps passed: 401, quota/rate 429, 503, disconnect before output, disconnect after partial output, recovery and restart. Uses deterministic local HTTP responses; not an external service availability claim.
- Tool failure UI journey: 12 steps passed: unknown name, malformed args, missing file, exit 7, timeout, recovery and restart. Real Android tools with deterministic model decisions.
- Xiaowan command menu: compact/init present, unsupported plan/review absent.
- Existing configured GLM-5.1 provider: in-process connectivity probe returned 200 for chat completions, responses and Anthropic wire. Credentials never exported. Real task acceptance remains separate.

## Durable execution entries

Use `node scripts/verify-agent-user-journey.mjs emulator-5560 scripts/fixtures/agent-user-journeys/<case>.en.json <evidence-directory>` after selecting the correct harness and fixture/real provider. `xiaowan-auto-summary-restart` requires 64k context budget; the fixture emits bounded synthetic progress plus twenty actual file reads. The SQLite assertion requires the device sqlite3 binary and uses its online backup interface.

New cases: `xiaowan-command-capabilities`, `xiaowan-provider-failures`, `xiaowan-tool-failures`, `xiaowan-auto-summary-restart`, `xiaowan-local-api-long-task`. Existing file, threshold, checkpoint, memory and Codex journeys remain reusable. Real API scenarios are isolated under `/workspace/oob-live-integration`; they must verify produced files in addition to final reply markers.

Automatic-summary restart journey: all 9 steps passed, two tasks of 20 pages, multiple in-task summaries, unchanged summary hash/cutoff/revision across restart.

Real provider file task: 16 successful calls covering file_write/list/read/search/stat/edit/move and terminal_execute, verified against canonical history and produced files. A UIAutomator test parser initially missed the real completion because XML used single-quoted attributes; the parser now accepts both delimiters and has a regression test. The task was not resent.

Integration debug APK SHA-256: `255fdd79eee6a2548d537e70d1ea5fa64d72f31121035c1a40e50be940c230e8`.

Real provider long task: 21 successful calls (20 file_read, one report file_write), all twenty offsets independently verified at 65,536-character intervals, 1,310,720 total characters. Visible reply survived restart. Recovery continuation required reselecting the same conversation after the automation could not find a composer; no prior task was resent.

Real provider recovery: 6 calls passed journal checks, including actual missing-file failure, exit code 7, and a later successful terminal command. Final visible group is Processed, not Failed. This continuation was agent-operated through the visible emulator UI after repeated UIAutomator empty-root failures; the exact submitted user message was independently checked in history and occurred once. The automated end-to-end run was interrupted and is not represented as an uninterrupted green run.

Codex Plan/Default journey: 27 steps passed; resolved approval after restart: 4 steps passed; cancellation, two follow-ups and restart: 11 steps passed. The Plan command itself also passed a complete 18-step rerun; provider requests proved plan=true then plan=false. The additional command scope checks compiled and 23 focused Flutter tests passed.

Memory write/search/load and restart recall: 7 steps passed. Context threshold 32k → 64k, model refresh and restart preserving 64k, then restoring 128k: 17 steps passed. Xiaowan command capabilities: all 6 steps passed. No blanket claim that all 44 available tool definitions have been exercised on-device. Physical-device validation remains unperformed.

Fixture generation: `python3 scripts/prepare-file-read-fixtures.py /tmp/oob-fixtures` produces deterministic HTML/text/binary-PDF and a valid 3000×2000 noise PNG, refusing to overwrite different inputs. Install these synthetic fixtures into the documented reserved workspace test paths on a test device, and set both OOB_FILE_TEST_DIR and OOB_FILE_TEST_IMAGE for the provider fixture.

Registry audit: builtin schemas are registered by canonical names, sorted for stable requests; execution does not route by localized display names. Five privileged tools are conditional on a granted Shizuku/root backend. VLM is deliberately discoverable before enablement and its handler must return an enablement error. Plugin/capability definitions share the registry and duplicate names are rejected. See tool-coverage.json for the full 44-definition inventory and explicit device coverage limits; this is not a claim that every external capability is available.

DSH rechecked against the final APK: bash succeeds, but read-only/workspace-write remain SANDBOX_UNAVAILABLE. Landlock exits 125 both inside and outside proot because the emulator kernel does not enforce it; bubblewrap is absent. This is a failed sandbox acceptance, not an App lifecycle fix or permission bypass. See `../verification/integration-20260908/dsh-sandbox.json`.

Cleanup: restored existing GLM-5.1 bindings, disabled the temporary accessibility service to its original unset/0 state, and stopped the local fixture HTTP server. Existing conversations and synthetic regression files were preserved. No release published.

## Follow-up: turn-specific error acceptance

The earlier generic `Failed` UI assertions were insufficient on their own: a historical failure could satisfy a new step. The durable journey now additionally reads the canonical SQLite journal, finds exactly one synthetic user admission, bounds entries before the next user, and checks session/turn identity, terminal outcome, partial output retention and recovery. A read-only audit of all 17 earlier provider/tool scenarios passed after restart. Seven verifier tests reject old/later failures, duplicate admission, mixed identities, wrong categories and false recovery.

Found a separate presentation issue: raw ACP HTTP errors arrived without structured failureKind details and became a generic assistant error. The existing formatter now recognizes the transport HTTP envelope and produces bounded app-owned authentication/quota/rate/service/rejected-request messages. The formatted summary contains only app-owned messages; the existing diagnostic detail remains separate. The first emulator run caught the native quota translation (`平台额度不足`) being mistaken for rate limiting; this actual translation path was added to the formatter regression. This changes presentation only, not retry or turn ownership.

Follow-up local verification: error formatter/service tests 59 passed, shared event reducer tests 193 passed, turn-verifier tests 7 passed; debug build and data-preserving emulator installation succeeded. Follow-up APK SHA-256: `a4520eb7a6a26988b4d98974875145525a712e6e987e2fb43e8c8a53883e5aa1` (same version label, subsequent source change). Earlier APK hashes identify their own test stages.

Final category journey: 18 steps passed on the subsequent APK, covering 401, quota 429, rate 429 and 503 with exact task-bound summaries. An additional read-only assertion passed after restart; agent-operated opening of the final error detail displayed the service-unavailable guidance (saved screenshot). This is emulator acceptance. Physical-device verification remains outstanding. Existing GLM bindings restored and fixture server stopped.

## Stateful terminal and installed skill follow-up

The 6-step emulator journey passed with 14 actual tool calls: native terminal start/exec/read/stop, cwd and environment preservation, actual command exit 7 followed by successful execution, read and repeated stop after closure returning errors; installed skills list/read, a missing skill returning error, and successful time lookup. Final completion survived App restart. The lifecycle task was also independently run once earlier (9 calls, actual native exit codes recorded). Four deterministic fixture unit tests were added to the maintained runtime test entrypoint; they verify that an environment failure cannot masquerade as exit 7, and that the skill read has an actual body and matching ID.

An initial discovery fixture wrongly assumed every source definition was available. Current Xiaowan explicitly supplies installed capabilities and does not advertise the legacy `tools_search` entry. Its actual rejection was correct; the fixture now asserts the absence of this schema, intentionally calls it once as a negative test, then uses advertised skills/time capabilities and completes successfully. The source-definition inventory now marks this distinction explicitly. No removed discovery lifecycle was reintroduced.

## Scheduled-task contract and restart fixes

Emulator reproduction confirmed two contract defects: a valid `schedule_task_create` request had no task ID (as specified by its schema), but the bridge called ID-required upsert; deleting an unknown ID returned `{deleted:false}` while the tool defaulted missing success to true. Creation now uses the existing scheduler to assign identity and creation time; the bridge exposes explicit deletion success. Update requires an existing task rather than resurrecting a deleted one. Import/sync retains upsert semantics. The creation schema now requires the prompt that its handler already requires.

A further actual restart failure revealed both incompatible persistence fields and sync scope: native-created Flutter records lacked required `createdAt`, causing the entire Flutter list to fail parsing, and startup passed only enabled tasks to a native full-replacement sync. New native records now carry creation time; legacy records use stable unknown timestamp 0; initialization syncs all tasks so the native owner can retain disabled tasks without scheduling them. No second scheduler or ACP lifecycle was added.

Local checks: scheduler CRUD/identity unit test passed, Flutter initialization test preserved both enabled and legacy disabled records across repeated initialization, and three fixture tests enforce disabled creation and exact cleanup identity. Final debug APK SHA-256: `aacb5ebbc205007dcba82efdb77abf9eb6c97bc67d1bb7290440d9901b2568e6`. The emulator schedule journey is explicitly a disabled-task CRUD and restart acceptance, not acceptance of firing a scheduled background Agent.

Final schedule emulator run: all 7 UI steps passed, 7 actual calls across create/list/update/delete, with both final tasks independently verified against their exact canonical turn after restart. Repeated delete and update-after-delete failed as expected while the enclosing task completed. A legacy disabled record missing createdAt was visibly restored in the Scheduled page, then deleted through the App's confirmation dialog to clean up only this test's leftover task. Existing model bindings restored; fixture HTTP server stopped.
