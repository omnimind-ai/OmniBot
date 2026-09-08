package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.util.OmniLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    internal const val DEFAULT_PROTOCOL_VERSION = "2025-11-25"
    internal const val MODERN_PROTOCOL_VERSION = "2026-07-28"
    private const val SESSION_ID_HEADER = "Mcp-Session-Id"
    private const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val sessions = ConcurrentHashMap<String, RemoteMcpSession>()
    private val initializationLocks = ConcurrentHashMap<String, Mutex>()
    private val protocolProbeLocks = ConcurrentHashMap<String, Mutex>()
    private val modernHeaderBindings =
        ConcurrentHashMap<String, List<ModernHeaderBinding>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(40, TimeUnit.SECONDS)
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
        val initialized: Boolean = false,
        val initializeResult: Map<String, Any?> = emptyMap(),
        val effectiveTransport: RemoteMcpTransport? = null,
        val protocolEra: ProtocolEra = ProtocolEra.UNKNOWN,
    )

    private enum class ProtocolEra { UNKNOWN, MODERN, LEGACY }

    private data class ModernHeaderBinding(
        val name: String,
        val path: List<String>,
        val type: String,
    )

    private class HttpStatusException(
        val code: Int,
        val responseBody: String = "",
        override val message: String,
    ) : IOException(message)

    private class RpcErrorException(
        val code: Int,
        val data: Map<String, Any?>?,
        override val message: String,
    ) : IOException(message)

    suspend fun initialize(config: RemoteMcpServerConfig): Map<String, Any?> {
        if (!usesSseTransport(config)) {
            sessions[config.id]?.takeIf { it.initialized }?.let {
                return it.initializeResult
            }
        }
        val lock = initializationLocks.getOrPut(config.id) { Mutex() }
        return lock.withLock {
            if (!usesSseTransport(config)) {
                sessions[config.id]?.takeIf { it.initialized }?.let {
                    return@withLock it.initializeResult
                }
                // An initialize request starts a new legacy MCP lifecycle and
                // must never carry a previously minted Mcp-Session-Id.
                sessions.remove(config.id)
            }
            val result = callJsonRpc(
                config = config,
                method = "initialize",
                params = mapOf(
                    "protocolVersion" to DEFAULT_PROTOCOL_VERSION,
                    "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                    "clientInfo" to mapOf("name" to "omnibot-android", "version" to "1.0")
                )
            )
            if (!usesSseTransport(config)) {
                runCatching {
                    callJsonRpc(config, "notifications/initialized", emptyMap())
                }.onFailure {
                    OmniLog.w(TAG, "initialized notification failed: ${it.message}")
                }
            }
            val normalized = deepStringMap(result) ?: emptyMap()
            updateSession(
                serverId = config.id,
                initialized = true,
                initializeResult = normalized,
                protocolEra = ProtocolEra.LEGACY,
            )
            normalized
        }
    }

    suspend fun listTools(config: RemoteMcpServerConfig): List<RemoteMcpToolDescriptor> = try {
        listToolsOnce(config)
    } catch (error: HttpStatusException) {
        val hasStatefulSession = sessions[config.id]?.sessionId != null
        if (error.code != 404 || !hasStatefulSession) throw error
        invalidateSession(config.id)
        listToolsOnce(config)
    }

    private suspend fun listToolsOnce(
        config: RemoteMcpServerConfig,
    ): List<RemoteMcpToolDescriptor> {
        val result = if (usesSseTransport(config)) {
            callSseMethodWithInitialize(config, "tools/list", emptyMap())
        } else if (useModernProtocol(config)) {
            callModernJsonRpc(config, "tools/list", emptyMap())
        } else {
            initialize(config)
            if (usesSseTransport(config)) {
                callSseMethodWithInitialize(config, "tools/list", emptyMap())
            } else {
                callJsonRpc(config, "tools/list", emptyMap())
            }
        }
        return parseToolDescriptors(config, result)
    }

    suspend fun close(config: RemoteMcpServerConfig) {
        try {
            val session = sessions[config.id]
            if (session?.sessionId != null &&
                session.protocolEra == ProtocolEra.LEGACY &&
                !usesSseTransport(config)
            ) {
                closeLegacyHttpSession(config)
            }
        } finally {
            invalidateSession(config.id)
        }
    }

    private fun parseToolDescriptors(
        config: RemoteMcpServerConfig,
        result: Any?,
    ): List<RemoteMcpToolDescriptor> {
        val resultMap = deepStringMap(result) ?: emptyMap()
        val tools = (resultMap["tools"] as? List<*>) ?: emptyList<Any>()
        return tools.mapNotNull { raw ->
            val toolMap = deepStringMap(raw) ?: return@mapNotNull null
            val name = toolMap["name"]?.toString()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            val inputSchema = deepStringMap(toolMap["inputSchema"])
                ?: deepStringMap(toolMap["parameters"])
                ?: emptyMap()
            if (sessions[config.id]?.protocolEra == ProtocolEra.MODERN) {
                val bindings = runCatching { collectModernHeaderBindings(inputSchema) }
                    .onFailure { error ->
                        OmniLog.w(
                            TAG,
                            "reject modern MCP tool ${config.name}/$name: ${error.message}",
                        )
                    }
                    .getOrNull()
                    ?: return@mapNotNull null
                modernHeaderBindings[modernToolKey(config.id, name)] = bindings
            }
            RemoteMcpToolDescriptor(
                serverId = config.id,
                serverName = config.name,
                toolName = name,
                description = toolMap["description"]?.toString()?.trim().orEmpty(),
                inputSchema = inputSchema,
            )
        }
    }

    suspend fun callTool(
        config: RemoteMcpServerConfig,
        toolName: String,
        arguments: Map<String, Any?>,
        meta: Map<String, Any?> = emptyMap()
    ): RemoteMcpCallResult = try {
        callToolOnce(config, toolName, arguments, meta)
    } catch (error: HttpStatusException) {
        val hasStatefulSession = sessions[config.id]?.sessionId != null
        if (error.code !in setOf(401, 403) && !(error.code == 404 && hasStatefulSession)) {
            throw error
        }
        // The local OmniLink gateway may restart independently of Omnibot.
        // Drop the stale MCP session and perform one bounded re-initialize so
        // background plugin polling recovers without user interaction.
        invalidateSession(config.id)
        callToolOnce(config, toolName, arguments, meta)
    } catch (error: RpcErrorException) {
        if (error.code != HEADER_MISMATCH_ERROR ||
            sessions[config.id]?.protocolEra != ProtocolEra.MODERN
        ) {
            throw error
        }
        // A modern server can change an x-mcp-header annotation after the
        // client listed its tools. The protocol recovery is one fresh list
        // followed by one retry with the updated mirrored headers.
        modernHeaderBindings.remove(modernToolKey(config.id, toolName))
        val refreshedTools = parseToolDescriptors(
            config,
            callModernJsonRpc(config, "tools/list", emptyMap()),
        )
        require(refreshedTools.any { it.toolName == toolName }) {
            "MCP tool is no longer available after refreshing its schema: $toolName"
        }
        callToolOnce(config, toolName, arguments, meta)
    }

    private suspend fun callToolOnce(
        config: RemoteMcpServerConfig,
        toolName: String,
        arguments: Map<String, Any?>,
        meta: Map<String, Any?>,
    ): RemoteMcpCallResult {
        val params = buildMap<String, Any?> {
            put("name", toolName)
            put("arguments", arguments)
            // MCP request metadata is carried in the reserved `_meta` member.
            // Using `meta` happens to work for tools that do not inspect
            // metadata, but drops idempotency and correlation keys at the
            // Kotlin SDK boundary. OmniLink peer/control calls must receive
            // those keys to remain retry-safe.
            if (meta.isNotEmpty()) put("_meta", meta)
        }
        val result = if (usesSseTransport(config)) {
            callSseMethodWithInitialize(
                config = config,
                method = "tools/call",
                params = params,
            )
        } else if (useModernProtocol(config)) {
            if (!modernHeaderBindings.containsKey(modernToolKey(config.id, toolName))) {
                parseToolDescriptors(
                    config,
                    callModernJsonRpc(config, "tools/list", emptyMap()),
                )
            }
            callModernJsonRpc(
                config = config,
                method = "tools/call",
                params = params,
                name = toolName,
            )
        } else {
            initialize(config)
            if (usesSseTransport(config)) {
                callSseMethodWithInitialize(
                    config = config,
                    method = "tools/call",
                    params = params,
                )
            } else {
                callJsonRpc(
                    config = config,
                    method = "tools/call",
                    params = params
                )
            }
        }
        val rawJson = gson.toJson(result)
        return RemoteMcpCallResult(
            summaryText = buildSummaryText(result),
            previewJson = buildPreviewJson(result),
            rawResultJson = rawJson,
            success = !(deepStringMap(result)?.get("isError") == true)
        )
    }

    fun invalidateSession(serverId: String? = null) {
        if (serverId == null) {
            sessions.clear()
            initializationLocks.clear()
            protocolProbeLocks.clear()
            modernHeaderBindings.clear()
            return
        }
        sessions.remove(serverId)
        initializationLocks.remove(serverId)
        protocolProbeLocks.remove(serverId)
        modernHeaderBindings.keys.removeAll { it.startsWith("$serverId\u0000") }
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
        requireMatchingResponseId(responseMap, requestId)
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
        if (usesSseTransport(config)) {
            return executeSseRpc(config, payload, requestId, expectResponse)
        }
        return runCatching {
            executeHttpRpc(config, config.endpointUrl, payload, requestId, expectResponse)
        }.getOrElse { throwable ->
            if (shouldTryLegacySseFallback(config, throwable)) {
                val response = executeSseRpc(config, payload, requestId, expectResponse)
                updateSession(
                    serverId = config.id,
                    effectiveTransport = RemoteMcpTransport.SSE,
                )
                return response
            }
            throw throwable
        }
    }

    private suspend fun executeHttpJson(
        config: RemoteMcpServerConfig,
        url: String,
        payload: String,
    ): HttpJsonResponse = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        applyMcpSessionHeaders(config, requestBuilder)
        applyConfiguredHeaders(config, requestBuilder)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty().trim()
            val contentType = response.header("Content-Type")
            val sessionId = response.header(SESSION_ID_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
            updateSession(config.id, sessionId = sessionId)
            if (!response.isSuccessful) {
                throw HttpStatusException(
                    code = response.code,
                    responseBody = responseBody,
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

    private suspend fun closeLegacyHttpSession(
        config: RemoteMcpServerConfig,
    ) = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(config.endpointUrl)
            .delete()
            .header("Accept", "application/json, text/event-stream")
        applyMcpSessionHeaders(config, requestBuilder)
        applyConfiguredHeaders(config, requestBuilder)
        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code !in setOf(404, 405)) {
                val body = response.body?.string().orEmpty()
                throw HttpStatusException(
                    code = response.code,
                    responseBody = body,
                    message = "HTTP ${response.code}: ${response.message}",
                )
            }
        }
    }

    private suspend fun executeHttpRpc(
        config: RemoteMcpServerConfig,
        url: String,
        payload: String,
        requestId: String,
        expectResponse: Boolean,
    ): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        applyMcpSessionHeaders(config, requestBuilder)
        applyConfiguredHeaders(config, requestBuilder)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val contentType = response.header("Content-Type")
            val sessionId = response.header(SESSION_ID_HEADER)?.trim()?.takeIf { it.isNotEmpty() }
            updateSession(config.id, sessionId = sessionId)

            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw HttpStatusException(
                    code = response.code,
                    responseBody = errorBody,
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
        val sseRequestBuilder = Request.Builder()
            .url(config.endpointUrl)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        applyConfiguredHeaders(config, sseRequestBuilder)

        client.newCall(sseRequestBuilder.build()).execute().use { sseResponse ->
            if (!sseResponse.isSuccessful) {
                val errorBody = sseResponse.body?.string().orEmpty()
                throw HttpStatusException(
                    code = sseResponse.code,
                    responseBody = errorBody,
                    message = "HTTP ${sseResponse.code}: ${sseResponse.message}",
                )
            }
            val reader = sseResponse.body?.charStream()?.buffered()
                ?: throw IllegalStateException("SSE response body is empty")
            val endpointData = readEndpointEvent(reader)
            val messageUrl = resolveAgainstBase(config.endpointUrl, endpointData)

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
        val sseRequestBuilder = Request.Builder()
            .url(config.endpointUrl)
            .get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        applyConfiguredHeaders(config, sseRequestBuilder)

        client.newCall(sseRequestBuilder.build()).execute().use { sseResponse ->
            if (!sseResponse.isSuccessful) {
                val errorBody = sseResponse.body?.string().orEmpty()
                throw HttpStatusException(
                    code = sseResponse.code,
                    responseBody = errorBody,
                    message = "HTTP ${sseResponse.code}: ${sseResponse.message}",
                )
            }
            val reader = sseResponse.body?.charStream()?.buffered()
                ?: throw IllegalStateException("SSE response body is empty")
            val endpointData = readEndpointEvent(reader)
            val messageUrl = resolveAgainstBase(config.endpointUrl, endpointData)

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
        initialized: Boolean? = null,
        initializeResult: Map<String, Any?>? = null,
        effectiveTransport: RemoteMcpTransport? = null,
        protocolEra: ProtocolEra? = null,
    ) {
        if (
            sessionId == null &&
            protocolVersion == null &&
            initialized == null &&
            initializeResult == null &&
            effectiveTransport == null
            && protocolEra == null
        ) return
        sessions.compute(serverId) { _, current ->
            RemoteMcpSession(
                sessionId = sessionId ?: current?.sessionId,
                protocolVersion = protocolVersion ?: current?.protocolVersion ?: DEFAULT_PROTOCOL_VERSION,
                initialized = initialized ?: current?.initialized ?: false,
                initializeResult = initializeResult ?: current?.initializeResult.orEmpty(),
                effectiveTransport = effectiveTransport ?: current?.effectiveTransport,
                protocolEra = protocolEra ?: current?.protocolEra ?: ProtocolEra.UNKNOWN,
            )
        }
    }

    private fun shouldTryLegacySseFallback(
        config: RemoteMcpServerConfig,
        throwable: Throwable,
    ): Boolean {
        if (config.transport != RemoteMcpTransport.AUTO) return false
        if (throwable !is HttpStatusException) return false
        if (sessions[config.id]?.initialized == true) return false
        if (throwable.code !in setOf(404, 405)) return false
        return !isRecognizedModernError(throwable.responseBody)
    }

    private fun usesSseTransport(config: RemoteMcpServerConfig): Boolean {
        return config.transport == RemoteMcpTransport.SSE ||
            sessions[config.id]?.effectiveTransport == RemoteMcpTransport.SSE ||
            (
                config.transport == RemoteMcpTransport.AUTO &&
                    looksLikeSseEndpoint(config.endpointUrl)
                )
    }

    private fun applyConfiguredHeaders(
        config: RemoteMcpServerConfig,
        requestBuilder: Request.Builder,
    ) {
        config.headers.forEach { (name, value) ->
            val normalizedName = name.lowercase()
            if (name.isNotBlank() &&
                normalizedName !in RESERVED_TRANSPORT_HEADERS &&
                !normalizedName.startsWith("mcp-param-")
            ) {
                requestBuilder.header(name, value)
            }
        }
        val hasAuthorization = config.headers.keys.any {
            it.equals("Authorization", ignoreCase = true)
        }
        if (!hasAuthorization && config.bearerToken.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.bearerToken.trim()}")
        }
    }

    private suspend fun useModernProtocol(config: RemoteMcpServerConfig): Boolean {
        if (usesSseTransport(config)) return false
        when (sessions[config.id]?.protocolEra) {
            ProtocolEra.MODERN -> return true
            ProtocolEra.LEGACY -> return false
            else -> Unit
        }
        val lock = protocolProbeLocks.getOrPut(config.id) { Mutex() }
        return lock.withLock {
            when (sessions[config.id]?.protocolEra) {
                ProtocolEra.MODERN -> return@withLock true
                ProtocolEra.LEGACY -> return@withLock false
                else -> Unit
            }
            try {
                val result = callModernJsonRpc(config, "server/discover", emptyMap())
                val supported = (deepStringMap(result)?.get("supportedVersions") as? List<*>)
                    .orEmpty()
                    .map(Any?::toString)
                if (MODERN_PROTOCOL_VERSION !in supported) {
                    updateSession(config.id, protocolEra = ProtocolEra.LEGACY)
                    false
                } else {
                    updateSession(
                        serverId = config.id,
                        protocolVersion = MODERN_PROTOCOL_VERSION,
                        protocolEra = ProtocolEra.MODERN,
                    )
                    true
                }
            } catch (error: Throwable) {
                if (!isLegacyModernProbeResponse(error)) throw error
                OmniLog.i(
                    TAG,
                    "Modern MCP discovery unavailable for ${config.name}; " +
                        "falling back to legacy initialize (${probeFailureKind(error)})",
                )
                updateSession(config.id, protocolEra = ProtocolEra.LEGACY)
                false
            }
        }
    }

    private suspend fun callModernJsonRpc(
        config: RemoteMcpServerConfig,
        method: String,
        params: Map<String, Any?>,
        name: String? = null,
    ): Any? {
        val requestId = UUID.randomUUID().toString()
        val requestParams = LinkedHashMap(params)
        val callerMeta = deepStringMap(requestParams["_meta"]).orEmpty()
        requestParams["_meta"] = callerMeta + mapOf(
            "io.modelcontextprotocol/protocolVersion" to MODERN_PROTOCOL_VERSION,
            "io.modelcontextprotocol/clientInfo" to mapOf(
                "name" to "omnibot-android",
                "version" to "1.0",
            ),
            "io.modelcontextprotocol/clientCapabilities" to emptyMap<String, Any?>(),
        )
        val payload = gson.toJson(
            mapOf(
                "jsonrpc" to "2.0",
                "id" to requestId,
                "method" to method,
                "params" to requestParams,
            )
        )
        val responseBody = executeModernHttpRpc(
            config = config,
            payload = payload,
            requestId = requestId,
            method = method,
            name = name,
            mirroredHeaders = if (method == "tools/call" && name != null) {
                buildModernParameterHeaders(
                    configId = config.id,
                    toolName = name,
                    arguments = deepStringMap(requestParams["arguments"]).orEmpty(),
                )
            } else {
                emptyMap()
            },
        )
        val responseMap = parseJsonMap(responseBody)
        requireMatchingResponseId(responseMap, requestId)
        val error = deepStringMap(responseMap["error"])
        if (error != null) {
            throw RpcErrorException(
                code = (error["code"] as? Number)?.toInt()
                    ?: error["code"]?.toString()?.toIntOrNull()
                    ?: -32603,
                data = deepStringMap(error["data"]),
                message = error["message"]?.toString() ?: "Unknown MCP error",
            )
        }
        val result = responseMap["result"]
        val resultType = deepStringMap(result)?.get("resultType")?.toString()
        when (resultType) {
            "complete" -> Unit
            "input_required" -> throw IllegalStateException(
                "MCP server requires a multi-round-trip input that this Agent cannot provide yet"
            )
            else -> throw IllegalStateException(
                "Modern MCP response has an unsupported resultType: ${resultType ?: "missing"}"
            )
        }
        return result
    }

    private suspend fun executeModernHttpRpc(
        config: RemoteMcpServerConfig,
        payload: String,
        requestId: String,
        method: String,
        name: String?,
        mirroredHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder()
            .url(config.endpointUrl)
            .post(payload.toRequestBody(jsonMediaType))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header(PROTOCOL_VERSION_HEADER, MODERN_PROTOCOL_VERSION)
            .header("Mcp-Method", method)
        if (name != null) requestBuilder.header("Mcp-Name", encodeMcpHeaderValue(name))
        mirroredHeaders.forEach { (headerName, value) ->
            requestBuilder.header(headerName, value)
        }
        applyConfiguredHeaders(config, requestBuilder)

        client.newCall(requestBuilder.build()).execute().use { response ->
            val contentType = response.header("Content-Type")
            val responseBody = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful) {
                if (response.code == 400 && isJsonRpcErrorForRequest(responseBody, requestId)) {
                    return@withContext responseBody
                }
                throw HttpStatusException(
                    code = response.code,
                    responseBody = responseBody,
                    message = "HTTP ${response.code}: ${response.message}",
                )
            }
            if (responseBody.isBlank()) return@withContext "{}"
            if (isEventStream(contentType) || looksLikeSseBody(responseBody)) {
                return@withContext parseSseJsonResponseBody(responseBody, requestId)
            }
            responseBody
        }
    }

    private fun isLegacyModernProbeResponse(error: Throwable): Boolean {
        return when (error) {
            // Discovery is the protocol-era probe. A JSON-RPC error proves
            // that an MCP endpoint answered, but not that it implements the
            // modern discovery contract. Only modern request/header errors
            // should stop instead of trying the legacy initialize handshake.
            is RpcErrorException -> error.code !in MODERN_REQUEST_ERROR_CODES
            is HttpStatusException -> {
                if (error.code !in setOf(400, 404, 405)) return false
                val modernCode = modernErrorCode(error.responseBody)
                modernCode == null || modernCode !in MODERN_REQUEST_ERROR_CODES
            }
            else -> false
        }
    }

    private fun probeFailureKind(error: Throwable): String {
        return when (error) {
            is RpcErrorException -> "rpc=${error.code}"
            is HttpStatusException -> "http=${error.code}"
            else -> error::class.java.simpleName
        }
    }

    private fun isRecognizedModernError(body: String): Boolean {
        val code = modernErrorCode(body) ?: return false
        return code in setOf(-32020, -32021, -32022, -32601)
    }

    private fun modernErrorCode(body: String): Int? {
        val error = runCatching {
            deepStringMap(parseJsonMap(body)["error"])
        }.getOrNull() ?: return null
        return (error["code"] as? Number)?.toInt()
            ?: error["code"]?.toString()?.toIntOrNull()
    }

    private fun isJsonRpcErrorForRequest(body: String, requestId: String): Boolean {
        val response = runCatching { parseJsonMap(body) }.getOrNull() ?: return false
        if (deepStringMap(response["error"]) == null) return false
        return response["id"]?.toString() == requestId
    }

    private fun requireMatchingResponseId(
        response: Map<String, Any?>,
        requestId: String,
    ) {
        require(response["id"]?.toString() == requestId) {
            "MCP response id does not match request id"
        }
    }

    internal fun encodeMcpHeaderValue(value: String): String {
        val safe = value == value.trim() &&
            value.all { character -> character.code in 0x20..0x7e } &&
            !(value.startsWith("=?base64?") && value.endsWith("?="))
        if (safe) return value
        val encoded = java.util.Base64.getEncoder()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
        return "=?base64?$encoded?="
    }

    private fun collectModernHeaderBindings(
        schema: Map<String, Any?>,
    ): List<ModernHeaderBinding> {
        val bindings = mutableListOf<ModernHeaderBinding>()
        val usedNames = linkedSetOf<String>()

        fun rejectNestedAnnotation(value: Any?) {
            when (value) {
                is Map<*, *> -> {
                    require("x-mcp-header" !in value.keys.map(Any?::toString)) {
                        "x-mcp-header is only valid on schemas reached through properties"
                    }
                    value.values.forEach(::rejectNestedAnnotation)
                }
                is Iterable<*> -> value.forEach(::rejectNestedAnnotation)
            }
        }

        fun visit(node: Map<String, Any?>, path: List<String>) {
            val annotation = node["x-mcp-header"]?.toString()
            if (annotation != null) {
                require(path.isNotEmpty()) { "x-mcp-header cannot annotate the schema root" }
                require(annotation.isNotEmpty() && HTTP_TOKEN.matches(annotation)) {
                    "invalid x-mcp-header name: $annotation"
                }
                require(usedNames.add(annotation.lowercase())) {
                    "duplicate x-mcp-header name: $annotation"
                }
                val type = node["type"]?.toString().orEmpty()
                require(type == "string" || type == "integer" || type == "boolean") {
                    "x-mcp-header parameter must be string, integer, or boolean"
                }
                bindings += ModernHeaderBinding(annotation, path, type)
            }

            val properties = deepStringMap(node["properties"]).orEmpty()
            properties.forEach { (name, child) ->
                val childSchema = deepStringMap(child) ?: return@forEach
                visit(childSchema, path + name)
            }
            node.forEach { (key, value) ->
                if (key != "properties" && key != "x-mcp-header") {
                    rejectNestedAnnotation(value)
                }
            }
        }

        visit(schema, emptyList())
        return bindings
    }

    private fun buildModernParameterHeaders(
        configId: String,
        toolName: String,
        arguments: Map<String, Any?>,
    ): Map<String, String> {
        val bindings = modernHeaderBindings[modernToolKey(configId, toolName)].orEmpty()
        return buildMap {
            bindings.forEach { binding ->
                var current: Any? = arguments
                binding.path.forEach { segment ->
                    current = deepStringMap(current)?.get(segment)
                }
                if (current == null) return@forEach
                val raw = when (binding.type) {
                    "string" -> current as? String
                        ?: throw IllegalArgumentException(
                            "MCP header parameter ${binding.path.joinToString(".")} must be string"
                        )
                    "boolean" -> (current as? Boolean)?.toString()
                        ?: throw IllegalArgumentException(
                            "MCP header parameter ${binding.path.joinToString(".")} must be boolean"
                        )
                    "integer" -> modernIntegerHeaderValue(current, binding.path)
                    else -> error("Unsupported MCP header parameter type: ${binding.type}")
                }
                put("Mcp-Param-${binding.name}", encodeMcpHeaderValue(raw))
            }
        }
    }

    private fun modernIntegerHeaderValue(value: Any, path: List<String>): String {
        val number = value as? Number ?: throw IllegalArgumentException(
            "MCP header parameter ${path.joinToString(".")} must be integer"
        )
        val asDouble = number.toDouble()
        require(asDouble.isFinite() && asDouble % 1.0 == 0.0) {
            "MCP header parameter ${path.joinToString(".")} must be integer"
        }
        require(kotlin.math.abs(asDouble) <= MAX_SAFE_INTEGER.toDouble()) {
            "MCP header parameter ${path.joinToString(".")} exceeds the safe integer range"
        }
        return asDouble.toLong().toString()
    }

    private fun modernToolKey(configId: String, toolName: String): String =
        "$configId\u0000$toolName"

    private val RESERVED_TRANSPORT_HEADERS = setOf(
        "host",
        "content-length",
        "content-type",
        "accept",
        "mcp-protocol-version",
        "mcp-session-id",
        "mcp-method",
        "mcp-name",
    )
    private val HTTP_TOKEN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val MODERN_REQUEST_ERROR_CODES = setOf(-32020, -32021)
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val HEADER_MISMATCH_ERROR = -32020

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

    private fun resolveAgainstBase(baseUrl: String, value: String): String {
        value.toHttpUrlOrNull()?.let { return it.toString() }
        val base = baseUrl.toHttpUrlOrNull()
            ?: throw IllegalStateException("Invalid base endpoint: $baseUrl")
        return base.resolve(value)?.toString()
            ?: throw IllegalStateException("Unable to resolve endpoint '$value' from '$baseUrl'")
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
            return textBlocks.joinToString("\n")
        }
        return gson.toJson(result)
    }

    private fun buildPreviewJson(result: Any?): String {
        return gson.toJson(result)
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
