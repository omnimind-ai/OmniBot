# Real-device Harness switching and error presentation — 2026-09-06

Device: PJE110 (`b49f281b`), installed with `adb install -r`; app data retained.

## Reproduced failures

- Claude Code selection with the existing OpenAI-configured connection was rejected before initialization. The chat toast rendered the entire native PlatformException, including implementation names and endpoint. Settings already shortened errors but chat bypassed that path.
- Temporarily choosing Anthropic for the existing connection allowed Claude initialization. One isolated test prompt ended with the official `Internal error: Request timed out` at 234,021 ms. Initialization alone does not prove a successful model round trip, nor does a gateway recognizing `/v1/messages` prove account/model compatibility.
- Restored the original OpenAI connection type after this diagnostic. No key was read, printed, or changed.

## Change

Reuse `formatAgentRuntimeErrorForUser` for switch, model/config loading, reasoning changes and assistant settings. Known failures give short actionable messages; unknown payloads cannot enter normal UI. Exact application-owned messages remain idempotent through durable error projection. No automatic retries, new lifecycle, protocol inference, endpoint rewriting, or weaker security checks were introduced.

Existing `HarnessSwitchSendBarrier` and conversation target ownership retain drafts, serialize selection, skip superseded work, and refuse queued sends after a failed switch. Existing native selection restores the previous selected profile on failure.

## Verification

- 69 focused service, assistant settings and switch-barrier tests passed. Includes unknown payload/credential non-disclosure, actual timeout shape, bilingual/idempotent projection, and failed queued sends.
- Additional reducer/config-card/architecture regression results recorded after execution. Assertions expecting raw internal strings were updated to the intended user-facing message; existing reload, value rollback and terminal-state checks remain.
- Dart analysis: no errors; 4 warnings and 8 infos in the chat mixin (unused member, unnecessary assertions/qualifiers, missing override annotation).

### Device observations before final UI reinstall

| Interaction | Observed result |
| --- | --- |
| Claude, original connection | Compatibility rejection; previous UI exposed native exception |
| Claude, temporary Anthropic configuration | Initialized; actual prompt timed out after 234 s |
| Kimi Code | Initialized, submitted one prompt; cancelled during prolonged wait |
| Codex | Initialized; repeated selection retained active test conversation |
| OpenCode | Initialized after switching away from active Codex conversation |
| DeepSeek Harness | Initialized; submitted independent test prompt |

These observations are agent-operated device testing. They do not establish successful replies or long-term stability for every Harness. Phone-installed CLIs are not identical to the independently tested desktop CLI versions.

### Final checks and reinstall

- Additional reducer/config-card/architecture tests: **223 passed** (292 with the focused suite).
- APK build: **BUILD SUCCESSFUL in 3m**. Installed `/tmp/oob-safe-errors-20260906.apk` on PJE110 with `install -r`: **Success**.
- APK SHA256: `67c2a086a9fc4db504a83414d23132c6a8a490ddc536ded08d954a5497ea48ec`.
- After reinstall: switched to 小万, typed isolated draft `OOB_DRAFT_SWITCH_TEST`, attempted Claude with original configuration. UI showed only `当前模型连接不适用于这个助手，请更换模型连接或助手。`; remained on 小万; exact draft retained; no `PlatformException` in UI hierarchy.
- Immediately selected Kimi after rejection: initialized, welcome identifies Kimi, exact draft retained. Removed only the isolated test draft afterward.
- Codex background test reached official `terminal_end_turn` at 176,194 ms, but after APK restart its persisted assistant text was `stream disconnected before completion: error sending request for url (...)`. **This was not a successful model reply.** The phone adapter identified itself as 1.1.7; bundled requested version is 1.10.0. DeepSeek reached `terminal_error` at 45,668 ms (`DeepSeek API request ... failed`). These are runtime terminal evidence, distinct from checking rendered response contents.
- Claude's original protocol guard remains. Successful initialization with temporary protocol selection followed by a timeout is insufficient evidence to remove compatibility validation or silently alter the shared connection type.

Outstanding: successful real-account replies and repeated long-term runs for all six Harnesses remain unproven; Claude/DeepSeek request failures need account/endpoint/runtime-version diagnosis. The UI improvement does not claim to fix an upstream timeout or provide automatic model fallback.


## Follow-up found during persistent acceptance

Initialization incorrectly saved the currently declared `preparationRevision`, even for an older installed executable. Explicit preparation then trusted the false revision and skipped installation. Readiness and installed revision are different facts.

The existing explicit `agent/prepare` now accepts `force: true` from the user’s reinstall action, bypassing reuse only for that action and retaining the single installation gate. Successful installation records its revision; successful initialization preserves that recorded revision. No installation was added to switching or prompt admission. Managed assistants expose Reinstall even when their current process initializes successfully.

## Codex failure normalization

Read the installed official `@agentclientprotocol/codex-acp` 1.10.0 package (`AirExtension`, `CodexEventHandler.createErrorEvent`, `CodexAcpServer.terminalFailurePromptResponse`). Without negotiated typed failures, it sends many transport failures as `agent_message_chunk` and ends with `end_turn`. This also occurs in the older phone adapter.

The existing Codex adapter now advertises the package's `jetbrains.air` version 1 `sessionFailure` capability. Only explicit severity `error` metadata on the owning ACP PromptResponse normalizes that same prompt completion to failure. Warnings and ordinary text do not drive state. There is no new stream, terminal event, request replay or session lifecycle. The existing error formatter hides the address and gives a network/retry action.

Verification after the follow-up: 71 focused Flutter tests passed; 61 native tests passed (49 config adapters, 2 shipped catalog, 10 preparation). Actual official Codex CLI controlled-failure checks passed for both configured wire variants: one request per case, structured terminal failure present, no failure detail emitted as assistant text. Evidence: `docs/verification/harness-adapters-20260906/codex-typed-failure.jsonl`.

Final APK with structured failure support: `/tmp/oob-harness-final-20260906.apk`, SHA256 `968dc87f6757404617f24fcba237f1bff88d38b77b3055f86665b5d7c2dfef3d`; build succeeded in 50 s; `adb install -r` succeeded on PJE110. Official CLI success cases also passed with the negotiated capability (two cases, one request each), and 46 capability contract tests passed.

## Actual installation/network root cause

Reading the phone's installed package (rather than the catalog readiness flag) proved Codex remained 1.1.7. The explicit installer had run the intended `@agentclientprotocol/codex-acp@1.10.0` command, but its npm log recorded three `EAI_AGAIN` failures resolving `registry.npmjs.org`, exit 1. The settings page returning to a previously online row was not evidence that an update succeeded.

The guest `/etc/resolv.conf` contained only `nameserver 8.8.8.8`; Android's active network supplied different resolvers. `OmnibotTerminalEnvironment.buildTerminalEnvironment`, already shared by hidden Harness and interactive terminal launches, now writes an atomic resolver file from the current active network. `init-host.sh` mounts that file as the guest resolver configuration when available, for either distribution. No resolver is invented if the system provides none; no background polling or network-retry owner is added.

Three resolver unit tests cover current-network addresses, deduplication/limit, IPv6, no network and changed-network snapshots. Shell syntax check passed. Device network verification follows installation of the resolver fix.

Resolver-fix APK: SHA256 `5b07b1374f84a129146ed9372dba99617816d166b741936910a3a0a740dc8f62`, `/tmp/oob-network-fix-20260906.apk`; build succeeded in 31 s and data-preserving phone installation succeeded.

On PJE110 after the fix, the generated resolver file contains three system-provided entries and no fixed Google resolver. Retrying installation changed the failure from `EAI_AGAIN` to `DEPTH_ZERO_SELF_SIGNED_CERT`. Android marks the current Wi-Fi as captive. This establishes that DNS was repaired but does **not** establish usable Internet access or successful Harness update. Requested that the user sign in to the network or switch to working mobile data. Certificate verification remains enabled. The installed Codex package is still 1.1.7 until a successful download; structured-failure behavior on the real phone therefore remains blocked even though official CLI tests pass.

Installation exceptions previously only displayed a transient toast. Because old installed software can still be marked online, this could be mistaken for a successful update once the toast disappeared. The existing settings result sheet now handles both returned failures and thrown installation errors and remains visible until dismissed. A widget regression advances ten seconds, asserts the failure/action remain visible, confirms no raw TLS code, and proves no automatic second install.

### Final delivered build

- `/tmp/oob-harness-verified-ui-20260906.apk`
- SHA256 `e0d7cc52044eb11989c748d0a33b5b49a7413db749b98de963c316a7c8738cdb`
- Build succeeded in 34 s; PJE110 `adb install -r` returned Success.
- Final service/settings suite: 68 passed, including persistent installation failure panel. Focused Dart analysis: no issues.
- Earlier unchanged checks: 223 reducer/config-card/architecture tests, 5 switch-barrier tests, 61 native adapter/preparation tests, 3 DNS tests, 46 capability contracts, and four real official CLI cases (two success, two controlled failure).
- Real-device acceptance remains partial. Failed switch retains draft and original assistant; next switch works. DNS now uses system network. Successful real-account replies and updated Codex structured-error rendering still require a network without certificate interception and a completed component update. No TLS bypass, data clearing, or credential change was performed.

## Recovery controls and ordinary UI follow-up

- Kept Configuration separate from Install/Reinstall for managed assistants in every readiness state. Previously any non-online state redirected the configuration row to preparation, so correcting a connection could inadvertently reinstall the assistant.
- Ordinary list hides managed launch/protocol descriptions, labels the shared choice Default model, and uses Could not start / 启动失败. Technical configuration stays in its configuration screen. Reused existing settings rows and runtime actions; no new retry or lifecycle owner.
- Focused service/settings/switch barrier suite: 73 passed. Settings suite repeated after copy/layout cleanup: 11 passed; focused Dart analysis clean.
- Real PJE110: failed Claude row kept both Reinstall and Configure; tapped Configure and reached Claude configuration with no raw PlatformException visible. This is UI recovery evidence, not successful Claude inference.
- Final build succeeded in 29 s. Data-preserving install succeeded on PJE110. APK `/tmp/oob-recovery-ui-final-20260906.apk`, SHA256 `4621ce3db2f03e9ca807d92ebdfe79a77886bc3d35225d38c733af9668200fce`.
- Phone registry request still fails certificate verification even using native Android curl. Full real-account Harness stability remains unverified until connectivity and component updates succeed.
