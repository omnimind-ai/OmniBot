# Jackery CES hardware interface and reset

The CES audience edition reads **the connected CES device**, never the phone battery. The hardware protocol and approved CES product render were not included in the specification. Configure the real interface and supply the approved image before hardware acceptance. Missing interfaces and missing artwork remain explicit empty states.

## Staff provisioning

Use the Android app's private `files/jackery-ces/connection.json`:

```json
{
  "modelApiBase": "http://DEVICE_ADDRESS:MODEL_PORT/v1",
  "modelId": "EXACT_DEVICE_MODEL_ID",
  "statusUrl": "http://DEVICE_ADDRESS:STATUS_PORT/status"
}
```

The example addresses are placeholders. Supply the device's actual OpenAI-compatible endpoint and model ID. `statusUrl` must provide the contract below; if the vendor's wire format differs, its gateway or this one telemetry adapter must normalize it. Gradle `JACKERY_MODEL_API_BASE`, `JACKERY_MODEL_ID` and `JACKERY_STATUS_URL` provide optional deployment defaults. No endpoint or model is invented when omitted. Credentials belong in the existing encrypted Provider store; an optional `apiKey` in staff provisioning is imported and removed from the JSON file by `JackeryCesProvisioning`.

Supply the approved device PNG at private `files/jackery-ces/device.png`. Home/detail show “Device image unavailable” until it exists. The chat background omits missing artwork and remains a faint, non-interactive slogan. This is separate from the approved launcher icon.

## Telemetry contract

`GET statusUrl` returns HTTP 200 and a JSON object:

```json
{
  "schemaVersion": 1,
  "observedAtEpochMs": 1800000000000,
  "connected": true,
  "batteryPercent": 73,
  "power": "battery",
  "connection": "wifi",
  "aiModelStatus": "ready"
}
```

These values illustrate the schema, not a demo fixture used by the application. `observedAtEpochMs` is the UTC epoch time when hardware sampled the status. Synchronize gateway/device time. Samples older than 15 seconds, more than 5 seconds in the future, or lacking a timestamp are unavailable. `connected` must be a Boolean. Disconnected responses discard all other values immediately.

| Field | Accepted values | Missing/invalid display |
| --- | --- | --- |
| `batteryPercent` | Integer 0–100 | Battery unavailable |
| `power` | battery, external, charging, off | Power unavailable |
| `connection` | wifi, usb, ethernet, bluetooth | Connection unavailable in details; compact Connected only when connection Boolean is proven |
| `aiModelStatus` | ready, loading, unavailable, offline | AI unavailable |

The polling owner sends one request every 5 seconds while any device surface is mounted and the app is resumed. It checks stale cached samples every second and clears them. Polling stops on background/detach, and an older in-flight response cannot repopulate resumed/detached UI. Each request has 3-second connection/read deadlines, no redirect following, no cache, and a 64-KiB body cap. Invalid configuration, HTTP errors and parsing errors project unavailable states.

## Reset demo lifecycle

Reset is exclusive in the shared `AgentRuntimeManager`: stop new ACP request admission; request official `session/cancel` through the existing owners; await the actual prompt responses; disconnect the existing transports; drain native session/history projection callbacks; then call Flutter's coordinator to fence and drain all pending persistence. An unresponsive Agent fails reset before destructive writes. Reset does not invent a turn-completion event or replay a prompt.

After that barrier, reset cancels app-owned scheduled-task alarms and reminders, stops active trajectory capture, disables/cancels memory rollup, clears short/long memory and its index, atomically removes all conversation/message/item/session-binding/usage records, and erases workspace user/runtime files and internal run logs. Queued scheduled alarms share a monitor with cleanup and see the emptied registry. Memory rollup shares the memory-write lock, so an already running rollup finishes before memory deletion.

Workspace deletion does not follow symbolic links into public or external storage. Installed skills, shipped infrastructure/pets, authentication, hardware connection JSON, encrypted device Provider credential and approved artwork survive. The provisioning owner restores the fixed device model, identity prompt, English, light theme and audience chat mode. The Flutter coordinator then discards every conversation runtime so later page disposal cannot resurrect history. Any failed stage returns an error; the UI confirms success only after native completion.

System-clock alarms created in a separate clock app are outside this app's owned reminder registry and cannot be deleted reliably through Android's public APIs. Exhibition staff should clear those separately if they were created during earlier testing. Audience CES chat exposes web search only and cannot create new clock alarms or schedules.

## Acceptance remaining on hardware

History mutations reserve their existing maintenance token at the native channel entry before the coroutine is queued. Reset immediately rejects new conversation/current-id writes; the coordinator's admitted update/message/card/completion persistence can drain after ACP cancellation. Its callback then closes the history-write fence and waits for every reserved write before deletion. Queued initial creation cannot run after the database has been cleared.

Reset validates staff provisioning before deleting content. A later partial failure reports `contentChanged: true`: Flutter discards the invalidated runtimes, reloads preferences and refreshes the visible chat, while still returning an error to the Reset demo action. Old page disposal cannot restore deleted visitor content, and the UI never announces success after that failure. Reset errors use fixed text without exposing configuration or credentials.

Test the actual telemetry endpoint and final image, device model requests, web search, foreground/background transitions, disconnect/reconnect and Reset demo on the provisioned CES phone. The automated source/unit/widget checks do not establish device connectivity or artwork approval.
