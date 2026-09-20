# Downloadable official OmniInfer runtime (experimental)

This standalone Android packaging project depends on **unmodified**
`io.github.omnimind-ai:omniinfer:0.2.4` from Maven Central. It produces an APK-shaped
DEX/resource/native-library container, not a separately installed inference app.
The main App does not depend on the AAR and does not embed the container or model.

From repository root, with an Android SDK configured through `ANDROID_HOME` or
this project's ignored `local.properties`:

```sh
./gradlew -p tools/omniinfer-runtime --no-daemon assembleRelease
```

Use the root Gradle 9.5 wrapper. The packaging project pins AGP 9.3.2 and the
SDK dependency. Target: ARM64 Android 11+. Current tested payload is 28,734,817
bytes, SHA-256 `b317a12bda248f9197024fd90d7067e8d6038394c7a6fde81dee2a17a02f9006`.

The main App's `DownloadableInferenceRuntime` verifies the fixed hash, extracts
native libraries into its private version directory and loads DEX using a boot-parent
`DexClassLoader`. Dependencies remain isolated from the App's Kotlin/Ktor versions.
A separate configuration Context with Android's public `ResourcesLoader` provides
upstream assets. `InferenceComponentFactory` resolves only the upstream Service;
Android still attaches and owns the Service. The upstream SDK owns model loading,
HTTP, generation, streaming and cancellation. Provider/ACP logic is unchanged.

Cold App startup does not load a model. Use Settings → Model Providers →
Use local model service → 下载并启动. The page downloads the fixed component and
Qwen3 0.6B Q8_0 independently; hash-correct files are reused. Stop via 停止服务.
Provider: `http://127.0.0.1:9099/v1`, Chat Completions, blank key; discover the model
ID from `/v1/models`. A listener already on port 9099 is an error unless this SDK
instance owns the ready model.

AAR is a build artifact, not an Android package that can directly be installed.
This loader is an experimental direct-APK distribution path, not Play Feature
Delivery. Component updates need a new version path and pinned hash in the App;
this version does not hot-swap loaded native libraries. UI download failures are
surfaced; there is no cloud fallback or automatic source build.

Published component:
https://github.com/omnimind-ai/OmniBot/releases/tag/omniinfer-runtime-v0.2.4-test.1

Upstream: https://github.com/omnimind-ai/OmniInfer
Official integration: https://github.com/omnimind-ai/OmniInfer/blob/main/docs/android/aar-integration.md
Android APIs: https://developer.android.com/reference/android/app/AppComponentFactory
and https://developer.android.com/reference/android/content/res/loader/ResourcesLoader

See `docs/testing/omniinfer-downloadable-runtime-2026-09-18.md` for actual acceptance
results. Emulator verification is not physical-device acceptance.
