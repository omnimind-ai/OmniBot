#!/usr/bin/env python3
"""Fail a release audit if a sensitive-content client bypasses the final transport gate."""

from __future__ import annotations

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]

TARGETS = {
    "image": ROOT / "app/src/main/java/cn/com/omnimind/bot/agent/tool/handlers/ImageGenerationToolHandler.kt",
    "speech": ROOT / "app/src/main/java/cn/com/omnimind/bot/voice/SpeechTranscriptionClient.kt",
    "voice": ROOT / "app/src/main/java/cn/com/omnimind/bot/voice/SceneVoicePlaybackManager.kt",
    "memory": ROOT / "app/src/main/java/cn/com/omnimind/bot/agent/workspace/memory/WorkspaceMemoryService.kt",
    "remote_codex": ROOT / "app/src/main/java/cn/com/omnimind/bot/agent/runtime/RemoteCodexBridgeConnection.kt",
    "http_controller": ROOT / "assists/src/main/java/cn/com/omnimind/assists/controller/http/HttpController.kt",
    "openclaw": ROOT / "assists/src/main/java/cn/com/omnimind/assists/task/ChatTask.kt",
}

BYPASS_MARKERS = (
    ".newCall(",
    ".newWebSocket(",
    "EventSources.createFactory",
    "OkHttpManager.enqueue(",
    "OkHttpManager.enqueueWithStream(",
)


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def main() -> int:
    errors: list[str] = []

    endpoint_policy = (
        ROOT
        / "baselib/src/main/java/cn/com/omnimind/baselib/util/ContentEndpointSecurity.kt"
    ).read_text(encoding="utf-8")
    require(
        "require(normalized.isNotEmpty())" in endpoint_policy,
        "ContentEndpointSecurity must reject an empty URL",
        errors,
    )
    require(
        "require(uri.isAbsolute)" in endpoint_policy,
        "ContentEndpointSecurity must reject a relative URL",
        errors,
    )
    require(
        'normalized == "::1"' in endpoint_policy and "parsed.first() == 127" in endpoint_policy,
        "debug plaintext must be limited to literal IP loopback",
        errors,
    )

    manager = (
        ROOT / "baselib/src/main/java/cn/com/omnimind/baselib/http/OkHttpManager.kt"
    ).read_text(encoding="utf-8")
    for marker in (
        ".followRedirects(false)",
        ".followSslRedirects(false)",
        "fun sensitiveContentCall(",
        "fun sensitiveContentEventSource(",
        "fun sensitiveContentWebSocket(",
        ".addNetworkInterceptor",
    ):
        require(marker in manager, f"missing sensitive client control: {marker}", errors)

    expected_gate = {
        "image": "sensitiveContentCall(",
        "speech": "sensitiveContentCall(",
        "voice": "sensitiveContentEventSource(",
        "memory": "sensitiveContentCall(",
        "remote_codex": "sensitiveContentWebSocket(",
        "http_controller": "sensitiveContentEventSource(",
        "openclaw": "sensitiveContentWebSocket(",
    }
    for name, path in TARGETS.items():
        source = path.read_text(encoding="utf-8")
        require(expected_gate[name] in source, f"{name} has no final transport gate", errors)
        for marker in BYPASS_MARKERS:
            require(marker not in source, f"{name} bypasses the gate with {marker}", errors)
        for marker in ('"URL:', "content_preview=", "url=${"):
            require(marker not in source, f"{name} logs sensitive destination/content", errors)

    network_config = (ROOT / "app/src/main/res/xml/network_security_config.xml").read_text(
        encoding="utf-8"
    )
    require(
        "This flag is not authorization for AI or other sensitive-content traffic" in network_config,
        "network security config must document the code-level sensitive-content gate",
        errors,
    )

    if errors:
        for error in errors:
            print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"PASS: sensitive-content transport gate covers {len(TARGETS)} runtime clients")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
