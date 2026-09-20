# Remote local inference host — development artifact

Built on Android ARM64 from OmniInfer 6ad984d414d4b7c27ab5740e63e8ccb1b33e4046 and llama.cpp 30b6a755e29692e8bc8e072885325716a2fee70f, using ../phone-host. APK is hosted on the separate GitHub prerelease omniinfer-local-v0.1.0-test.1 in omnimind-ai/OmniBot; no APK is bundled in the main app. Debug signed, version 0.1.0 (1), package cn.com.omnimind.localinfer. Not an official upstream APK or production release. No model weights included.

SHA-256: c8152e7094b36de5b6ad32765c97b4e408c27a45a34917163593db571a93fcb2

Reproduce with `python3 scripts/phone.py build` on Android Ubuntu ARM64. Keep the signing identity stable for updates; never uninstall an existing host to bypass a signature mismatch (that deletes its model).
