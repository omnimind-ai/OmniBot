> **Current status — user-requested rollback:** The user requested keeping the system prompt simple and reverting this turn's additions. The bilingual “当轮执行约定 / Execution within this turn” blocks have been removed. Pre-existing workspace changes are retained. The prompt-based mitigation and its five successful device checks below are historical evidence, not acceptance of the reverted build. No automatic continuation/retry has been added. A 0.6.3 (16) productionStandardRelease test-signed build passed installation, UI response and restart smoke checks as recorded below. This is not proof that the original model plan-only behavior is eliminated.

# Action request stops after planning text — 2026-09-17

Physical PJE110 b49f281b, current conversation89. User entry41104 clarified that the target was the gallery screenshot/screen recording. Its persisted turn has one reasoning card and one assistant text item announcing a directory inspection; zero tool events; all final metadata is end_turn. No persisted cancellation/error marker exists. Prior user entry41097 (“继续”) has a successful terminal tool event and completed output, so the observed evidence does not support a global one-round limit or universally broken tool access.

Executed red regression:
`OOB_ANDROID_SQLITE=cache/oob-sqlite-wrapper python3 scripts/verify-agent-action-turn.py b49f281b 41104 docs/testing/artifacts/agent-plan-only-20260917/album-plan-only.json`
Returns exit1, toolCount0, stopReasons[end_turn]. The adjacent previous action turn passes the same tool-presence check.

Source: AgentOrchestrator consumes turn.message.toolCalls; if empty it emits assistant text as final, marks terminated and breaks. This explains how a plan-only response ends normally. Persisted history establishes no tool reached execution but does not distinguish the model omitting tool calls from a provider/parser dropping one. Raw transport logs for this specific turn were no longer available in the inspected logcat buffer; do not claim a verified provider-level root cause, app crash, transport disconnect, or completed fix. No product/lifecycle change made. Do not implement text-heuristic auto-replay or a second ACP turn lifecycle as a workaround.

Evidence contains only counts and lifecycle metadata, no private reasoning bodies. Next diagnostic boundary is the provider response tool-call count versus parsed call count for a reproduction of this exact conversation context.


## Confirmed provider cause and verified fix

The device's persisted AiRequestLogStore retained the raw stream after logcat rotation. Two offending HTTP200 responses (createdAt1789647100092 and1789647364415) explicitly contain finish_reason=stop and **zero wire tool-call deltas**, with 60 tool schemas available, no stop sequence, and max_completion_tokens16000. Thus the early finish originated in the model's plan-only response, not tool-call parser loss or a user cancellation. Sanitized evidence: `artifacts/agent-plan-only-20260917/provider-plan-only.json` (no reasoning bodies or credentials exported).

Fix: the existing bilingual `AgentSystemPrompt.build` now states the current-turn execution contract before workspace/tool details: an action request requires actual tool use through the requested outcome; progress text is not a final answer; continue/retry/corrections retain the goal; remaining authorized steps continue after tool results; completion/cancellation/concrete blockers are valid endpoints and ordinary questions can answer directly. No private lifecycle, forced tool selection, automatic replay, text-heuristic continuation, provider switch or second Agent loop was introduced. The selected DeepSeek-V4-Flash-0731 model and reasoning settings were retained.

Validation:
- AgentSystemPromptTest: 7 passed, Android assemble passed.
- Installed final APK 0.6.3/code16 on physical PJE110 b49f281b. SHA-256 `3973211285e0e7cd0f36f45f03c518070b8da39898f1cbf88bf79049b6731a80`.
- Real UI journey in the original failing conversation89, run1789647924708: find latest gallery recording (2 successful terminal calls), correct gallery-vs-OmniFlow target (1), copy and verify (1), force-stop/relaunch and recheck (1). All four turns reached a final answer after actual file operations. No tool results/model replies were mocked. The UI sender uses English requests with the same action/correction semantics; original Chinese failure responses remain preserved in the baseline evidence.
- Each request's installed execution contract was verified in the actual provider request, same model: `provider-fixed-contract.json`.
- Actual source recording found: 4,802,335 bytes. Copy retained at `/workspace/recording-recovery-demo/` with original basename. Independent SHA-256 comparison of source and copy matched: `893586487936e024cdc2ccc94da786578345467c0e5b1fef0c1a19938016a299`. Original remains present. No media bytes exported off-device.
- Canonical history and artifact assertion passed: `fixed-consecutive-turns.json`.

Re-run:

```sh
OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs b49f281b scripts/fixtures/agent-user-journeys/agent-action-recording-continuation.json /tmp/oob-plan-only-fixed-live
OOB_ANDROID_SQLITE=cache/oob-sqlite-wrapper python3 scripts/assert-agent-action-recording.py b49f281b /tmp/oob-plan-only-fixed-live/result.json /sdcard/Pictures/Screenshots/Record_2026-09-17-20-02-17_5b17c6e510bce824f9d850dff297e4be.mp4 /tmp/action-recording-verified.json
```

The regression verifies actual work, final response, consecutive conversation identity, restart and artifact integrity; a completion marker alone is insufficient. This addresses the evidenced model execution behavior; finite live tests do not guarantee all future model responses will comply.

Current-install audit: after the four-turn test completed, another shared-workspace build was installed at20:29:57. Its APK SHA-256 is `48a911b20bdd63d26f7ec3409933802420489e78c8d2cfefb650e1405986d55f`; both Chinese and English execution-contract strings were verified in its actual DEX. The original tested APK hash above remains the four-turn test artifact. A separate current-build UI recheck was initiated to validate the final installed state rather than relying solely on the earlier installation.

Final installed-state check passed: original conversation89, user entry41149, marker `OOB_LIVE_ACTION_CURRENT_1789648350260`, actual successful terminal call followed by final end_turn. UI sender/final reply both passed. The APK currently left installed includes the fix and has been exercised with the actual Agent. Total live acceptance: four consecutive task/correction turns including restart, plus this fifth check after the shared environment's later installation. Evidence `current-installed-turn.json`, `current-ui-journey.json`, and `fixed-ui-journey.json`.

## User-requested rollback and 0.6.3 Release verification

Removed only this task's added Chinese/English current-turn execution blocks; kept pre-existing workspace edits. Added negative assertions in the existing AgentSystemPromptTest for both removed headings. All 7 tests passed at 2026-09-17T12:48:50Z. ProductionStandardRelease build succeeded; APK DEX contains neither removed heading, and manifest is not debuggable.

APK SHA-256: `a0ad9151f9e200b79688d442092df753412b960d082a480a85f27bb81eeaf68f`. Version 0.6.3/code16. Signed with the existing Android debug/test certificate, NOT a formal distribution key. Installed with adb install -r to PJE110 b49f281b (Android16) at 20:54:02 Beijing time; package flags no longer include DEBUGGABLE. Existing conversations remained available.

Real UI smoke: original conversation89, ordinary request “Recheck the saved recording copy and report its full path and size.” (runner appends a unique acceptance marker). Run1789649660270 returned a completed reply reporting 4,802,335 bytes. Restarted app and the same completed response remained present. UI checks passed; release disables run-as, so this smoke does not independently assert canonical tool-event history or establish a complete fix for plan-only responses. The original multi-turn regression remains available; its earlier passes were on the subsequently reverted mitigation.

Executable entry: `OOB_ALLOW_PHYSICAL_DEVICE=1 node scripts/verify-agent-user-journey.mjs b49f281b scripts/fixtures/agent-user-journeys/agent-action-current-build.json /tmp/release-smoke`. After completion, the same runner supports a restart step followed by a reply assertion using that run's exact unique marker. Evidence: `rollback-release-artifact.json`, `rollback-release-ui.json`, `rollback-release-restart.json`.
