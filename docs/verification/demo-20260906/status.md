# Full demo acceptance — current source, 2026-09-06

Goal retained: clean source build; launch Ubuntu and Alpine; Xiaowan conversation, model and thought-level changes and continuation; Claude startup and session switch; DeepSeek Web UI conversation; Kimi conversation; OpenCode startup.

- Clean source build: PASS. `./gradlew --no-daemon --no-parallel clean :app:assembleDevelopStandardDebug -Ptarget=lib/main_standard.dart`, BUILD SUCCESSFUL in 2m17s; 637 executed / 7 up-to-date.
- APK `/tmp/oob-demo-clean-build-20260906.apk`, SHA256 `d2f07b8d30e728ba02ce8fe4fa320ef989023c50e6ab1b95c206822e128dc088`.
- Phone network: native Android curl also rejects registry.npmjs.org with self-signed certificate. Current network blocker is independently reproduced outside PRoot. Do not disable verification.
- Simulator: existing profile has real GLM-5.1 binding. Previous terminal launch briefly exposed `main-ubuntu (ubuntu)` and shell prompt, but captured command outputs were missing, so Ubuntu full acceptance remains incomplete.
- All remaining workflow steps require new current-build evidence. Historical documents are investigation context, not blanket completion evidence.

## Current-build simulator observations

- Clean-build APK installed on emulator-5556: Success, data retained.
- Ubuntu: created new `main1 (ubuntu)` using the visible terminal + menu. `pwd` returned `/root`; `cat /etc/os-release` returned Ubuntu 24.04.4 LTS and returned to the shell prompt. Screenshot `ubuntu-start.png`. PASS for launching and executing a real command.
- Earlier `main-ubuntu` was an ended session; Enter removes an ended session by existing TerminalBackEnd behavior. A new live session works. Do not treat old prompt text as proof of a live shell.
- Alpine: created new `main2 (alpine)` through the same + menu, ran `cat /etc/os-release`, received Alpine Linux v3.21 / VERSION_ID 3.21.0 and a fresh prompt. Screenshot `alpine-start.png`. PASS for launching and real command execution.
- Returned to chat, selected 小万 through visible mode menu, sent one isolated real-model prompt: `Remember demo code CEDAR_902. Reply only READY_CEDAR_902. Do not use tools.` Awaiting actual completion before configuration/continuation checks.
- Xiaowan real initial reply: `READY_CEDAR_902`, 6.0 s. Model catalog first refresh timed out; a second explicit refresh returned actual provider model choices (no fabricated list).
- In the same session ending `bab3040c`, selected DeepSeek-V3.1, then High. Read only that session's persisted config: provider `debug-llmthu-glm`, model `DeepSeek-V3.1`, reasoning effort `high`.
- Same-conversation continuation: asked for prior demo code without repeating it. Actual reply `CEDAR_902`, 9.9 s. Screenshot `xiaowan-continuation.png`. Configuration persistence plus successful continuation observed; direct inference-wire model/effort logging checked separately if present.
- Inference request log inspection (model fields only) confirmed DeepSeek-V3.1. No request effort field was present in those logs; the `high` value is proven by persisted ACP session config, not by an observed provider-wire field.
- Claude installation on simulator: npm log `2026-09-06T10_12_53_035Z-debug-0.log` exit 0. Initial startup rejected the existing OpenAI-configured connection. For this simulator demo, changed profile `debug-llmthu-glm` protocol to Anthropic through UI, retaining URL/key and model binding. Original protocol is OpenAI-compatible/chat_completions; restore when ending temporary configuration unless intentionally kept for the final demo. Phone profile unchanged.
- Claude 0.74.0 initialized successfully after connection configuration. First isolated session replied `READY_CC_MAPLE_17`; second new Claude session replied `READY_CC_BIRCH_28`. Returned via visible history to first, verified only first session history, asked for its code, got `CC_MAPLE_17` in 4.2 s. Screenshot `claude-session-return.png`. PASS for startup, real replies and session switching.
- OpenCode installation requested through its visible Install control while Claude ran. npm log `2026-09-06T10_16_59_509Z-debug-0.log` records `npm install opencode-ai@latest`, exit 0. Startup still needs verification.

## Remaining demo progress

- DeepSeek Web: launched from the visible drawer. Initial Android Chrome 109 failed `AbortSignal.any` when selecting a workspace. Installed the existing local Chromium 155 APK on the emulator after removing only disposable npm download cache; made it the emulator's browser. This is a test-environment change, not an app workaround.
- Independent first-navigation issue reproduced in both browsers: official DSH root-token exchange issues HttpOnly SameSite=Strict cookie, but Android external-intent redirect requests exclude it (`Network.requestWillBeSentExtraInfo` blocked reason `SameSiteStrict`). Direct address-bar navigation to the same local root succeeds. No token/cookie secret printed, no authentication bypass or cookie-policy modification. This first-open rough edge remains unresolved.
- Through Chromium 155's visible directory picker, selected `/workspace`, sent `Reply OOB_DEMO_DSH_WEB_RIVER_31`. Actual assistant paragraph matched marker. Verifier counted one user row, one assistant row, Send restored; repeated after foreground browser reload also passed. Screenshot `deepseek-web-reply.png`. Real GLM-5.1 response, ~21.7 s provider time. Background-tab observation timed out once; no resend occurred.
- Kimi: selected through app mode menu, sent `Reply only KIMI_DEMO_WILLOW_42. Do not use tools.` Actual assistant response `KIMI_DEMO_WILLOW_42`, displayed 7.8 s and 18:38:49. Screenshot `kimi-reply.png`. PASS for actual Kimi conversation under current Anthropic connection.
- OpenCode: both npm installs had exit 0, but initialization failed with exec code127. ELF interpreter is `/lib/ld-musl-aarch64.so.1`, absent from Ubuntu. The catalog had unconditionally selected musl, unlike terminal setup's separate distribution-aware installer. Replaced duplicate commands with one shared catalog asset consumed by both entry points; selects libc by rootfs, pins native binary to installed wrapper version, checks actual executable before relinking. Four shell execution tests passed, covering both distributions and loader failure. Device reinstallation/startup verification pending.
- OpenCode follow-up: 25 native installation tests passed, including shell syntax for every package combination on both distributions; 46 ACP contract tests passed. Initial test failure was a missing catalog fixture after unifying installer ownership and was corrected to supply the real assets.
- Build succeeded in 1m28s. APK `/tmp/oob-opencode-rootfs-20260906.apk`, SHA256 `2a75805a19c059e9f63703004944d2cdc59e03334a65b31e15e30850caf5384c`, data-preserving install succeeded on emulator and PJE110.
- Clicked OpenCode Reinstall on simulator. Both wrapper and exact glibc binary npm installs exit0; UI returned Assistant installed / ready to chat. Runtime health now online, installed true, preparationRevision `opencode-rootfs-libc-v2`; launcher points to `opencode-linux-arm64/bin/opencode`.
- Returned through visible settings back button, selected OpenCode from chat mode menu; welcome correctly identifies OpenCode. Startup requirement now passed. Sent an additional isolated `OPENCODE_DEMO_OAK_53` reply check; completion still being observed.
- OpenCode actual response completed: `OPENCODE_DEMO_OAK_53`, 15.1 s, completion clock 18:49:00. Screenshot `opencode-reply.png`. PASS for startup plus real prompt/reply.
- Simulator demo profile intentionally remains Anthropic with the same configured URL/key and GLM-5.1 binding, enabling the verified Claude/Kimi/OpenCode demonstrations. Phone connection settings were not changed.

## Completion audit still open

Observed execution covers Ubuntu, Alpine, Xiaowan real model change/continuation, two Claude sessions with return isolation, DeepSeek Web real reply/reload, Kimi real reply, and OpenCode real reply. Do not mark the full task complete yet: DeepSeek initial app-to-browser navigation still needs a direct-use fix; high reasoning effort is proven in persisted session config but not yet in outgoing model payload. Real-phone full conversation stability remains separately blocked by certificate-intercepted network; simulator evidence is not phone acceptance.

## Actual reasoning wire verification

- Added whitelisted effort/thinking metadata to the existing transparent test observer; it never logs request messages or credentials, rewrites responses, or retries. Two observer regression tests passed.
- On emulator, temporarily changed only test profile URL to the loopback relay, forwarding to the existing configured real endpoint. Current UI protocol is OpenAI/Chat Completions (supersedes the earlier inferred final Anthropic note).
- Selected High in the actual compact settings card; sent isolated memory-code prompt. Wire log records GLM-5.1, `/v1/chat/completions`, `effort: high`, response200. Actual reply `READY_EFFORT_PINE_64`.
- In the same conversation selected Low, asked for the code without repeating it. Wire log records `effort: low`, response200; actual reply `EFFORT_PINE_64`. Screenshot `reasoning-wire-continuation.png`, safe request evidence `reasoning-wire.jsonl`.
- Restored original `https://llmapi.paratera.com` via UI, verified the field value, stopped the temporary observer. Phone settings unchanged. Reasoning-setting-to-wire requirement now passed.

## DeepSeek direct browser navigation fixed

- Official DSH BrowserAuth returned a 303 immediately after setting its Strict cookie. External Android navigation retained cross-site classification; browser excluded the cookie. A minimal installer compatibility patch now returns a same-origin HTML document with a root meta-refresh after the same successful token check. Token verification, cookie signing, HttpOnly/SameSite=Strict, no-store and no-referrer remain unchanged. Unsupported upstream layouts fail preparation without patching.
- Two patch regression tests passed (idempotence, recognized exchange, unchanged unauthorized path, rejection of unsupported layout). Executed patched installed official BrowserAuth: valid token200, unauthenticated401, tampered cookie401, signed-cookie index succeeds, Strict/HttpOnly retained. Twenty-one native Web runtime tests passed.
- Build succeeded in21s. APK `/tmp/oob-dsh-navigation-20260906.apk`, SHA256 `d22996ab57bde42a05e7bcb802625fe976faba2e711951d18b49009a0999fc2a`. Data-preserving installation succeeded on emulator and PJE110.
- On emulator clicked DeepSeek Reinstall; completed installation and initialization. Then clicked the visible DeepSeek Harness Web row. Browser opened a new local service directly into the workspace, with no manual address entry, refresh, token handling, or authentication bypass.
- Sent `Reply OOB_DSH_DIRECT_OPEN_75`. Real assistant paragraph acknowledged that marker in a full sentence; exact-output helper correctly failed because the answer was not marker-only. Inspected the actual assistant paragraph, then verified one user row, one assistant paragraph containing the marker, Send restored, and persistence after reload. No resend. Screenshot `deepseek-direct-open-reply.png`.

## Requirement audit after current fixes

| Requirement | Authoritative evidence | Result |
|---|---|---|
| Clean source build | Clean Gradle build,637executed; subsequent changed-source builds successful | Passed |
| Ubuntu start | New visible terminal, actual os-release Ubuntu24.04.4, screenshot | Passed on emulator |
| Alpine start | New visible terminal, actual os-release3.21.0, screenshot | Passed on emulator |
| Xiaowan/model change/continue | Actual GLM initial reply, actual DeepSeek-V3.1 request and remembered code reply | Passed on emulator |
| Thought configuration affects request | High then Low through UI, both observed on real upstream requests, same-session memory retained | Passed on emulator |
| Claude startup/session switch | Two distinct marker sessions, returned first session retrieved only its marker | Passed on emulator |
| DeepSeek Web direct open/chat | Fixed login navigation, real reply and reload persistence | Passed on emulator |
| Kimi conversation | Actual marker reply7.8s | Passed on emulator |
| OpenCode switch/start | Fixed rootfs binary, actual marker reply15.1s | Passed on emulator |
| Real-phone install and full Harness stability | Latest APK install Success; native phone registry TLS request still fails self-signed certificate | Install passed; full real-phone acceptance blocked |

All listed demo flows have simulator evidence. Do not substitute that for the user's requested real-phone stability acceptance. The remaining external blocker is a working phone network for component updates/model requests. No TLS bypass or user data clearing was performed. The test observer was stopped and original endpoint restored.
