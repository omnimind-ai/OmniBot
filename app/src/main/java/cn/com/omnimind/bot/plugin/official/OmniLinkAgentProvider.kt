package cn.com.omnimind.bot.plugin.official

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import cn.com.omnimind.baselib.llm.AssistantToolCall
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.ToolExecutionResult
import cn.com.omnimind.bot.agent.tool.handlers.ToolHandler
import cn.com.omnimind.bot.mcp.RemoteMcpCallResult
import cn.com.omnimind.bot.mcp.RemoteMcpClient
import cn.com.omnimind.bot.mcp.RemoteMcpServerConfig
import cn.com.omnimind.bot.plugin.OmniPlugin
import cn.com.omnimind.bot.plugin.OmniPluginContribution
import cn.com.omnimind.bot.plugin.OmniPluginToolGroup
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleAdapter
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundleDefinition
import cn.com.omnimind.bot.plugin.runtime.RuntimeBundlePrepareMode
import cn.com.omnimind.bot.plugin.runtime.RuntimeSkillBundleManager
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val OMNILINK_AGENT_ID = "omnibot-omnilink-agent"

/** Events delivered to the current Flutter chat without exposing MCP tokens. */
object OmniLinkAgentEventBus {
    private val listeners = CopyOnWriteArrayList<(Map<String, Any?>) -> Unit>()
    private val recentEvents = ArrayDeque<Map<String, Any?>>()
    private const val MAX_RECENT_EVENTS = 64

    fun subscribe(listener: (Map<String, Any?>) -> Unit): () -> Unit {
        listeners += listener
        val replay = synchronized(recentEvents) { recentEvents.toList() }
        replay.forEach { event ->
            runCatching { listener(event) }
        }
        return { listeners -= listener }
    }

    fun publish(event: Map<String, Any?>) {
        synchronized(recentEvents) {
            recentEvents.addLast(event)
            while (recentEvents.size > MAX_RECENT_EVENTS) recentEvents.removeFirst()
        }
        listeners.forEach { listener -> runCatching { listener(event) } }
    }
}

class OmniLinkAgentProvider(
    context: Context,
    definition: RuntimeBundleDefinition,
) : RuntimeBundleAdapter {
    private val appContext = context.applicationContext
    private val skillManager = RuntimeSkillBundleManager(appContext, definition.runtimeSkill)

    override suspend fun prepare(mode: RuntimeBundlePrepareMode) {
        skillManager.resolvePackaged(refresh = mode == RuntimeBundlePrepareMode.UPDATE)
        skillManager.setEnabled(false)
    }

    override suspend fun remove() = skillManager.reclaim()

    override fun open(): OmniPlugin = object : OmniPlugin {
        private var poller: OmniLinkAgentEventPoller? = null

        override suspend fun contribution(): OmniPluginContribution = OmniPluginContribution(
            toolGroups = listOf(
                OmniPluginToolGroup(
                    definitions = OmniLinkAgentTools.definitions(),
                    handlerFactory = { OmniLinkAgentToolHandler(appContext) },
                ),
            ),
        )

        override suspend fun onEnable() {
            skillManager.resolvePackaged(refresh = false)
            skillManager.setEnabled(true)
            poller = OmniLinkAgentEventPoller(appContext).also { it.start() }
        }

        override suspend fun onDisable() {
            poller?.stop()
            poller = null
            skillManager.setEnabled(false)
        }
    }

    companion object {
        const val ADAPTER_ID = "omnilink_agent"
    }
}

private class OmniLinkAgentToolHandler(context: Context) : ToolHandler {
    private val appContext = context.applicationContext
    private val gateway = OmniLinkLocalMcpClient(context.applicationContext)
    private val eventSubscriptionStore = OmniLinkEventSubscriptionStore(appContext)

    override val toolNames: Set<String> = OmniLinkAgentTools.TOOL_NAMES

    override suspend fun execute(
        toolCall: AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle,
    ): ToolExecutionResult {
        val toolName = toolCall.function.name
        return try {
            toolHandle.throwIfStopRequested()
            val result = when (toolName) {
                OmniLinkAgentTools.DEVICES -> gateway.call(
                    toolName = "omnilink_devices",
                    arguments = emptyMap(),
                )
                OmniLinkAgentTools.CONTROL -> {
                    val action = args.requiredString("action")
                    val input = args.optionalObject("input").toMutableMap()
                    if (action == "send_message") {
                        val messageId = input["messageId"]?.toString()
                            ?.trim()
                            ?.ifBlank { null }
                            ?: "omnibot-${UUID.randomUUID()}"
                        input["conversationId"] = input["conversationId"]?.toString()
                            ?.ifBlank { null }
                            ?: "omnibot-collaboration"
                        input["recipientAgentId"] = input["recipientAgentId"]?.toString()
                            ?.ifBlank { null }
                            ?: "omnibot-omnilink-agent"
                        input["messageId"] = messageId
                        input["message"] = input["message"]?.toString()
                            ?.takeIf { it.isNotBlank() }
                            ?: throw IllegalArgumentException("input.message is required")
                    }
                    gateway.call(
                        toolName = OmniLinkAgentTools.CONTROL,
                        arguments = buildMap {
                            put("deviceId", args.requiredString("device_id"))
                            put("action", action)
                            if (input.isNotEmpty()) put("input", input)
                        },
                        idempotencyKey = input["messageId"]?.toString()
                            ?.takeIf { action == "send_message" }
                            ?: "omnibot-control-${UUID.randomUUID()}",
                    )
                }
                OmniLinkAgentTools.EVENTS -> {
                    val deviceId = args.requiredString("device_id")
                    val eventTypes = args.optionalStringList("event_types")
                        .ifEmpty { listOf("AGENT_MESSAGE_RECEIVED") }
                    when (args.optionalString("mode").ifBlank { "read" }) {
                        "subscribe" -> subscribeEvents(
                            deviceId = deviceId,
                            eventTypes = eventTypes,
                            mode = "subscribe",
                        )
                        "stop" -> subscribeEvents(
                            deviceId = deviceId,
                            eventTypes = eventTypes,
                            mode = "stop",
                        )
                        "read" -> {
                            val cursor = args.optionalString("cursor")
                            gateway.call(
                                toolName = OmniLinkAgentTools.EVENTS,
                                arguments = buildMap {
                                    put("deviceIds", listOf(deviceId))
                                    put("eventTypes", eventTypes)
                                    put("limitPerDevice", 32)
                                    put("waitMs", args.optionalInt("wait_ms").coerceIn(0, 30_000))
                                    if (cursor.isNotBlank()) put("after", mapOf(deviceId to cursor))
                                },
                            )
                        }
                        else -> throw IllegalArgumentException("mode must be read, subscribe, or stop")
                    }
                }
                else -> return ToolExecutionResult.Error(toolName, "Unsupported OmniLink tool")
            }
            val payload = result.payload()
            val hasPartitionFailures = payload.hasEventFailures()
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = when (toolName) {
                    OmniLinkAgentTools.CONTROL -> if (
                        args.optionalString("action") == "send_message" && result.success
                    ) {
                        "已通过 OmniLink 发出协作消息"
                    } else {
                        result.summaryText
                    }
                    OmniLinkAgentTools.EVENTS -> when {
                        !result.success || hasPartitionFailures -> "协作事件订阅未就绪"
                        args.optionalString("mode") == "stop" -> "已停止协作事件回流"
                        args.optionalString("mode").ifBlank { "read" } == "read" -> "已读取协作事件"
                        else -> "已开始回流协作事件，后续事件会自动回流当前聊天"
                    }
                    else -> "已读取协作设备"
                },
                previewJson = result.previewJson,
                rawResultJson = result.rawResultJson,
                success = result.success && !hasPartitionFailures,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolExecutionResult.Error(toolName, error.message ?: "OmniLink tool failed")
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        optionalString(key).takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("$key is required")

    private fun JsonObject.optionalString(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.optionalObject(key: String): Map<String, Any?> {
        val element = get(key) ?: return emptyMap()
        return runCatching {
            Gson().fromJson<Map<String, Any?>>(
                element.toString(),
                object : TypeToken<Map<String, Any?>>() {}.type,
            )
        }.getOrElse { throw IllegalArgumentException("$key must be an object") }
    }

    private fun JsonObject.optionalInt(key: String): Int =
        get(key)?.jsonPrimitive?.intOrNull ?: 0

    private fun JsonObject.optionalStringList(key: String): List<String> {
        val values = get(key) as? JsonArray ?: return emptyList()
        return values.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
    }

    private suspend fun subscribeEvents(
        deviceId: String,
        eventTypes: List<String>,
        mode: String,
    ): RemoteMcpCallResult {
        require(mode in EVENT_SUBSCRIPTION_MODES) { "mode must be subscribe or stop" }
        if (mode == "stop") {
            eventSubscriptionStore.remove(deviceId)
            return localResult(
                mapOf(
                    "deviceId" to deviceId,
                    "mode" to mode,
                    "subscribed" to false,
                ),
            )
        }
        require(eventTypes.isNotEmpty()) { "event_types is required for start" }

        val cursor = eventSubscriptionStore.cursor(deviceId)
        val result = gateway.call(
            toolName = "omnilink_events",
            arguments = buildMap {
                put("deviceIds", listOf(deviceId))
                put("eventTypes", eventTypes)
                put("limitPerDevice", EVENT_LIMIT)
                put("waitMs", 0)
                if (!cursor.isNullOrBlank()) put("after", mapOf(deviceId to cursor))
            },
        )
        if (!result.success || result.payload().hasEventFailures()) return result

        publishIncomingEvents(result.payload()["events"] as? List<*> ?: emptyList<Any?>())
        val nextCursor = result.payload().cursorFor(deviceId)
        eventSubscriptionStore.set(deviceId, eventTypes)
        nextCursor?.let { eventSubscriptionStore.setCursor(deviceId, it) }
        return result
    }

    private companion object {
        val EVENT_SUBSCRIPTION_MODES = setOf("subscribe", "stop")
        const val EVENT_LIMIT = 32
    }
}

private class OmniLinkEventSubscriptionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "omnilink_agent_events",
        Context.MODE_PRIVATE,
    )

    fun deviceIds(): Set<String> = synchronized(this) {
        preferences.getStringSet(SUBSCRIBED_DEVICE_IDS_KEY, emptySet()).orEmpty().toSet()
    }

    fun eventTypes(deviceId: String): Set<String> = synchronized(this) {
        preferences.getStringSet("event_types_$deviceId", emptySet()).orEmpty().toSet()
    }

    fun set(deviceId: String, eventTypes: List<String>) = synchronized(this) {
        val next = deviceIds() + deviceId
        preferences.edit()
            .putStringSet(SUBSCRIBED_DEVICE_IDS_KEY, next)
            .putStringSet("event_types_$deviceId", eventTypes.toSet())
            .apply()
    }

    fun remove(deviceId: String) = synchronized(this) {
        val next = deviceIds() - deviceId
        preferences.edit()
            .putStringSet(SUBSCRIBED_DEVICE_IDS_KEY, next)
            .remove("event_types_$deviceId")
            .apply()
    }

    fun cursor(deviceId: String): String? = synchronized(this) {
        preferences.getString("cursor_$deviceId", null)
    }

    fun setCursor(deviceId: String, cursor: String) = synchronized(this) {
        preferences.edit().putString("cursor_$deviceId", cursor).apply()
    }

    private companion object {
        const val SUBSCRIBED_DEVICE_IDS_KEY = "subscribed_event_device_ids"
    }
}

private class OmniLinkAgentEventPoller(context: Context) {
    private val gateway = OmniLinkLocalMcpClient(context.applicationContext)
    private val eventSubscriptionStore = OmniLinkEventSubscriptionStore(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val waitMillis = runCatching { pollOnce() }
                    .onFailure { error ->
                        Log.w(TAG, "poll failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                    .getOrDefault(2_000L)
                delay(waitMillis.coerceIn(250L, 3_000L))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollOnce(): Long {
        val localDeviceId = gateway.localDeviceId()
        val deviceIds = localDeviceId
            ?.takeIf { it.matches(DEVICE_ID) }
            ?.let { localId ->
                linkedSetOf<String>().apply {
                    add(localId)
                    addAll(eventSubscriptionStore.deviceIds())
                }.toList().take(MAX_DEVICES)
            }
            .orEmpty()
        if (deviceIds.isEmpty()) return 2_000L

        deviceIds.forEach { deviceId ->
            val cursor = eventSubscriptionStore.cursor(deviceId).orEmpty()
            val isLocalDevice = deviceId == localDeviceId
            val eventTypes = linkedSetOf<String>().apply {
                if (isLocalDevice) add("AGENT_MESSAGE_RECEIVED")
                addAll(eventSubscriptionStore.eventTypes(deviceId))
            }
            if (eventTypes.isEmpty()) return@forEach
            val arguments = buildMap<String, Any?> {
                put("deviceIds", listOf(deviceId))
                put("eventTypes", eventTypes.toList())
                put("limitPerDevice", if (cursor.isBlank()) 1 else EVENT_LIMIT)
                put("waitMs", POLL_WAIT_MILLIS)
                if (cursor.isNotBlank()) put("after", mapOf(deviceId to cursor))
            }
            val result = gateway.call("omnilink_events", arguments)
            if (!result.success) {
                Log.w(TAG, "event read failed device=$deviceId: ${result.summaryText}")
                return@forEach
            }
            val payload = result.payload()
            val failures = (payload["failures"] as? List<*>)
                .orEmpty()
                .mapNotNull { rawFailure ->
                    val failure = rawFailure as? Map<*, *> ?: return@mapNotNull null
                    val failureBody = failure["failure"] as? Map<*, *> ?: return@mapNotNull null
                    val code = failureBody["code"]?.toString()?.take(96).orEmpty()
                    val category = failureBody["category"]?.toString()?.take(48).orEmpty()
                    if (code.isBlank() && category.isBlank()) null
                    else "${category.ifBlank { "UNKNOWN" }}:${code.ifBlank { "UNKNOWN" }}"
                }
            if (failures.isNotEmpty()) {
                Log.w(
                    TAG,
                    "event partition failed device=$deviceId failures=${failures.joinToString(",")}",
                )
            }
            val rawEvents = (payload["events"] as? List<*>).orEmpty()
            var deliveredEvents = 0
            rawEvents.forEach { rawEvent ->
                val incoming = toIncomingEvent(
                    rawEvent,
                    expectedRecipientAgentId = OMNILINK_AGENT_ID,
                )
                if (incoming == null) {
                    Log.w(TAG, "event dropped: sanitized payload shape unavailable")
                } else {
                    deliveredEvents += 1
                    OmniLinkAgentEventBus.publish(incoming)
                }
            }
            if (rawEvents.isNotEmpty()) {
                Log.i(
                    TAG,
                    "event page device=$deviceId received=${rawEvents.size} " +
                        "delivered=$deliveredEvents",
                )
            }
            val nextCursor = (payload["cursors"] as? Map<*, *>)
                ?.get(deviceId)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            if (failures.isEmpty() && nextCursor != null) {
                eventSubscriptionStore.setCursor(deviceId, nextCursor)
            }
        }
        return 250L
    }

    private companion object {
        const val TAG = "OmniLinkAgentPoller"
        const val EVENT_LIMIT = 32
        const val POLL_WAIT_MILLIS = 1_000
        const val MAX_DEVICES = 16
        val DEVICE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

private fun localResult(payload: Map<String, Any?>): RemoteMcpCallResult {
    val encoded = Gson().toJson(payload)
    return RemoteMcpCallResult(
        summaryText = "OmniLink notification watch updated",
        previewJson = encoded,
        rawResultJson = encoded,
        success = true,
    )
}

private fun Map<String, Any?>.hasEventFailures(): Boolean =
    (this["failures"] as? List<*>)?.isNotEmpty() == true

private fun Map<String, Any?>.cursorFor(deviceId: String): String? =
    (this["cursors"] as? Map<*, *>)?.get(deviceId)?.toString()?.takeIf(String::isNotBlank)

private fun publishIncomingEvents(rawEvents: List<*>) {
    rawEvents.forEach { rawEvent ->
        toIncomingEvent(
            rawEvent,
            expectedRecipientAgentId = OMNILINK_AGENT_ID,
        )?.let(OmniLinkAgentEventBus::publish)
    }
}

internal fun toIncomingEvent(
    raw: Any?,
    expectedRecipientAgentId: String? = null,
): Map<String, Any?>? {
    val event = raw as? Map<*, *> ?: return null
    val data = event["data"] as? Map<*, *> ?: return null
    val payload = data["payload"] as? Map<*, *> ?: return null
    val eventType = data["type"]?.toString().orEmpty()
    val sourceDeviceId = data["sourceDeviceId"]?.toString().orEmpty()
    val deviceId = event["deviceid"]?.toString().orEmpty()
    return when (eventType) {
        "AGENT_MESSAGE_RECEIVED" -> {
            val message = payload["message"]?.toString()?.trim().orEmpty()
            val messageId = payload["messageId"]?.toString()?.trim().orEmpty()
            val recipientAgentId = payload["recipientAgentId"]?.toString()?.trim().orEmpty()
            if (message.isBlank() || messageId.isBlank()) return null
            if (expectedRecipientAgentId != null && recipientAgentId != expectedRecipientAgentId) {
                return null
            }
            mapOf(
                "kind" to "omnilink_agent_message",
                "messageId" to messageId,
                "conversationId" to payload["conversationId"]?.toString().orEmpty(),
                "message" to message,
                "senderAgentId" to payload["senderAgentId"]?.toString().orEmpty(),
                "recipientAgentId" to recipientAgentId,
                "sourceDeviceId" to sourceDeviceId,
                "deviceId" to deviceId,
                "sentAt" to (payload["sentAt"] as? Number)?.toLong(),
            )
        }
        "NOTIFICATION_UPSERTED", "NOTIFICATION_REMOVED" -> {
            val eventId = event["id"]?.toString()?.trim().orEmpty()
            if (eventId.isBlank()) return null
            mapOf(
                "kind" to "omnilink_device_notification",
                "eventId" to eventId,
                "sourceDeviceId" to sourceDeviceId,
                "deviceId" to deviceId,
                "applicationId" to payload["applicationId"]?.toString().orEmpty(),
                "postedAt" to (payload["postedAt"] as? Number)?.toLong(),
                "notificationIdDigest" to payload["notificationIdDigest"]?.toString().orEmpty(),
                "sensitive" to (payload["sensitive"] as? Boolean ?: false),
                "hasTitle" to (payload["hasTitle"] as? Boolean ?: false),
                "hasBody" to (payload["hasBody"] as? Boolean ?: false),
                "actionCount" to (payload["actionCount"] as? Number)?.toInt(),
                "removed" to (payload["removed"] as? Boolean ?: eventType == "NOTIFICATION_REMOVED"),
            )
        }
        else -> null
    }
}

private class OmniLinkLocalMcpClient(private val context: Context) {
    private val lock = Any()
    private var cachedCredential: Credential? = null
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type

    suspend fun call(
        toolName: String,
        arguments: Map<String, Any?>,
        idempotencyKey: String? = null,
    ): RemoteMcpCallResult {
        val meta = idempotencyKey?.let { mapOf(IDEMPOTENCY_META_KEY to it) }.orEmpty()
        return try {
            callWithCredential(toolName, arguments, meta, credential())
        } catch (error: Exception) {
            if (!isUnauthorized(error)) throw error
            // OmniLink may rotate the local gateway credential when its app
            // process restarts. Refresh both the bearer credential and MCP
            // session once; never log either value.
            synchronized(lock) { cachedCredential = null }
            RemoteMcpClient.invalidateSession(SERVER_ID)
            callWithCredential(toolName, arguments, meta, credential())
        }
    }

    private suspend fun callWithCredential(
        toolName: String,
        arguments: Map<String, Any?>,
        meta: Map<String, Any?>,
        credential: Credential,
    ): RemoteMcpCallResult {
        val config = RemoteMcpServerConfig(
            id = SERVER_ID,
            name = "OmniLink 本地网关",
            endpointUrl = "http://127.0.0.1:${credential.port}/mcp",
            bearerToken = credential.token,
        )
        return RemoteMcpClient.callTool(config, toolName, arguments, meta)
    }

    private fun isUnauthorized(error: Throwable): Boolean =
        generateSequence(error) { it.cause }
            .any { throwable -> throwable.message?.startsWith("HTTP 401") == true }

    suspend fun localDeviceId(): String? = withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://$AUTHORITY/identity")
        val cursor = context.contentResolver.query(uri, null, null, null, null)
            ?: return@withContext null
        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            val index = it.getColumnIndex("deviceId")
            if (index < 0) return@withContext null
            it.getString(index).orEmpty().trim().takeIf { value ->
                value.matches(DEVICE_ID)
            }
        }
    }

    private suspend fun credential(): Credential {
        synchronized(lock) {
            cachedCredential?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() + 5_000L }
                ?.let { return it }
        }
        val next = withContext(Dispatchers.IO) {
            val uri = Uri.parse("content://$AUTHORITY/credential/omnibot-omnilink-agent")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
                ?: throw IllegalStateException("OmniLink local gateway is unavailable")
            cursor.use(::readCredential)
        }
        synchronized(lock) { cachedCredential = next }
        return next
    }

    private fun readCredential(cursor: Cursor): Credential {
        if (!cursor.moveToFirst()) throw IllegalStateException("OmniLink gateway returned no credential")
        fun value(name: String): String {
            val index = cursor.getColumnIndexOrThrow(name)
            return cursor.getString(index).orEmpty().trim()
        }
        val port = cursor.getInt(cursor.getColumnIndexOrThrow("port"))
        val token = value("token").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OmniLink gateway credential is empty")
        val ttl = cursor.getLong(cursor.getColumnIndexOrThrow("ttlMillis"))
        return Credential(
            port = port,
            token = token,
            expiresAt = SystemClock.elapsedRealtime() + ttl.coerceAtLeast(1_000L),
        )
    }

    private data class Credential(
        val port: Int,
        val token: String,
        val expiresAt: Long,
    )

    companion object {
        private const val AUTHORITY = "com.omni.omnilink.mcp-gateway"
        private const val SERVER_ID = "omnilink-local"
        private const val IDEMPOTENCY_META_KEY = "io.omnilink/idempotencyKey"
        private val DEVICE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

private fun RemoteMcpCallResult.payload(): Map<String, Any?> {
    val root = runCatching { JsonParser.parseString(rawResultJson) }.getOrNull()
    val text = root?.asJsonObject
        ?.getAsJsonArray("content")
        ?.firstOrNull()
        ?.asJsonObject
        ?.get("text")
        ?.let { if (it.isJsonPrimitive) it.asString else null }
    val source = text ?: rawResultJson
    return runCatching { Gson().fromJson<Map<String, Any?>>(source, object : TypeToken<Map<String, Any?>>() {}.type) }
        .getOrDefault(emptyMap())
}
