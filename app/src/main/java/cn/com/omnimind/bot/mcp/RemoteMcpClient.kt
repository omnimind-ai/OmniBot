package cn.com.omnimind.bot.mcp

import androidx.annotation.VisibleForTesting
import cn.com.omnimind.baselib.util.ContentEndpointSecurity
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object RemoteMcpClient {
    private const val TAG = "[RemoteMcpClient]"
    private const val DEFAULT_PROTOCOL_VERSION = "2024-11-05"
    private const val SESSION_ID_HEADER = "Mcp-Session-Id"
    private const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val sessions = ConcurrentHashMap<String, RemoteMcpSession>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
        // An automatic 307/308 redirect could replay a JSON-RPC body to another origin.
        // Require users to configure the final endpoint instead.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private data class HttpJsonResponse(
        val code: Int,
        val body: String,
        val contentType: String?,
        val sessionId: String?,
    )

    private data class RemoteMcpSession(
        val sessionId: String?,
        val protocolVersion: String = DEFAULT_PROTOCOL_VERSION,
    )

    private class HttpStatusException(
        val code: Int,
        override val message: String,
    ) : IOException(message)

    suspend fun initialize(config: RemoteMcpServerConfig): Map<String, Any?> {
        val result = callJsonRpc(
            config = config,
            method = "initialize",
            params = mapOf(
                "protocolVersion" to DEFAULT_PROTOCOL_VERSION,
                "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                "clientInfo" to mapOf("name" to "omnibot-android", "version" to "1.0")
            )
        )
        if (!looksLikeSseEndpoint(config.endpointUrl)) {
            runCatching {
                callJsonRpc(config, "notifications/initialized", emptyMap())
            }.onFailure {
                OmniLog.w(TAG, "initialized notification failed: ${it.message}")
            }
        }
        return deepStringMap(result) ?: emptyMap()
    }

    suspend fun listTools(config: RemoteMcpServerConfig): List<RemoteMcpToolDescriptor> {
        val result = if (looksLikeSseEndpoint(config.endpointUrl)) {
            callSseMethodWithInitialize(config, "tools/list", emptyMap())
        } else {
            initialize(config)
            callJsonRpc(config, "tools/list", emptyMap())
        }
        val resultMap = deepStringMap(result) ?: emptyMap()
        val tools = (resultMap["tools"] as? List<*>) ?: emptyList<Any>()
        return tools.mapNotNull { raw ->
            val toolMap = deepStringMap(raw) ?: return@mapNotNull null
            val name = toolMap["name"]?.toString()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            RemoteMcpToolDescriptor(
                serverId = config.id,
                serverName = config.name,
                toolName = name,
                description = toolMap["description"]?.toString()?.trim().orEmpty(),
                inputSchema = deepStringMap(toolMap["inputSchema"])
                    ?: deepStringMap(toolMap["parameters"])
                    ?: emptyMap()
            )
        }
    }

    suspend fun callTool(
        config: RemoteMcpServerConfig,
        toolName: String,
        arguments: Map<String, Any?>
    ): RemoteMcpCallResult {
        val result = if (looksLikeSseEndpoint(config.endpointUrl)) {
            callSseMethodWithInitialize(
                config = config,
                method = "tools/call",
                params = mapOf("name" to toolName, "arguments" to arguments),
            )
        } else {
            initialize(config)
            callJsonRpc(
                config = config,
                method = "tools/call",
                params = mapOf("name" to toolName, "arguments" to arguments)
            )
        }
        return buildModelSafeCallResult(result)
    }

    @VisibleForTesting
    internal fun buildModelSafeCallResult(result: Any?): RemoteMcpCallResult {
        // MCP `_meta` is reserved for the client transport. It can contain short-lived
        // download headers or other credentials and must never enter the model-visible
        // tool result, UI previews, logs, or persisted conversation history.
        val modelSafeResult = stripTransportMetadata(result)
        return RemoteMcpCallResult(
            summaryText = buildSummaryText(modelSafeResult),
            previewJson = buildPreviewJson(modelSafeResult),
            rawResultJson = gson.toJson(modelSafeResult),
            success = !(deepStringMap(modelSafeResult)?.get("isError") == true)
        )
    }

    @VisibleForTesting
    internal fun stripTransportMetadata(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> value.entries
                .filterNot { (key, _) -> key?.toString() == "_meta" }
                .associate { (key, nested) ->
                    key.toString() to stripTransportMetadata(nested)
                }
            is List<*> -> value.map(::stripTransportMetadata)
            is Array<*> -> value.map(::stripTransportMetadata)
            else -> value
        }
    }

    fun invalidateSession(serverId: String? = null) {
        if (serverId == null) {
            sessions.clear()
            return
        }
        sessions.remove(serverId)
    }

    private suspend fun callJsonRpc(
        config: RemoteMcpServerConfig,
        method: String,
        params: Map<String, Any?>
    ): Any? {
        val requestId = UUID.randomUUID().toString()
        val body = mapOf(
            "jsonrpc" to "2.0",
            "id" to requestId,
            "method" to method,
            "params" to params
        )
        val expectResponse = !method.startsWith("notifications/") && !method.startsWith("$/")
        val responseBody = executeRpcRequest(
            config = config,
            payload = gson.toJson(body),
            requestId = requestId,
            expectResponse = expectResponse
        )
        if (!expectResponse) {
            return emptyMap<String, Any?>()
        }
        val responseMap = runCatching {
            gson.fromJson<Map<String, Any?>>(responseBody, mapType)
        }.getOrElse {
            throw IllegalStateException(
                "Invalid MCP response: ${it.message}; preview=${responseBody.take(200)}"
            )
        }
        val errorMap = deepStringMap(responseMap["error"])
        if (errorMap != null) {
            val errorMessage = errorMap["message"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "Unknown MCP error"
            throw IllegalStateException(errorMessage)
        }
        if (method == "initialize") {
            val negotiatedProtocol = deepStringMap(responseMap["result"])
                ?.get("protocolVersion")
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            updateSession(config.id, protocolVersion = negotiatedProtocol)
        }
        return responseMap["result"]
    }

    private suspend fun executeRpcRequest(
        config: RemoteMcpServerConfig,
        payload: String,
        requestId: String,
        expectResponse: Boolean,
    ): String {
        if (looksLikeSseEndpoint(config.endpointUrl)) {
            return executeSseRpc(config, payload, requestId, expectResponse)
        }
        return runCatching {
            executeHttpRpc(config, config.endpointUrl, payload, requestId, expectResponse)
        }.getOrElse { throwable ->
            if (shouldTryLegacySseFallback(throwable)) {
                return executeSseRpc(config, payload, requestId, expectResponse)
            }
            throw throwable
        }
    }

    private suspend fun executeHttpJson(
        config: RemoteMcpServerConfig,
        url: String,
        payload: String,
    ): HttpJsonResponse = withContext(Dispatchers.IO) {
        val configuredEndpoint = validateEndpointSecurity(config.endpointUrl, config.bearerToken)
        val sameOriginUrl = resolveAgainstBase(configuredEndpoint, url)
        val requestBuilder = Request.Builder()
            .url(sameOriginUrl)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        applyMcpSessionHeaders(config, requestBuilder)

        if (config.bearerToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.bearerToken}")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty().trim()
            val contentType = response.header("Content-Type")
            val sessionId = response.header(SESSION_ID_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
            updateSession(config.id, sessionId = sessionId)
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    code = response.code,
                    message = "HTTP ${response.code}: ${response.message}",
                )
            }
            HttpJsonResponse(
                code = response.code,
                body = if (responseBody.isBlank()) "{}" else responseBody,
                contentType = contentType,
                sessionId = sessionId,
            )
        }
    }

    private suspend fun executeHttpRpc(
        config: RemoteMcpServerConfig,
        url: String,
        payload: String,
        requestId: String,
        expectResponse: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val configuredEndpoint = validateEndpointSecurity(config.endpointUrl, config.bearerToken)
        val sameOriginUrl = resolveAgainstBase(configuredEndpoint, url)
        val requestBuilder = Request.Builder()
            .url(sameOriginUrl)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        applyMcpSessionHeaders(config, requestBuilder)

        if (config.bearerToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.bearerToken}")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            val contentType = response.header("Content-Type")
            val sessionId = response.header(SESSION_ID_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
            updateSession(config.id, sessionId = sessionId)

            if (!response.isSuccessful) {
                throw HttpStatusException(
                    code = response.code,
                    message = "HTTP ${response.code}: ${response.message}",
                )
            }
            if (!expectResponse) {
                return@withContext "{}"
            }

            val body = response.body ?: throw IllegalStateException("MCP response body is empty")
            if (isEventStream(contentType)) {
                return@withContext readSseJsonResponse(body.charStream().buffered(), requestId)
            }

            val responseBody = body.string().orEmpty().trim()
            if (responseBody.isBlank()) {
                return@withContext "{}"
            }
            if (looksLikeSseBody(responseBody)) {
                return@withContext parseSseJsonResponseBody(responseBody, requestId)
            }
            responseBody
        }
    }

    private suspend fun executeSseRpc(
        config: RemoteMcpServerConfig,
        payload: String,
        requestId: String,
        expectResponse: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val configuredEndpoint = validateEndpointSecurity(config.endpointUrl, config.bearerToken)
        val sseRequestBuilder = Request.Builder()
            .url(configuredEndpoint)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        if (config.bearerToken.isNotBlank()) {
            sseRequestBuilder.header("Authorization", "Bearer ${config.bearerToken}")
        }

        client.newCall(sseRequestBuilder.build()).execute().use { sseResponse ->
            if (!sseResponse.isSuccessful) {
                throw HttpStatusException(
                    code = sseResponse.code,
                    message = "HTTP ${sseResponse.code}: ${sseResponse.message}",
                )
            }
            val reader = sseResponse.body?.charStream()?.buffered()
                ?: throw IllegalStateException("SSE response body is empty")
            val endpointData = readEndpointEvent(reader)
            val messageUrl = resolveAgainstBase(configuredEndpoint, endpointData)

            val postResponse = executeHttpJson(config, messageUrl, payload)
            if (!expectResponse) {
                return@withContext "{}"
            }

            // Some servers may return JSON directly in HTTP body instead of SSE push.
            if (postResponse.code in 200..299 && postResponse.body.startsWith("{")) {
                return@withContext postResponse.body
            }
            return@withContext readSseJsonResponse(reader, requestId)
        }
    }

    private suspend fun callSseMethodWithInitialize(
        config: RemoteMcpServerConfig,
        method: String,
        params: Map<String, Any?>,
    ): Any? = withContext(Dispatchers.IO) {
        val configuredEndpoint = validateEndpointSecurity(config.endpointUrl, config.bearerToken)
        val sseRequestBuilder = Request.Builder()
            .url(configuredEndpoint)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        if (config.bearerToken.isNotBlank()) {
            sseRequestBuilder.header("Authorization", "Bearer ${config.bearerToken}")
        }

        client.newCall(sseRequestBuilder.build()).execute().use { sseResponse ->
            if (!sseResponse.isSuccessful) {
                throw HttpStatusException(
                    code = sseResponse.code,
                    message = "HTTP ${sseResponse.code}: ${sseResponse.message}",
                )
            }
            val reader = sseResponse.body?.charStream()?.buffered()
                ?: throw IllegalStateException("SSE response body is empty")
            val endpointData = readEndpointEvent(reader)
            val messageUrl = resolveAgainstBase(configuredEndpoint, endpointData)

            val initId = UUID.randomUUID().toString()
            val initPayload = gson.toJson(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to initId,
                    "method" to "initialize",
                    "params" to mapOf(
                        "protocolVersion" to DEFAULT_PROTOCOL_VERSION,
                        "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                        "clientInfo" to mapOf("name" to "omnibot-android", "version" to "1.0"),
                    ),
                )
            )
            executeHttpJson(config, messageUrl, initPayload)
            val initResponseMap = parseJsonMap(readSseJsonResponse(reader, initId))
            val initError = deepStringMap(initResponseMap["error"])
            if (initError != null) {
                val message = initError["message"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "SSE initialize failed"
                throw IllegalStateException(message)
            }
            val negotiatedProtocol = deepStringMap(initResponseMap["result"])
                ?.get("protocolVersion")
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            updateSession(config.id, protocolVersion = negotiatedProtocol)

            val initializedNotification = gson.toJson(
                mapOf(
                    "jsonrpc" to "2.0",
                    "method" to "notifications/initialized",
                    "params" to emptyMap<String, Any>(),
                )
            )
            executeHttpJson(config, messageUrl, initializedNotification)

            val requestId = UUID.randomUUID().toString()
            val requestPayload = gson.toJson(
                mapOf(
                    "jsonrpc" to "2.0",
                    "id" to requestId,
                    "method" to method,
                    "params" to params,
                )
            )
            executeHttpJson(config, messageUrl, requestPayload)

            val responseMap = parseJsonMap(readSseJsonResponse(reader, requestId))
            val errorMap = deepStringMap(responseMap["error"])
            if (errorMap != null) {
                val errorMessage = errorMap["message"]?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Unknown MCP error"
                throw IllegalStateException(errorMessage)
            }
            return@withContext responseMap["result"]
        }
    }

    private fun applyMcpSessionHeaders(
        config: RemoteMcpServerConfig,
        requestBuilder: Request.Builder,
    ) {
        val session = sessions[config.id] ?: return
        requestBuilder.header(PROTOCOL_VERSION_HEADER, session.protocolVersion)
        session.sessionId?.takeIf { it.isNotBlank() }?.let {
            requestBuilder.header(SESSION_ID_HEADER, it)
        }
    }

    private fun updateSession(
        serverId: String,
        sessionId: String? = null,
        protocolVersion: String? = null,
    ) {
        if (sessionId == null && protocolVersion == null) return
        sessions.compute(serverId) { _, current ->
            RemoteMcpSession(
                sessionId = sessionId ?: current?.sessionId,
                protocolVersion = protocolVersion ?: current?.protocolVersion ?: DEFAULT_PROTOCOL_VERSION,
            )
        }
    }

    private fun shouldTryLegacySseFallback(throwable: Throwable): Boolean {
        if (throwable !is HttpStatusException) return false
        return throwable.code == 404 || throwable.code == 405
    }

    private fun isEventStream(contentType: String?): Boolean {
        return contentType
            ?.substringBefore(";")
            ?.trim()
            ?.equals("text/event-stream", ignoreCase = true) == true
    }

    private fun looksLikeSseBody(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("event:") || trimmed.startsWith("data:") || trimmed.startsWith(":")
    }

    private fun parseJsonMap(jsonText: String): Map<String, Any?> {
        return runCatching {
            gson.fromJson<Map<String, Any?>>(jsonText, mapType)
        }.getOrElse {
            throw IllegalStateException(
                "Invalid MCP response: ${it.message}; preview=${jsonText.take(200)}"
            )
        }
    }

    private fun readEndpointEvent(reader: BufferedReader): String {
        var currentEvent: String? = null
        while (true) {
            val line = reader.readLine()
                ?: throw IllegalStateException("SSE stream closed before endpoint event")
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                continue
            }
            if (trimmed.startsWith("event:")) {
                currentEvent = trimmed.removePrefix("event:").trim()
                continue
            }
            if (trimmed.startsWith("data:")) {
                val data = trimmed.removePrefix("data:").trim()
                if (currentEvent == "endpoint" && data.isNotEmpty()) {
                    return data
                }
            }
        }
    }

    private fun readSseJsonResponse(
        reader: BufferedReader,
        requestId: String,
    ): String {
        val dataLines = mutableListOf<String>()
        while (true) {
            val line = try {
                reader.readLine()
            } catch (e: SocketTimeoutException) {
                throw IllegalStateException("SSE response timeout")
            } ?: throw IllegalStateException("SSE stream closed before RPC response")
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                val payload = dataLines.joinToString("\n").trim()
                dataLines.clear()
                matchingJsonRpcPayload(payload, requestId)?.let { return it }
                continue
            }
            if (trimmed.startsWith("data:")) {
                dataLines.add(trimmed.removePrefix("data:").trim())
            }
        }
    }

    private fun parseSseJsonResponseBody(
        body: String,
        requestId: String,
    ): String {
        val dataLines = mutableListOf<String>()
        body.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                val payload = dataLines.joinToString("\n").trim()
                dataLines.clear()
                matchingJsonRpcPayload(payload, requestId)?.let { return it }
                return@forEach
            }
            if (trimmed.startsWith("data:")) {
                dataLines.add(trimmed.removePrefix("data:").trim())
            }
        }

        val trailingPayload = dataLines.joinToString("\n").trim()
        matchingJsonRpcPayload(trailingPayload, requestId)?.let { return it }

        throw IllegalStateException(
            "SSE MCP response did not contain JSON-RPC response for id=$requestId; preview=${body.take(200)}"
        )
    }

    private fun matchingJsonRpcPayload(payload: String, requestId: String): String? {
        if (payload.isBlank() || payload == "[DONE]") return null
        val map = runCatching {
            gson.fromJson<Map<String, Any?>>(payload, mapType)
        }.getOrNull() ?: return null
        val payloadId = map["id"]?.toString()
        return if (payloadId == requestId || payloadId == "\"$requestId\"") {
            payload
        } else {
            null
        }
    }

    @VisibleForTesting
    internal fun resolveAgainstBase(baseUrl: String, value: String): String {
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalStateException("Invalid configured MCP endpoint")
        val resolved = value.toHttpUrlOrNull() ?: base.resolve(value)
            ?: throw IllegalStateException("Unable to resolve MCP endpoint")
        if (
            resolved.scheme != base.scheme ||
            resolved.host != base.host ||
            resolved.port != base.port
        ) {
            throw SecurityException("MCP server returned a cross-origin endpoint")
        }
        if (resolved.username.isNotEmpty() || resolved.password.isNotEmpty()) {
            throw SecurityException("MCP endpoint must not contain URL credentials")
        }
        return runCatching {
            ContentEndpointSecurity.requireSafe(
                rawUrl = resolved.toString(),
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            )
        }.getOrElse { throw SecurityException("MCP endpoint URL is unsafe") }
    }

    @VisibleForTesting
    internal fun validateEndpointSecurity(endpointUrl: String, bearerToken: String): String {
        return runCatching {
            ContentEndpointSecurity.requireSafe(
                rawUrl = endpointUrl,
                allowInsecureLoopback = CredentialEndpointSecurity.isDebugLoopbackAllowed(),
            )
        }.getOrElse { throw SecurityException("MCP endpoint URL is unsafe") }
    }

    private fun looksLikeSseEndpoint(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.encodedPath.endsWith("/sse")
    }

    private suspend fun executeHttpJsonLegacy(
        config: RemoteMcpServerConfig,
        payload: String
    ): String = withContext(Dispatchers.IO) {
        executeHttpJson(config, config.endpointUrl, payload).body
    }

    private fun parseSseBody(body: String): String {
        val events = body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotEmpty() && it != "[DONE]" }
            .toList()
        return events.lastOrNull() ?: "{}"
    }

    private fun buildSummaryText(result: Any?): String {
        val resultMap = deepStringMap(result)
        val contentList = resultMap?.get("content") as? List<*>
        val textBlocks = contentList.orEmpty().mapNotNull { item ->
            val map = deepStringMap(item) ?: return@mapNotNull null
            if (map["type"]?.toString() == "text") {
                map["text"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
        }
        if (textBlocks.isNotEmpty()) {
            return textBlocks.joinToString("\n").take(600)
        }
        return gson.toJson(result).take(600)
    }

    private fun buildPreviewJson(result: Any?): String {
        val raw = gson.toJson(result)
        return if (raw.length <= 1200) raw else raw.take(1200) + "..."
    }

    private fun deepStringMap(value: Any?): Map<String, Any?>? {
        return when (value) {
            null -> null
            is Map<*, *> -> value.entries.associate { (key, rawValue) ->
                key.toString() to normalizeValue(rawValue)
            }
            else -> null
        }
    }

    private fun normalizeValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> deepStringMap(value)
            is List<*> -> value.map { normalizeValue(it) }
            else -> value
        }
    }
}
