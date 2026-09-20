# Physical Release remote Sessions diagnosis

Device: PJE110 b49f281b, Android16; installed 0.6.3/code16 productionStandardRelease, installed20:54:02, not debuggable. User reports Sessions cannot load.

Observed on Computer tab: AGENT_RUNTIME_CALL_FAILED during connect, Unable to resolve host indicating-readers-insurance-peripherals.trycloudflare.com. Actual Retry reproduces the same error. Phone resolves and pings www.cloudflare.com successfully. SSH4090 independently cannot resolve the saved tunnel hostname, resolves www.cloudflare.com successfully, and pgrep -x cloudflared returns no processes. Historical deployment record shared-session-acceptance-2026-09-15.md identifies this exact hostname as a foreground temporary quick-tunnel service. Evidence supports unavailable temporary tunnel endpoint, not system prompt or local history corruption.

Physical UI switched to Local: no Sessions load error and local navigation/list controls rendered; returned to Computer afterward. This does not certify all local session resumes. No endpoint/token, prompt, runtime lifecycle, or server process was changed. Remote recovery remains pending an available Bridge endpoint and pairing/configuration update; do not mark fixed.

Executable symptom regression (fails on the actual Computer error):
`OOB_ALLOW_PHYSICAL_DEVICE=1 python3 scripts/verify-session-list-visible.py b49f281b docs/testing/artifacts/remote-session-endpoint-20260917/remote.json`

Returned exit1 with sessionLoadFailureVisible=true and dnsFailureVisible=true. Same read-only probe on Local reports both false. Absence of this error alone does not prove remote recovery; successful authenticated session listing/resume must be tested after endpoint repair. Evidence exports only booleans and device identifier, no session titles or credentials.
