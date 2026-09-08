# Chat first-response latency investigation — 2026-09-07

## Current state — supersedes experimental implementation notes below

The overall latency issue remains **unresolved**. The final source keeps two measured, bounded fixes: shared SSE HTTP clients, and preserving the session's selected reasoning effort when changing models. Initial reasoning remains `default`; no tools or memory capabilities were removed.

The [cache investigation](prompt-cache-hit-verification-2026-09-07.md) is an archived experiment. The experimental verified-cache-support registry and its invalidation hooks were removed. Temporary Flutter/HTTP timing instrumentation, debug latency receiver operations and the default-`none` experiment were also removed. Historical commands below describe the investigation build and are **not available in the final ordinary APK**.

Latest actual emulator UI runs, with reasoning disabled for the control, returned first content in 6.018 s and 14.494 s in the same session. Thus disabling reasoning does not satisfy a consistent sub-ten-second acceptance criterion. A real current-time tool request retained all 58 tool definitions and completed in 28.499 s: model round one took 13.840 s, tool execution plus transition into the next round took about 0.1 s, and model round two took 14.231 s. The separate transparent Node relay measured upstream response-header waits of 13.755 s and 14.191 s. The relay uses identity encoding, so these are not independent encoding-comparison samples. These measurements locate the dominant delay outside local turn admission and tool execution; they do not distinguish gateway queueing, model inference or remote response buffering.

A phone control with reasoning disabled and cached tokens 10,368/10,447 still waited about 15.98 s for response headers. Neither cache hit nor thinking-off guarantees a short response. Comparisons with `v0.6.0.3` request parameter shapes were variable and did not establish that restoring old parameters resolves the delay. The old APK itself was not installed for this comparison.

Final source validation: `/tmp/oob-minimal-speed-final.log`, BUILD SUCCESSFUL, 68 tests with zero failures (AgentOrchestrator 59, XiaowanSessionConfig 5, connection reuse 2, SSE lifecycle 2), plus normal develop-standard debug APK. Earlier broader suites passed 200 native and 156 Flutter tests; those precede the final default-policy revert, whose affected tests were rerun in the 68-test check. Real UI evidence is in `/tmp/oob-speed-final-emulator.log` and `/tmp/oob-speed-tool-emulator.log`; temporary files are local investigation artifacts, not durable CI fixtures.

Remaining requirement: access to the configured `llmapi.paratera.com` gateway source or server-side request timing logs to distinguish and repair the remote wait. The repository alone does not provide that access. No claim of complete latency repair is warranted.

## Full submission and HTTP transport measurement, 01:09–01:10

After the user reported that cache work did not resolve the delay, added debug-only Flutter stage logging and an explicitly enabled HTTP transport diagnostic. Official SSE clears EventListener, so application/network interceptors measure dispatcher admission, connection readiness, request-body write completion and response headers. Request content, URL, headers and credentials are not logged by this diagnostic. Body write completion means bytes were handed to OkHttp's network sink, not proof of server receipt. The diagnostic adds no flush, retry or lifecycle transition; it is disabled by default and enabled by the debug receiver for this process only.

Two messages were sent through the real phone Flutter composer, each after verifying an empty draft and exact test text. Both assistant replies were verified on screen. Same phone process 10748, same ACP session suffix `6a203d5c`; first turn `214b7f7a`, second `0f16d320`.

| Measured interval | Message A | Message B |
| --- | ---: | ---: |
| Click -> switch/bootstrap/model configuration ready | 1 ms | 0 ms |
| Agent send entry -> status ready | 92 ms | 54 ms |
| Agent send entry -> history committed/session ready | 380 ms | 339 ms |
| ACP reservation -> prompt sent | 24 ms | 4 ms |
| Click -> prompt sent | 465 ms | 430 ms |
| Click -> HTTP request body written | 974 ms | 784 ms |
| Dispatcher admission -> connection ready | 65 ms | 0 ms |
| Body written -> response headers | **24,260 ms** | **17,132 ms** |
| Response headers -> first provider content | 22 ms | 51 ms |

Request A: click 01:09:07.550, body written 01:09:08.524, response headers 01:09:32.784, official completion projected at 01:09:32.858. Request B: click 01:10:09.475, body written 01:10:10.259, headers 01:10:27.391, completion 01:10:27.519. Both immediately entered the HTTP dispatcher. The second reused the connection and existing session, with no old-turn cleanup delay.

This reproduces the user's slowness on the actual UI path. For these turns the long interval is inside the network exchange after body write, not pre-prompt lifecycle admission, MCP startup, session recreation, or HTTP dispatcher queuing. It does not distinguish remote gateway queueing, inference, network delivery or server buffering, and does not prove other unmeasured Harnesses cannot have separate defects. The overall delay remains unresolved.

Validation: 156 Flutter lifecycle tests passed; five native connection/SSE tests passed, including diagnostic-enabled exact POST bytes, two requests only and keep-alive reuse. Debug APK built and installed on b49f281b. An additional emulator installation was unavailable due to insufficient storage; no emulator data was deleted. Metadata-only trace excerpts: `/tmp/oob-lifecycle-trace-A.txt`, `/tmp/oob-lifecycle-trace-B.txt`; build `/tmp/oob-lifecycle-wire-trace.log`, diagnostic regression `/tmp/oob-lifecycle-trace-regression.log`.

## Scope and measured findings

The user reports roughly ten seconds of waiting on every message across assistants. Read-only phone ACP logs and explicit debug-only Provider probes were used; no conversation content, credentials, provider bindings, or reasoning preferences were changed by the probes.

A real Xiaowan turn on phone b49f281b recorded:

- ACP initialization: 127 ms; session creation: 112 ms.
- Provider request_dispatched: 146 ms; stream_open: 18,659 ms; first_event: 18,660 ms; first_content: 19,021 ms (same Provider timing origin).
- Thus about 18.5 seconds elapsed after dispatch, before HTTP stream open, rather than in MCP initialization or Flutter projection.

The saved request was 128,291 UTF-8 bytes, with 16 messages and 58 tool definitions. Size alone does not establish causation; history and tools were not removed from production requests.

Direct same-phone, same-bound-Provider GLM-5.1 probes with a short synthetic prompt and no MCP execution showed response headers at 4,713 / 4,668 / 4,171 ms; initial DNS/TCP/TLS completed within about 152 ms. One sample failed after headers. A subsequent probe batch had four SocketTimeoutExceptions after request dispatch, whereas a 33,770-byte request with tools and thinking disabled received a response at 2,603 ms. This rules out MCP as a necessary cause of the delay; it does not identify whether the remote gateway, model serving, or another part of the response path is responsible.

Correction: the initial probe set a 60-second *call* timeout but retained OkHttp's default 10-second *read* timeout. Those failures are not evidence of sixty seconds of waiting. The final debug probe explicitly sets both to 20 seconds and records failure elapsed time.

Final explicit-20-second probe, `thinking_disabled_with_tools`: request sent at 189 ms, response headers at 19,404 ms, first SSE event at 19,406 ms, first content at 19,407 ms. It returned HTTP 200 successfully. Thus ~19.2 seconds preceded response headers, while event/body delivery added only 3 ms. The same shape previously returned in 2.6 seconds: a large variability remains on the remote response path even with reasoning explicitly disabled.

## Confirmed implementation defect and fix

HttpController.openAIStreamClient constructed a new OkHttpClient for every streaming request. Each client had an independent connection pool, preventing reuse between turns and tool continuations. It now returns lazily shared clients, with an HTTP/1.1 variant derived from the shared client. Credentials remain on individual Requests; no Provider or session state was moved into the transport.

HttpControllerStreamConnectionReuseTest exercises two requests against a local keep-alive server using the actual client factory. Both default and HTTP/1.1 tests failed before the fix (second request sequence number 0) and passed afterwards (sequence number 1). This proves reuse, not a ten-second end-to-end speedup.

No ACP lifecycle, retry policy, cancellation, tool catalog, or memory APIs were changed. Real AgentOrchestrator requests already include thinking.type=disabled when reasoning is explicitly disabled; a hypothetical generic-flag-only test was discarded because it did not represent this caller.

## Verification and rerun

Six focused JVM tests passed (stream reuse, completion timeout policy, and custom headers). The develop standard debug APK built successfully and was installed on b49f281b.

The debug-only receiver supports operation `measure_latency` and an optional variant: `default`, `enable_thinking_false`, `thinking_disabled`, `thinking_disabled_with_tools`, `enable_thinking_false_with_tools`, or `default_warm`. It uses the existing dispatch binding and stores only timing/status/size metadata in `files/debug-provider-latency.json`. It sends a synthetic “Reply with exactly OK.” prompt and does not execute returned tool calls. It targets the configured OpenAI-compatible chat-completions endpoint; it is not a Harness end-to-end test.

```sh
adb -s b49f281b shell am broadcast -n cn.com.omnimind.bot/.debug.DebugModelProviderConfigReceiver -a cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER --es operation measure_latency --es variant thinking_disabled_with_tools
adb -s b49f281b shell run-as cn.com.omnimind.bot cat files/debug-provider-latency.json
```

The connection-pool fix is verified. Consistently low user-visible latency across every Harness remains unproven; the measured remote response delay must not be described as fixed.


## ACP lifecycle audit and consecutive-turn verification

The requested lifecycle was traced from Conversation through ACP Session, Turn and Item:

- Flutter `AgentRuntimeService.ensureSession` returns an existing session id without another native call. Each send calls canonical `session/prompt` with a stable request id.
- `AgentRuntimeManager.ensureLocalAcpConnected` selects the runtime by conversation/session owner and connects only if it is not connected. Provider catalog refresh is reserved for explicit `session/load` with `refreshConfig`.
- `LocalAcpRuntime.ensureSessionForTurn` reuses the in-memory session. The host ownership store reserves one turn per session and deduplicates request ids.
- `session.prompt` updates pass through the shared projection; the official PromptResponse/error/cancellation ends the prompt. `finishTurn` releases the active reservation and foreground lease without closing the ACP session.
- Normal send persistence uses the coordinator's ordered queue with summary generation disabled. The queue exists to preserve committed history; removing the awaited commit would risk message loss rather than establish a latency fix.

Two synthetic messages were sent through the real Flutter composer on emulator-5556, using its existing Claude conversation. No configuration was changed. Both completed in the same process (6169) and same session suffix (36a4a80e):

| Turn suffix | Reserved -> prompt sent | Reserved -> first update | First update -> event delivered | Reserved -> terminal |
| --- | ---: | ---: | ---: | ---: |
| f1ec926e | 30 ms | 46,169 ms | 2 ms | 46,651 ms |
| 87dbf1b2 | 4 ms | 10,476 ms | 2 ms | 10,620 ms |

There was one reservation and one prompt_sent per tested turn, and no intervening process initialization/session creation. This is real evidence against per-message ACP restart or previous-turn cleanup blocking the following prompt. It does not inspect the external Harness's own HTTP traffic, so its remaining wait is attributable only to the interval between prompt submission and its first update. The independent phone Provider probe above further demonstrates a long response delay without ACP/MCP execution.

Flutter runtime/service/conversation-switch regression: 156 tests passed. The broader native protocol suite had 69/70 pass: the failing catalog assertion expected `codex-acp-1.10.0` while current configuration uses `codex-acp-1.10.0-message-completion-1`; that existing metadata mismatch was not changed as a latency fix. HttpAgentLlmClientTest passed all 30 tests, including no replay after visible output.

Added AcpTurnLifecycleRegressionTest exercises the actual host ownership classes: completion/error/cancellation release the next message, duplicate request suppression, late completion isolation, transport-scope separation, and cancellation before versus after official prompt admission. No second lifecycle, retry path, timeout-based turn release, or presentation protocol was introduced.

Final focused native run: HttpAgentLlmClientTest 30 tests / 0 failures, AcpTurnLifecycleRegressionTest 4 tests / 0 failures. Build successful.


## Official implementation comparison

Compared against the official [ACP v1 prompt-turn contract](https://agentclientprotocol.com/protocol/v1/prompt-turn), [OkHttp 4.12 client implementation](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/OkHttpClient.kt), and [official RealEventSource](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp-sse/src/main/kotlin/okhttp3/internal/sse/RealEventSource.kt).

ACP permits subsequent prompts in the same session after the preceding prompt response; updates do not themselves create a separate lifecycle. The current runtime uses that boundary. OkHttp recommends shared clients and newBuilder-derived variants that share pools; the repaired factory follows this design. SSE uses official EventSources rather than a replacement parser. Official RealEventSource removes the client EventListener, so DNS/TLS measurements in the debug probe must not be described as instrumentation of production EventSource internals.

Added HttpAgentSseLifecycleTest to drive real official EventSource callbacks through HttpAgentLlmClient, checking successive completion and cancellation followed by a new request, including dispatcher resource release and exact request counts. This supplements (does not replace) the consecutive real-device ACP tests.

No remote gateway source or operational logs are present in the provided project. The remaining response-before-headers delay cannot be fully fixed or attributed to a specific upstream component from this repository alone. Requested the gateway repository/log location from the user; do not represent the end-to-end latency issue as resolved.

Official-comparison final run: HttpAgentLlmClientTest 30 tests / 0 failures, HttpAgentSseLifecycleTest 2 tests / 0 failures, AcpTurnLifecycleRegressionTest 4 tests / 0 failures, AgentRuntimeProtocolPayloadTest 70 tests / 0 failures, HttpControllerStreamConnectionReuseTest 2 tests / 0 failures. All 108 tests passed. The catalog test now accepts a preparation revision suffix tied to the pinned bridge package version, instead of incorrectly requiring the package version to be the entire host preparation revision. This is a test-contract correction, not an upstream latency fix.
