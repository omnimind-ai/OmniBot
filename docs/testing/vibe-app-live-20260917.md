# Real Vibe App generation and UI acceptance — 2026-09-17

Result: **passed on emulator after two repairs; 待真机验证**.
This was not first-attempt success. The real model generated the source through
normal chat tools, checked it and published local.project.vibe-notes-test. We did
not substitute a hand-written sample App or fake its tool responses.

Device: emulator-5580, sdk_gphone64_arm64. App 0.6.3 (16), Vibe Builder 0.2.1.
APK identities and installation status are under artifacts/vibe-live-20260917.

## Observed failures and repairs

1. Real chat run OOB_LIVE_VIBE_BUILD_1789628742792 failed repeatedly when file_write
   normalized SVG. Android rejected an unescaped closing brace in a CSS regex.
   Stopped via the real Stop button; canonical cancellation verified. Escaped the
   brace in FileToolHandler. 17 SkillRuntimeBehaviorTest tests and build passed.
   The later repair turn successfully wrote icon.svg on Android.
2. Model-generated manifest lacked schema_path and the generated Skill had the
   wrong name. project_check rejected both. The model repaired them and published.
3. The first opened App inserted VIBE_UI_21a85db059e1 into SQLite, but displayed
   an empty list. Model source used limit/order_by instead of _limit/_order_by
   and rendered the query object as an array instead of result.rows.
   Fed the actual failure back through chat. The model rewrote toolkit.json and
   app.js, checked and republished. Runtime project_contract and packaged Builder
   Skill now describe SQLite arguments/results explicitly. Nine contract tests
   and build passed. Existing database records were preserved.

One continuation UI attempt was interrupted by another operation navigating the
shared emulator to Trajectory. Its result remains failed. Verified no admission,
resumed only our exact partial draft and sent it once; later canonical history
assertions passed. No request was silently replayed.

## Actual UI acceptance

scripts/verify-vibe-notes-ui.py opens the generated plugin from its detail page,
uses the actual WebView input and Save Note button, observes the saved note,
queries SQLite read-only to assert exactly one row, force-stops/restarts the app,
and reopens through the same plugin UI.

Final execution passed:
- Open App displays the generated notes UI.
- Previously saved note survives the model's republish.
- New VIBE_UI_d3d58530cd57 appears after Save Note.
- Exactly one SQLite row exists for that marker.
- Force-stop/relaunch and reopening preserves the visible new note.

Evidence: artifacts/vibe-live-20260917/app-repaired/result.json and screenshots.
Initial failures: build/events.json, app-first/result.json and generated-first/.
Final model-generated source: generated-final/. Canonical real-model completion:
continue-observed/result.json and repair/result.json; repair/events.json also
proves successful SVG write, project_check and project_publish.

## Executable regression

```sh
node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/vibe-build-notes.en.json OUTPUT
# After generation succeeds, actual App UI checks:
python3 scripts/verify-vibe-notes-ui.py SERIAL OUTPUT [PRIOR_NOTE_MARKER]
```

Requires configured real Provider, enabled Builder, idle exclusively available
chat, and the synthetic vibe-notes-test project namespace. Initial and corrective
chat scenarios are retained under scripts/fixtures/user-scenarios/vibe-*.json.
Do not count reply markers alone as App acceptance; require the actual UI and
SQLite/restart assertions. Physical device, pinned shortcut and arbitrary other
App types are not covered by this run.
