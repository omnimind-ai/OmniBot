# Shared Codex ACP v2 acceptance — 2026-09-14

Status: IN PROGRESS, NOT ACCEPTED as a complete phone + native desktop + WAN product.

## Implementation and boundary

The opt-in adapter uses official ACP SDK 1.4.0 experimental/v2 with pinned Apache-2.0 codex-acp 1.11.0. Codex execution stays in the already-running official 0.154.0 daemon. The adapter reuses upstream session registry, notification queue, item conversion and permission handling. No model configuration changes, copied sessions, lock removal or second Agent loop.

Android negotiates v2, keeps backend session/turn/item identities, and treats prompt response as admission only. Backend-derived state_update owns completion. Flutter consumes this through its existing reducer/coordinator. v1 remains supported. The SDK v2 surface is experimental; this is not a claim that upstream codex-acp 1.11.0 natively implements v2.

## Reproduced failures and executable regressions

- External user message absent from live upstream v1 projection: normalized authoritative item/started userMessage to v2 user_message. `scripts/verify-acp-shared-observation.cjs` now asserts passive user input, output, actual IDs, exactly one execution, replay and observation after the observer sends its own prompt. Real-model v2 run PASS, marker OOB_OBSERVER_84254C1D970F48F58B0894319BBCCE40.
- Reopened phone session receives no external updates before its first phone prompt: opening omitted conversationId. Bind the existing ephemeral runtime before session/load/resume. After this change, actual phone receives external reply E without first sending.
- External user message projected but placed at oldest end of history: live user projection used add() on the newest-first message list. Regression `ACP v2 state owns completion and whole messages replace chunks` now seeds prior history, checks live question order and preservation through completion. RED before changing to insert(0), GREEN afterward. Temporary identity-only diagnostics removed.
- Valid repeated/prefix chunks are append data, not inferred retransmissions. OOB prefix verified intact on phone A.

## Actual device and candidates

OnePlus PJE110 / serial b49f281b / Android 16. Installation preserves app data. Version 0.6.2.3, code 15. Local test route uses adb reverse 17321 -> isolated Bridge 17336 -> shared daemon. This proves no cellular/WAN connectivity.

Initial v2 APK: 24a7d618b792506fe59a6e015715e5f33ba74e4e1a4ce41a32818970ae533d8d.
Binding candidate: cbbdcedbcb7d960e030eb2753037944e3747ead44b36a4bd87f4cd9b4a1bec53.
Current ordering candidate: b3c3572ffc8355f68fba60a01b2ca2d9ccc44b3f5ca3b3422e139ddaa7e19ac7.

Shared test session: 01a09ae4-40da-7853-aa19-e1940cd4c4c4, isolated /tmp/oob-shared-session-probe.

- Phone A: send, full reply, idle, backend exactly once PASS.
- External B: phone automatically showed reply, user question missing from viewport FAIL.
- Force-stop/reopen before binding fix: C/D passive updates FAIL.
- Binding fix E: passive reply PASS, user question incorrectly ordered FAIL.
- Diagnostic F: confirmed user projected with backend turn/item IDs and no optimistic-message match; wrong end of history FAIL.
- Current candidate final physical regression: pending; append results below.

## Local checks

385 focused Flutter tests PASS after ordering fix. Native RemoteCodexAppServerSessionTest passed earlier v2 identity changes. APK build/install PASS. These do not substitute for physical-device acceptance.

Commands use Java 21 from Android Studio and Flutter 3.47.2 in /tmp/oob-flutter-3.47.2 (the default user Flutter 3.35/Dart 3.9.2 is too old for this repository). First build attempt failed because Java 21 was not selected; rerun with JAVA_HOME succeeded. First Flutter command used old SDK and failed dependency resolution; rerun with the repository-compatible SDK passed.

## Remaining gates

- Native original desktop SSH entry bidirectional GUI acceptance remains unverified. The app's local task API returns active-writer conflict on daemon-owned tasks and does not select that SSH entry. CUA native Codex automation was denied; no alternate UI bypass used.
- Website WSS route not installed: current 4090 account cannot edit root-owned Nginx and has no sudo. Requested usable deployment account/SSH alias; no answer yet. Cellular test unrun.
- Automatic socket reconnect + session re-subscription/history catch-up is not yet implemented end-to-end. Manual reopen history is a separate result.
- Resume during generation, lost admission acknowledgement, concurrent sends, approvals/cancellation and server restart require explicit results. Do not infer these from a successful simple prompt.
- v2 whole thought/message metadata completeness and upstream native history fallback need review before production enablement.

## Current candidate physical results

- G: external protocol turn 01a09e2d-10ef-73b1-a5ac-2d51a2d9c7eb; phone displayed one user question and one complete reply automatically, without reopen or preceding phone send. PASS. Evidence: artifacts/shared-codex-2026-09-14/phone-external-G.json and phone-external-G.png.
- H: phone-originated turn 01a09e2e-4602-7d00-a096-ca284ea4af31; forced stop of phone App while backend reported inProgress; same turn completed, exactly one new turn. PASS for backend continuity; phone history recovery pending.

- H recovery: reopening the same session on the physical phone restored exactly one question and one reply. PASS; phone-recover-H.json.
- I: after the force-stop/reopen lifecycle, external send 01a09e31-fd69-7fb0-a89e-23670b27163d still appeared passively as one question plus reply. PASS; phone-external-after-restart-I.json/png.
- Reproducible adapter deployment: fresh npm ci --ignore-scripts and npm run prepare:adapter from the new pinned package-lock passed. Generated upstream module/adjacent license and node_modules are ignored.
- Restored phone stay_on_while_plugged_in to original value 15. USB reverse remains mapped to test Bridge 17336 for the installed candidate.

- Navigation helper now waits for remote runtime restoration, and both physical UI scripts remove their prior generated XML before dumping; failed UiAutomator dumps cannot reuse stale screenshots/XML. Fresh-dump phone history I verification PASS.
