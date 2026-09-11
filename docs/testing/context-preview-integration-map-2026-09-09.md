# Context and preview integration provenance

Integration target: codex/integrate-local-fixes, starting HEAD a4a06744f.

The separate installed preview package was built from release commit 4ccc7bbbc
plus e0bf4201f. It did not contain this branch's accumulated runtime work.

## Existing context implementation retained

- 8c16e38d9 introduced the accumulated AgentContextBudget source port and the
  existing orchestrator/compactor integration. Pi upstream is pinned in
  docs/third-party/compaction.md; Gemini multilingual estimation and tool output
  budgeting are documented there too.
- 01fd832f6 bounds context across restored history, compaction and ACP projection.
- 4a8ac38b3 adds bounded provider-overflow recovery within the existing owner.
- 8496e2adb adds the reusable Xiaowan context regression suite.

The source implementation was checked: the orchestrator estimates the full first
request without usage and adds trailing messages to valid usage on later rounds;
its compactor handles request overhead and large tool outputs. History restoration
uses the existing bounded projection. These satisfy the scope of this conversation's
minimal preflight patch without introducing another estimator or Agent loop.

The uncommitted PiContextTokenEstimate patch in ../OpenOmniBot is NOT copied into
this branch: it overlaps AgentContextBudget and pins a different Pi revision.
It remains preserved in that working directory. Its prior 116-test result belongs
to that separate tree and must not be claimed for the integrated candidate.

## Imported now

From e0bf4201f: resource link box layout fix, six HTML/Word regression cases, and
explicit test-only WebView platform dependency (same locked version 2.15.1).
Existing unstaged/staged runtime and tool changes in this integration workspace
are preserved and are not silently included in the preview import commit.

The current emulator still runs the preview-only 0.6.2.2 package. This import is
not a claim of a full-candidate build, installation, or physical-device acceptance.
Full integrated runtime checks and device verification remain required.

Import verification: all six preview regression tests passed in this integration
workspace (`/tmp/oob-full-integration-preview-tests.log`). This is a focused UI
check, not a rerun of the accumulated native context/runtime regression suite.
