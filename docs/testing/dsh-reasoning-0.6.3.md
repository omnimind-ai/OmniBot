# 0.6.3 — DSH reasoning configuration

> 历史归档：本文记录旧版本 DeepSeek Harness 的验证结果。2026-10-29 起已移除该 Harness 的内置支持及专用验证脚本，以下安装与测试步骤不再适用于当前版本。

Release base: 11a380d72bd1385422c3d8ec9e521a895d22bb8d, the target recorded by the published v0.6.2.3 release. The in-progress remote Codex/WSS changes are outside this release worktree.

## Reproductions

- The generated DSH generic OpenAI profile declared `reasoningEfforts.off: null`. Official Pi treats this as omission, so Off did not explicitly disable a server's default reasoning. `OOB_TEST_LEGACY_OFF=1 node scripts/verify-dsh-reasoning-wire.mjs INSTALLED_DEPS` fails after the high request succeeds: expected `none`, received an omitted field.
- DSH 0.1.2-rc.1 on the physical PJE110 restored the last executed high setting after selecting Off and replacing/restarting the APK before another send. Fresh Android accessibility inspection showed `思考强度 / 高`, not Off. The 0.1.5 upstream ACP source still restores configuration from the last logged request header.

## Changes

- Generic OpenAI completions profiles explicitly map Off to `reasoning_effort: none`. Known native Provider dialects retain upstream serialization. No model-name switches or frontend layout changes.
- The existing local ACP runtime and profile store persist accepted configuration, including unsolicited official configuration updates. Restored sessions apply saved values through the same ACP configuration mutation before being returned. Failed restore removes the provisional session registration and retains the saved choices. No prompt replay or synthetic turn completion is added.
- App version 0.6.3 / code 16. Existing release signing workflow is reused.

## Executable regressions

- `:app:testDevelopStandardDebugUnitTest --tests '*AgentWebRuntimeTest' --tests '*LocalAcpRuntimeConfigTest' --tests '*LocalAcpSessionCloseTest' --tests '*LocalAcpSessionDeleteTest'`
- `node scripts/verify-dsh-reasoning-wire.mjs INSTALLED_DEPS` (official Pi 0.84.4; high/off/high/off, exactly four local fixture requests). Included under the existing runtime test runner's optional `--dsh` entry; install the test dependency with `npm install --prefix INSTALLED_DEPS --ignore-scripts --no-audit --no-fund @earendil-works/pi-ai@0.84.4`.
- `python3 scripts/verify-dsh-phone-reasoning.py SERIAL SESSION_FILE MARKER=off MARKER=high ...` inspects only synthetic cases in the explicit DSH durable journal and rejects duplicate user or assistant messages.

## Physical evidence and remaining acceptance

Device: OnePlus PJE110, serial b49f281b. Existing DSH 0.1.2-rc.1 with Pi 0.84.4. Model: DeepSeek-V4-Flash-0731 through its configured gateway.

- Candidate 0.6.2.3/code 15, SHA256 `fca3eb6293a01f996bee94920019d885f34c62dca61272a80cfe1fb82cbf9a4f`: installed successfully with data preserved.
- `OOB_DSH_OFF_063_B`: real UI Off send completed; upstream assistant journal contains text only, 20 output tokens.
- `OOB_DSH_HIGH_063_C`: same-session UI High send completed; upstream journal contains nonempty reasoning and text, 111 output tokens.
- Independent 0.6.3/code 16 APK installed successfully; the pre-persistence-fix restart reproduction above failed.
- Final configuration persistence fix: all 29 focused JVM tests and APK build passed. APK SHA256 `691a24e021483a07281ba862781b3e56442ca81f5be99d2e15ca4379438e9a67` installed with data preserved (0.6.3/code 16). Agent-operated physical UI: selected Off, force-stopped before any send, reopened the same DSH session; after authoritative configuration loaded, it remained Off.
- Post-restart `OOB_DSH_OFF_063_D` send failed with upstream `Connection error`; Android reported `Active default network: none`. Wi-Fi enabled but unconnected. No automatic send replay performed. The failed request is not counted as a passing generation test. After the user restored network, a new explicitly sent case was used; the failed turn was not automatically replayed.

No claim of all Provider/Harness coverage, production WSS, automatic remote catch-up, or original native Codex desktop GUI acceptance.

Final-generation evidence: on the final 0.6.3/code 16 APK, `OOB_DSH_OFF_063_E` (turn 5) has text only after idle-Off/restart; `OOB_DSH_HIGH_063_F` (turn 6) has nonempty reasoning after re-enabling High. Both have exactly one user and one assistant record. See `artifacts/dsh-reasoning-0.6.3/phone-final.json`.

## Published release verification

GitHub Release v0.6.3 and the website stable update route are live. CI run 34869067139 completed successfully. Both actual APK downloads have SHA256 `918727e61fc0683bda641aa1d1eedaf3389249407072b003fe08dab7700feca1`; Android package metadata is 0.6.3/code 16 and release signature verification passed. See `artifacts/dsh-reasoning-0.6.3/release-verification.json`. The physical phone retains the tested debug build and user data because the production signing certificate differs.
