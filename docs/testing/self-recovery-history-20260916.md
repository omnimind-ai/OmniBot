# Self-check and durable recovery evidence — 2026-09-16

User requirement: enhance existing self-check/self-recovery without coupling new
Agent loops, avoid a provider error blocking subsequent requests, retain learning.

Changes:
- Bundled check-provider.mjs matches native header precedence (case-insensitive
  custom override); supports custom-header authentication and anonymous service;
  rejects empty authentication overrides without issuing a request.
- Existing ProviderFailureJournal retains at most 20 category-only failure
  snapshots under skill data/provider-diagnostics, alongside the latest diagnostic.
  No keys, bodies, models, endpoints, prompts or conversation text are copied.
- Existing skill explains missing credentials, bounded transient recovery and
  evidence-based repair persistence using its existing learning/memory tools.
  Original operation plus subsequent request/restart verification are required
  for a verified permanent repair. Probe success alone is not that evidence.

Lifecycle ownership is unchanged. Existing HttpAgentLlmClient transport owns
bounded pre-output retry; existing canonical ACP prompt cleanup owns completion.
The Context journal entry remains best-effort and cannot throw storage errors
into prompt cleanup. The skill cannot repair an unavailable upstream or invent
credentials. Encrypted settings remain owned by existing Provider settings.

Executable verification:
- node --test scripts/provider-recovery.test.mjs (5 pass)
- skill-creator quick_validate.py (pass)
- :app:testDevelopStandardDebugUnitTest --tests '*ProviderFailureJournalTest' --tests '*BuiltinSkillAssetsTest' --tests '*HttpAgentLlmClientTest'
Includes bounded history, asset refresh preservation, cancellation, safe records,
retry exhaustion then a subsequent request, and no replay after visible output.

Physical-device acceptance: **待真机验证**. No physical device available in this
session; automated results do not establish end-to-end phone recovery. Prior
Release artifact does not contain this enhancement.

Focused JVM run: 37 tests passed, Gradle success (50s).
