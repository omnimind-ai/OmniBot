# Actual chat attachment acceptance — 2026-09-17

Device: emulator-5580, sdk_gphone64_arm64; app 0.6.3 (16).
Installed APK SHA-256: faffd8e2cb83d8647942a384f5a713ae3afefab217fc1270c00219fd119bbf9a.
Configured chat model observed through Model & settings: GLM-4.6V.
Physical-device acceptance: **待真机验证**.

## Actual image: passed

Selected `chat-upload-colors-second.png` through the Android system picker;
sent through the chat composer, without supplying expected colors in the prompt.
The real model replied `green, yellow` for marker
`OOB_LIVE_CHAT_IMAGE_SECOND_1789617158084`.
The read-only history assertion verified one admitted user message and canonical
completion. Force-stop/relaunch retained the reply and image history; model
selection remained GLM-4.6V.

Initial driver observation could not match a multiword result because its helper
compared only the last word. Corrected helper and ran four passing Node tests.
Stopped the old observation process and verified the existing completed turn
without sending it again. Original send evidence: `artifacts/chat-actual-upload-20260917/image/1-send.jsonl`.
Corrected observations: `artifacts/chat-actual-upload-20260917/image-observed/result.json`
(seven successful checks and screenshots).

## Text attachment: not yet accepted

Selected synthetic `chat-upload-record.txt` through Android picker. During focus
observation, UIAutomator exited 137. A subsequent attempt refused to overwrite an
existing draft: another test had entered an unrelated `OOB_LIVE_PERF_READS` prompt.
No file-read/model success is claimed. Both failed executions are retained in
`artifacts/chat-actual-upload-20260917/file*/result.json`.

Executable regression:

```sh
python3 scripts/prepare-chat-image.py SERIAL chat-upload-record.txt
node scripts/verify-agent-user-journey.mjs SERIAL scripts/fixtures/agent-user-journeys/chat-upload-record.en.json EVIDENCE_DIRECTORY
```

Requires an exclusively available idle chat and real configured provider.
Fixture contents are synthetic. The prompt does not reveal the expected values.
The journey requires a file read, canonical completion, process restart, and a
second read of a previously unrequested field. This journey remains pending;
UI success alone must not substitute for a successful file_read tool result.

Model compatibility: official GLM-5.1 documentation declares text input and tool
calling. GLM-4.6V and GLM-5V-Turbo declare visual input. The current file_read tool
returns text for text files, image data for images, and contentAvailable=false
for binary documents requiring a parser. Upload availability is not proof of
native multimodal capability or an automatic OCR/parser fallback.
