# Claude phone diagnosis — 2026-09-06

The user requested reinstall and real-phone investigation of repeated Claude Code failures. USB serial 10AG2P03S5004ZD appeared in the first inventory, then disappeared during the first UI dump. Subsequent inventories contained only emulator-5556; mDNS discovery was empty and USB registry inspection found no vivo device. Do not claim reinstall or current-screen diagnosis succeeded.

The latest working tree APK built successfully in 53s and was copied to /tmp/oob-claude-reinstall-20260906.apk. It includes the updated model card and installation retry/user-facing error fixes.

Read-only public endpoint probes on the previously observed https://llmapi.paratera.com returned:
- POST /v1/messages, empty body, no key: 401 auth_error, No api key passed in.
- POST /v1/chat/completions, same: 401 auth_error.
- POST /oob-nonexistent-probe, same: 404 Not Found.

This establishes a recognized Messages endpoint, not current-account permission or a working selected model. The app currently rejects unknown OpenAI-configured URLs in ClaudeCodeConfigAdapter before attempting that endpoint. That guard may therefore reject a multi-protocol service even when Claude is usable. Do not resolve this by guessing a new endpoint, silently changing the user's global Provider protocol, or claiming successful inference without an authenticated test.

Pending phone connection: inspect the current failure and selected model/configuration, verify the actual Claude path with the user's existing authorized setup, then install the built APK and reproduce the user action. Preserve conversation history and credentials.
