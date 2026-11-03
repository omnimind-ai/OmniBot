# Na in the shared Agent mode

Na is a built-in Agent profile (`na-cloud`) in the native catalog. Select **Na** from the existing chat mode menu. It uses the existing `ChatPage`, composer, message/tool cards, conversation drawer, cancellation, and `ChatConversationRuntimeCoordinator` / `AgentEventReducer`. There is no `/home/na` chat route or independent Na chat lifecycle. The resource management page at `/home/na/personal` uses the same captured Na connection and does not own a second session or turn.

Sign in to the App account and select Na. Managed mode uses the current account credentials to claim the account's authorized workspace and refresh access tokens automatically. `OMNIBOT_CLOUD_URL` defaults to `https://na.omnimind.com.cn`; users do not enter a container address or token. The separate `cloud.omnimind.com.cn` New API/image service keeps its existing address. The App never receives the model-provider API key. Na's model, reasoning, tools and execution permissions remain server-owned; device Provider/model selectors and permission controls are hidden for Na.

In every App mode, **Settings → Agent mode → Na → Configure** opens the Na resource page at `/home/na/personal` directly. Its visible page title is **Na**. When the native Na catalog reports `configurationAvailable: true` in a develop-flavor build, tapping that title opens the existing connection editor at `/home/na/connection`, where development mode, address and instance token are configured. Production builds show the same title as plain text and do not expose the development editor. The standalone development default is `http://127.0.0.1:8787`, suitable for a physical test device with `adb reverse tcp:8787 tcp:8787`. The development URL is saved locally, while its manually entered token stays only in the App process. Managed mode instead uses encrypted account credentials, including after restart. Account and endpoint ownership checks isolate Na history across logout, account switches and service changes. See [account integration](account-client.zh-CN.md) for the managed connection contract.

## Personal assistant resources

Select Na in the existing chat, then use the header menu **Personal assistant / 个人助理**, or open **Settings → Agent mode → Na → Configure**. Both go directly to the page titled **Na** at `/home/na/personal`; the connection editor is no longer an intermediate resource-page entry. The page offers five native views:

- **Inbox:** durable notifications, shared read state, exact external-operation review, reject/approve and returned results. The review dialog displays the actual operation, account ID, pinned version when present and complete arguments before approval. An uncertain result is shown as needing verification; it is never retried automatically.
- **Connected apps:** unified Composio service settings, toolkit/account inventory, hosted Google and other app authorization, explicit refresh and account-specific disconnection. Authorization opens the system browser using only the validated official HTTPS destination. Inactive or expired accounts remain visible. If upstream revocation is unconfirmed, the service warning is shown.
- **Memory:** list, create, edit and forget explicitly saved memories; Chinese/English search uses the service's bounded recall. Truncated search excerpts cannot overwrite the full stored memory. Clear the search to edit the complete record.
- **Responsibilities:** activate prerequisite-aware scenarios, create/edit/pause/delete routines or run one check. An unchanged recurring interval is omitted from edits so a civil-time scenario schedule is preserved, including when native JSON numbers arrive as `1440.0`.
- **Profile:** user/agent identity and preferences, timezone, goals, proactivity, quiet hours and minimum reminder interval. These settings remain service-owned and are shared with the Web client.

The server's `/api/status` capabilities control availability. This page does not configure the model Provider, grant itself app access or create a separate scheduler. Tool discovery/execution and bounded per-turn memory selection remain in the Na service; the App's existing tool cards continue to display their run activity.

Development connection access is decided by the native/local Agent catalog, independently of resource loading. Even when Na is offline, the token is missing or the resource request fails, an eligible develop build can still tap the **Na** title to correct its address/token. The editor returns to the resource page and reloads it under the updated connection owner. The old `/home/agent_config/na-cloud` route redirects to `/home/na/personal`; `/home/na/connection` is the dedicated editor route. There is no configuration-to-resource navigation cycle or duplicate Cloud connection shortcut in the chat menu.

`AgentRuntimeService.requestNaPersonal` calls the existing native channel method `config/na/personal` with `agentId: "na-cloud"`, an allowlisted `operation`, optional resource `id`, structured `payload` and an opaque connection `owner`. The result is `{owner, data}`. Only initial `status` can acquire an owner without supplying one; all later reads and mutations retain that owner. Native code maps operation names to fixed routes and methods, including `PUT /api/memories/:id`; callers cannot supply arbitrary URLs, HTTP verbs or private instance routes.

The owner includes endpoint/account attribution and configuration revision. Updating a development token on the same endpoint, switching service or changing account invalidates old resource windows. Native validates it before dispatch and after response; Flutter also binds the rendered snapshot and open review/edit dialogs to that owner. A stale read clears old content and disables actions until explicit reload. Approval, disconnect and all other mutations are sent once, without transport replay. API keys and connection tokens do not enter the resource response.

## Local development connection

Keep `NA_PORT=8787` in the sibling `codex/na/.env` consistent with the development URL saved in the App. `npm --prefix na start` loads that file; a separately supervised process must receive the same intended environment. Build/select a personal-agent-enabled source runtime through `NA_CODEX_BIN` when required; a globally installed Codex without `--personal-agent` is insufficient.

For the USB-connected physical device:

```sh
adb reverse tcp:8787 tcp:8787
```

In the develop App, open **Settings → Agent mode → Na → Configure**, tap the **Na** page title, then enable **Use a development server / 使用独立测试服务** in the connection editor. Enter `http://127.0.0.1:8787` and the instance access token, then save. This token is kept only in the App process: installing/restarting the process may require entering it again. The saved URL is not proof of authorization, and no model-provider key belongs on the phone. Do not put the instance token in a URL, screenshot, commit or documentation.

Manage Composio in **Na → Connected apps / 连接应用 → Connection service settings / 连接服务设置**. Enable the service, enter a Platform project API key and choose allowed toolkit slugs; auth-config IDs are optional for managed OAuth. Saving writes to the Na service, never local App preferences. The key is write-only: leaving it blank retains the existing server key, and clearing it requires an explicit choice. `expectedRevision` prevents an old form from overwriting newer settings. The native Na owner still binds every request. Provider inventory failure leaves the settings editor available for repair.

Composio settings persist privately on the server and override initial `NA_COMPOSIO_*` environment defaults. Current 8787 development service is enabled for Gmail, Calendar, Docs, Drive, Sheets, Slides and Tasks; real inventory and restart persistence were verified. Actual Google account consent must still be completed in the App. The custom Google OAuth implementation and its native operations are removed; legacy pending actions remain visible but cannot be approved. Every prepared Composio application operation continues to require user review.

## Runtime boundary

`NaCloudRuntime.kt` adapts Na HTTP to the App's existing internal session API; Na does not run an ACP server or use ACP on the network. A namespaced cloud conversation UUID is bound through `AgentSessionBindingRepository` to the existing local Conversation. The App prompt reservation is Na's `clientMessageId`; the submitted user item is retained separately as `clientUserMessageId`, including when a retry retains an older user id. Model/permission parameters from the device are never forwarded to Na.

The native adapter reads durable run snapshots every 600 ms, emits normalized session updates to the shared reducer, and resolves the existing prompt response when Na reports completed/cancelled/failed. One HTTP transport boundary retries failed GET reads at most three times; POST/PUT writes are never automatically replayed. Idle cancellation is harmless, active cancellation calls the owning Na run, and changing the service address is blocked while an App prompt is active. Instance namespacing prevents an old conversation from being sent to a different service.

Session loading returns cloud history using the same prompt/item identities as live updates. The existing snapshot mapper and coordinator merge it with committed App history, preserving live turns and user queries. Listing cloud sessions creates the ordinary local conversation bindings; opening them hydrates the shared chat.

### Same-account history on multiple phones

While the App is in the foreground, `NaHistorySyncService` enables one native
history observer through `config/na/historySync`. It reads only an existing
workspace, captures the conversation list's `eventCursor`, and follows the
existing `/api/events` stream. Chat/file changes coalesce into ordinary
`onConversationListChanged` invalidations with the account/endpoint owner prefix.
The drawer reads synchronized title/count/preview/activity metadata; the visible
Na chat reloads the existing ACP session snapshot. This observer never submits a
prompt, claims a missing workspace, or creates a second Agent lifecycle.

The shared coordinator merges cloud items by identity, preserving the locally
admitted turn while allowing another phone's running turn to update. Those
external turns do not acquire local prompt ownership. Initial/latest snapshots
and historical pages are merged without discarding older local history. A new
phone first displays the newest page, then imports older 100-run pages through
the server's stable `nextRunCursor`; live invalidations refresh the newest page.
Backgrounding stops the subscription; reconnect, foregrounding and owner changes
restart from snapshots, including recovery after compacted event history.

An installation UUID in Android's `noBackupFilesDir` namespaces server
`clientMessageId` values; retries of one submission retain that identity. The
server preserves `clientOriginId` with runs/archives. Only the originating phone
may reuse `clientUserMessageId` as a local message id. Other phones use canonical
run/item identities. Legacy local user aliases are migrated only when their
explicit mapping is unique; message text is never used as an identity.

The Na server update adds atomic list/detail cursors, bounded previews and older
run pagination. Older servers still support snapshot recovery/observation, but
full older-history pagination requires the matching server update. Managed
account routing continues to select the account's private Na instance; local
conversation database ids and installation ids are not authorization credentials.

Na files use the existing remote workspace browser and file preview/save/share components. The native HTTP adapter maps `/workspace` to Na's files API. Text editing uses the existing editor and `PUT /api/files/content`. Regular files support rename and delete through `POST /api/files/rename` and `DELETE /api/files/content`; directory mutations remain hidden. The server rejects mutations while a task is active, private paths, links, traversal and overwrites. Download and upload limits remain 20 MiB and 10 MiB.

App chat attachments require the server's `attachments` capability. Each file is limited to 10 MiB (10,485,760 bytes), including providers with unknown or inaccurate size metadata, with at most 20 files per message. The existing prompt reservation stages selected sources in private cache and uploads them sequentially to `PUT /api/files/uploads/<UUID>` with conversation/message ownership. One message admission supplies `attachmentIds`; the server publishes those files and constructs model references in the same run transaction. Android paths and document URIs stay on the phone. Temporary copies are removed on success, failure or cancellation; account generation checks fence uploads and message admission after logout or account switching. PUT and message POST are never automatically replayed.

On failure or cancellation the App requests `DELETE /api/files/uploads` for the owning reservation. The server removes only uncommitted files; a lost message response never deletes accepted attachments. If offline cleanup cannot reach the server, pending uploads expire after one hour through the server's maintenance job. Staging counts toward the user's disk quota and stays hidden from file browsing and agent execution. A manual retry creates a new prompt reservation.

Cloud history returns original user text and attachment metadata. A device without local metadata restores file cards, authenticated previews and image thumbnails through the existing file reader. Preview requests include the namespaced session so an old account's card cannot read the current account's files. Rename updates the history path/name; deletion leaves a disabled `Deleted` card. Legacy explicit attachment references are also imported by the server.

Workspace listings also carry an opaque account/endpoint owner. Browsing, preview, save, rename and delete retain that owner, including while a confirmation dialog is open. The native file boundary rejects stale owners before making a file request; switching accounts cannot redirect an old file action into the new account's workspace.

After a mid-run connection failure, loading history reads the same durable run without resubmitting it. An explicit terminal Na snapshot replaces the stale transport-failure presentation through the existing snapshot mapper and coordinator. Incomplete snapshots cannot erase that failure or revive an App prompt.

## Checks

Use the configured Flutter SDK `/Users/ocean/.local/share/flutter/3.47.2/bin/flutter`. The final focused suite includes Agent configuration, menu selection, shared reducer/coordinator, Harness switching, remote workspace and runtime-service tests. Native `NaItemProjectionTest` covers repeated snapshots, whitespace, tool completion, reasoning identity, inconsistent visible text and restored prompt/item ids.

Build the authorized developer APK with:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
PATH=/Users/ocean/.local/share/flutter/3.47.2/bin:$PATH \
./gradlew --no-daemon :app:assembleDevelopStandardDebug \
  -Ptarget=lib/main_standard.dart -Ptarget-platform=android-arm64
```

Artifact: `app/build/outputs/apk/developStandard/debug/app-develop-standard-debug.apk`.

Earlier independent Na-page acceptance is historical and does not establish acceptance of this shared integration. Current build/test/device evidence is recorded in the sibling [Na App integration](../../codex/na/docs/app-integration.md). No repository commit or production rollout is implied.

## Delivery evidence (2026-11-03)

The final focused checks passed **95 Flutter tests**, **34 native tests** and **204 Na service tests**, including the source-built Codex harness test. The developer APK was rebuilt and installed on the physical **PJD110**. ADB validation exercised the native personal-resource bridge against an isolated fixture: approval reached completed state and returned result `42`; Chinese memory search and memory editing were checked against the server, including the actual PUT request. A status-bar overlap found in the first physical pass was fixed by using the existing primary AppBar safe-area behavior, followed by rebuild/install; the top-inset regression fails before that fix and passes afterward.

The final native screenshots are stored under [build/verification/na-personal-20261103](../build/verification/na-personal-20261103/), including [the inbox](../build/verification/na-personal-20261103/03-inbox-final.png) and [connections](../build/verification/na-personal-20261103/04-connections-final.png). They are ADB physical-device captures, not widget previews. Fixture accounts/actions remain isolated test data. The real 8787 service's model reply and final connection state are recorded separately by the owning acceptance run; this document does not infer that outcome from the fixture.

See the cross-repository [2026-11-03 acceptance report](../../codex/na/docs/verification/app-sync-20261103/ACCEPTANCE.md) for final build/device evidence and limitations. No production rollout, real Composio authorization or repository commit is implied.

The navigation follow-up makes **Agents → Na → Configure** open the personal assistant directly in every mode; eligible develop builds enter the connection editor by tapping the **Na** title. Its **52 focused Flutter regressions** passed, including an interleaved stale-response negative control. The final APK was rebuilt, installed and SHA-256 matched on PJD110; address/token save and return-to-personal resource reload passed. See [the navigation acceptance report](../../codex/na/docs/verification/na-navigation-20261103/ACCEPTANCE.md) and [physical screenshots](../build/verification/na-navigation-20261103/).

The unified connector follow-up passed **199 Na tests**, **59 focused Flutter tests** and **13 NaPersonalApi tests**. The refresh action now uses the shared liquid-glass `OmniCircleButton`; resource chips omit the leading checkmark allocation and keep selected text centered. Real Platform configuration, managed Gmail link generation, private `0600` persistence and post-restart inventory were verified. ADB physical-device validation also saved service off/on from the App and independently read both values back from the real server, retaining its key; the final state is enabled. See [the Composio acceptance report](../../codex/na/docs/verification/composio-settings-20261103/ACCEPTANCE.md) and [physical screenshots](../build/verification/na-composio-20261103/).

## Historical delivery evidence (2026-10-09)

The shared integration passed 433 focused Flutter tests, then all four workspace tests including the added owner-switch regression; seven native adapter tests and 52 Na service tests passed. The APK builds successfully. Static analysis reports no errors; existing warnings and style infos remain. PJD110 fixture acceptance verified the original composer, tool cards, cancellation, unique history after reopening, workspace listing, Markdown preview and server-confirmed edited file content. This fixture uses public test credentials and a deterministic runner; the user subsequently authorized account-token entry, and real-model device acceptance also passed (see below).

Final APK SHA-256: `dfcd71b2acc34f8c78b1a7a21227fa3c83af73f3f726f635f5b46f37ab8483f9`. The device configuration is restored to `http://127.0.0.1:8787`; no fixture token is retained. Na remains managed by `dev.omnimind.na.local`. Detailed device evidence lives in the sibling Na documentation.

## Authorized real-model device acceptance (2026-10-09)

PJD110 used the same delivered APK and existing chat/configuration/workspace pages against the live Na service. DeepSeek `deepseek-flash` at `https://api.deepseek.com` completed three runs; the original Stop button cancelled one real running shell tool. The same conversation continued successfully after cancellation. Actual command execution exited with code 0 and generated `na-app-live-20261009-2237.txt`; its contents were verified at the server and in the existing App file preview. Latest replies survived reopening the original history once each. No runs remain active. The account token stays in this App process; no provider key was transferred to the phone. Evidence: sibling `codex/na/test-results/shared-app-live.json`.
