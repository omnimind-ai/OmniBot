# OmniBot Offline AI

OmniBot now has an on-device GGUF model path backed by llama.cpp on Android.

## How it works

```text
Flutter Settings
    ↓
Local Model Channel
    ↓
LocalModelManager / LocalModelDownloader
    ↓
SHA-256 verification
    ↓
Persistent app storage
    ↓
LocalModelProvider
    ↓
LocalInferenceEngine
    ↓
llama.cpp JNI
    ↓
GGUF model
```

Models are downloaded on demand. No model weights are bundled in the APK or Git repository.

## Modes

### Automatic

Use the configured remote provider when available and fall back to the selected installed local model.

### Online

Preserves the existing remote-provider behavior.

### Offline

The inference route is local-only. If the selected local model is unavailable, OmniBot reports:

> Offline model is not installed or cannot be loaded.

It does not silently switch to a remote provider.

## Model storage

Permanent model files live under Android app-specific storage:

```text
files/models/<model-id>/
  model.gguf
```

Temporary downloads use:

```text
model.gguf.part
```

A `.part` file is atomically renamed only after the expected size and SHA-256 checksum pass.

## Catalog

The initial catalog contains Qwen2.5 1.5B Instruct Q4_K_M from Qwen's official GGUF release. It is distributed under Apache-2.0 and has a pinned SHA-256 checksum in `LocalModelCatalog.kt`.

The catalog deliberately contains only verified entries. Arbitrary model URLs are not accepted by the downloader.

## Hardware

The current runtime is CPU-first. A model is rejected before loading when the device does not have the minimum available RAM advertised by the catalog.

The current starter model is intended for devices with roughly 4 GB or more RAM. Larger models should be added only after multi-file/sharded model support and device-specific memory guards are implemented.

## Security

- HTTPS-only model sources.
- Catalog allow-listing.
- SHA-256 verification.
- Path-safe model IDs.
- Streaming downloads; model files are not buffered into JVM memory.
- `.part` files for interrupted downloads.
- No arbitrary native-library/model execution.
- No model auto-download during build or startup.

## Limitations

- Local vision is not enabled.
- Local function/tool calling is not advertised until a model/runtime path supports it end-to-end.
- CPU inference is enabled; experimental Android GPU acceleration is intentionally not enabled yet.
- The current Flutter bridge exposes generation for integration/testing, while deeper agent-loop wiring should use `LocalInferenceAdapter` and the existing provider abstraction rather than a separate chat system.
