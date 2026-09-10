# MCP lifecycle device regression fixture

This is an isolated loopback test service, not a model provider. Keep the App's configured local model API unchanged. The fixture exposes only waiting, echo, and HTTP 401 test tools; no filesystem or shell access.

1. Start `node scripts/fixtures/mcp-lifecycle-server.mjs 29091 /tmp/oob-mcp-events.jsonl` and retain its process handle.
2. For the isolated device, run `adb -s SERIAL reverse tcp:29091 tcp:29091`.
3. In Settings → MCP Tools add **OOB_MCP_Lifecycle**, URL `http://127.0.0.1:29091/mcp`, no token, enabled. Refresh tools and verify Connected / Tools 3. Preserve any unrelated service configurations.
4. Start a new Xiaowan conversation, using the existing configured local model.
5. Run `node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/xiaowan-mcp-stop-recovery.en.json /tmp/oob-mcp-journey-1`. Run again with a different output directory. Each run uses unique user markers and its own observation baseline.
6. Keep `result.json`, per-step canonical history assertions, screenshots, and server event log. This checks admitted wait, one matching cancellation, subsequent real echo, and restart history. The fixture observation step polls only its loopback read-only endpoint; it never issues an Agent request.
7. Remove only this test service through the App UI, remove `tcp:29091` reverse, then stop the fixture process. Do not clear App data.

The fixture exposes legacy Streamable HTTP at `/mcp` and legacy SSE at `/sse`. To exercise SSE, configure the `/sse` URL, refresh tools, and start a new conversation; run `xiaowan-mcp-stop-denial-recovery.en.json` for cancellation plus a 401 tool error and subsequent recovery. Server observations record the SSE stream ID as well as request ID. A pass on one transport is not a pass on the other. It does not constitute production-server, physical-device, or all-Agent acceptance. Emulator runs must remain labeled as such. Physical-device acceptance remains required by repository policy.
