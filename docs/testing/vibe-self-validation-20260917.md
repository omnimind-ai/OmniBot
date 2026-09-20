# Vibe Builder owns testing and repair

User requirement: the creating Agent must execute complete tests and fix failures itself; the supervisor audits evidence instead of issuing each repair step.

Persisted in Vibe Builder 0.2.2 (packaged marker v12): read connector contract before coding; maintain executable tests and evidence; publish a test candidate; execute actual business/UI workflows; repair and rerun affected tests in the active task; do not declare completion for unexecuted requirements. Life XP failures now provide concrete date/XP/history/duplicate/midnight/multi-day/100-row/reopen cases. Removed unsupported event-listener/runId/cancellation instructions from the Promise-only bridge documentation.

Independent subagent reviewed actual runtime capabilities and updated instructions. Incorporated its findings: new tool definitions may be unavailable in the current turn snapshot; subagents inherit tools; terminal tests are not native/UI evidence; `vlm_task` requires the real runtime and permissions; process death recovery requires an external runner. No second ACP loop, forced continuation, or hard runtime gate was added. Skill instructions cannot override a Provider ending its turn.

Verification:
- Scoped `SandboxBundleDefinitionTest`: 4 tests passed (marker v12 and existing contract assertions). An earlier run caught a removed consistency instruction; it was restored before the passing rerun.
- `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon --no-parallel :app:syncPluginAssets -Ptarget=lib/main_standard.dart`: passed.
- `python3 scripts/verify-vibe-package.py app/build/generated/plugin_assets/main`: verifies version and byte equality of Skill, tool definitions, and both references to repository source. Results under artifacts/vibe-self-validation-20260917/.
- Existing 0.2.1 APK deliberately rejected as stale. No new APK installed and no claim that the phone has loaded 0.2.2.
- Prior executable Life XP scenarios and `scripts/verify-life-xp-demo.py` remain the real behavior regression; not rerun with 0.2.2 this turn. New Skill model-following behavior not yet accepted. 待真机验证。

The packaged source is durable; installed devices require an APK/plugin update before using the new instructions. This change adds specific executable-test requirements, not a claim that prompt text guarantees successful self-repair.
