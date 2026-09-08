# 0.6.2

Android versionName: 0.6.2; versionCode: 12.

## Changes

- Repair terminal environment detection and Ubuntu extraction/installation paths, and validate managed Harness installation before reporting readiness.
- Improve provider model discovery, explicit refresh, selection and reasoning configuration through the existing ACP session owner.
- Preserve completed Codex messages and Claude Code terminal output through the supported adapter boundary.
- Restore context compaction, large-history database protections and cancellation safeguards without introducing a second Agent lifecycle.
- Repair completed-run accessibility so the fold control can be activated independently of the answer; preserve the established chat presentation.
- Add regression coverage for installation, provider wires, output, model selection, history and memory handling, plus reusable device journeys.

## Known limitations

- OmniBot official gateway access from Claude Code has returned HTTP 403; successful authentication/initialization is not proof of a successful chat. This release does not claim that gateway authorization issue is resolved.
- Full real-API acceptance across every Harness remains incomplete. Earlier live tests exposed Kimi session restoration, DeepSeek reasoning-wire and Codex auxiliary-model compatibility gaps.
- Xiaowan reasoning was observed and could be expanded after restart on the emulator; the zero-second reasoning label and the user's original phone-only symptom are not declared fully resolved.

Validation results will be recorded in the release PR. Local CLI tests, widget tests and actual device/API checks are reported separately.
