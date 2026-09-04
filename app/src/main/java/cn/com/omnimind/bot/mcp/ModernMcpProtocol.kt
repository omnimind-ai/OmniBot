package cn.com.omnimind.bot.mcp

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Thin protocol boundary for MCP 2026-07-28.
 *
 * The Kotlin SDK currently speaks the legacy 2025-era lifecycle.  This adapter
 * keeps the existing SDK server and tool kernel intact while adding the modern
 * discover/stateless request shape at the HTTP boundary.  Legacy requests are
 * deliberately not handled here and continue through the official SDK route.
 */
internal object ModernMcpProtocol {
    const val PROTOCOL_VERSION = "2026-07-28"
    private const val PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version"
    private const val METHOD_HEADER = "Mcp-Method"
    private const val NAME_HEADER = "Mcp-Name"
    private const val META_PROTOCOL_VERSION =
        "io.modelcontextprotocol/protocolVersion"
    private const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    private const val META_CLIENT_CAPABILITIES =
        "io.modelcontextprotocol/clientCapabilities"
    private const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"

    private val serverInfo = buildJsonObject {
        put("name", "omnibot")
        put("version", "1.0.0")
        put("title", "OmniBot")
    }

    internal fun isModernRequest(call: ApplicationCall): Boolean =
        call.request.path() == "/mcp" &&
            (
                call.request.headers[PROTOCOL_VERSION_HEADER] == PROTOCOL_VERSION ||
                    call.request.headers[METHOD_HEADER] != null
                )

    internal suspend fun handle(
        call: ApplicationCall,
        context: Context,
        scope: CoroutineScope,
    ) {
        call.response.header(PROTOCOL_VERSION_HEADER, PROTOCOL_VERSION)
        val request = runCatching {
            McpJson.parseToJsonElement(call.receiveText()).jsonObject
        }.getOrElse {
            respondError(call, null, -32700, "Parse error")
            return
        }
        val id = request["id"] ?: JsonNull
        val method = call.request.headers[METHOD_HEADER]
        val bodyMethod = (request["method"] as? JsonPrimitive)?.contentOrNull
        if (method.isNullOrBlank() || bodyMethod != method) {
            respondError(
                call,
                id,
                -32020,
                "Mcp-Method header does not match request method",
                status = HttpStatusCode.BadRequest,
            )
            return
        }
        if (call.request.headers[PROTOCOL_VERSION_HEADER] != PROTOCOL_VERSION) {
            val requested = call.request.headers[PROTOCOL_VERSION_HEADER]
                ?.takeIf(String::isNotBlank)
                ?: "unknown"
            respondError(
                call,
                id,
                -32022,
                "Unsupported MCP protocol version",
                status = HttpStatusCode.BadRequest,
                data = buildJsonObject {
                    putJsonArray("supported") { add(JsonPrimitive(PROTOCOL_VERSION)) }
                    put("requested", requested)
                },
            )
            return
        }
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        // MCP 2026 request metadata is scoped to params, matching RequestParams
        // in the official schema. It is not a JSON-RPC envelope field.
        val meta = params["_meta"] as? JsonObject
        val metaVersion = (meta?.get(META_PROTOCOL_VERSION) as? JsonPrimitive)?.contentOrNull
        if (metaVersion != PROTOCOL_VERSION) {
            respondError(
                call,
                id,
                -32020,
                "Request _meta protocolVersion does not match header",
                status = HttpStatusCode.BadRequest,
            )
            return
        }
        // clientInfo is SHOULD/optional; clientCapabilities is required on
        // every modern request and must not be inferred from an earlier call.
        if (meta[META_CLIENT_CAPABILITIES] !is JsonObject) {
            respondError(
                call,
                id,
                -32021,
                "Modern MCP request metadata is incomplete",
                status = HttpStatusCode.BadRequest,
            )
            return
        }
        val clientInfo = meta[META_CLIENT_INFO]
        if (clientInfo != null && !isValidImplementation(clientInfo)) {
            respondError(
                call,
                id,
                -32602,
                "Modern MCP clientInfo is invalid",
                status = HttpStatusCode.BadRequest,
            )
            return
        }

        when (method) {
            "server/discover" -> respondResult(
                call,
                id,
                buildJsonObject {
                    put("resultType", "complete")
                    put("ttlMs", 0)
                    put("cacheScope", "private")
                    putJsonArray("supportedVersions") {
                        add(JsonPrimitive(PROTOCOL_VERSION))
                    }
                    putJsonObject("capabilities") {
                        putJsonObject("tools") { put("listChanged", false) }
                    }
                    put(
                        "instructions",
                        AndroidDeviceMcpServer.MCP_INSTRUCTIONS,
                    )
                    putJsonObject("_meta") {
                        put(META_SERVER_INFO, serverInfo)
                    }
                },
            )

            "tools/list" -> {
                val tools = AndroidDeviceMcpServer.modernToolDescriptors(context, scope)
                respondResult(
                    call,
                    id,
                    buildJsonObject {
                        put("resultType", "complete")
                        put("ttlMs", 0)
                        put("cacheScope", "private")
                        putJsonArray("tools") {
                            tools.forEach { add(toolToJson(it)) }
                        }
                        putJsonObject("_meta") { put(META_SERVER_INFO, serverInfo) }
                    },
                )
            }

            "tools/call" -> {
                val toolName = (params["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val headerName = call.request.headers[NAME_HEADER]
                    ?.let(::decodeMcpHeaderValue)
                if (toolName.isBlank() || headerName != toolName) {
                    respondError(
                        call,
                        id,
                        -32020,
                        "Mcp-Name header does not match tool name",
                        status = HttpStatusCode.BadRequest,
                    )
                    return
                }
                val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val result = AndroidDeviceMcpServer.modernCallTool(
                    context = context,
                    scope = scope,
                    name = toolName,
                    arguments = arguments,
                )
                val resultJson = McpJson
                    .encodeToJsonElement(CallToolResult.serializer(), result)
                    .jsonObject
                respondResult(
                    call,
                    id,
                    buildJsonObject {
                        put("resultType", "complete")
                        resultJson.forEach { (key, value) -> put(key, value) }
                        putJsonObject("_meta") {
                            (resultJson["_meta"] as? JsonObject)?.forEach { (key, value) ->
                                put(key, value)
                            }
                            put(META_SERVER_INFO, serverInfo)
                        }
                    },
                )
            }

            else -> respondError(
                call,
                id,
                -32601,
                "Method not found: $method",
                status = HttpStatusCode.NotFound,
            )
        }
    }

    private fun toolToJson(tool: AndroidDeviceMcpServer.DeviceTool): JsonObject =
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            putJsonObject("inputSchema") {
                put("type", "object")
                put("properties", JsonObject(tool.properties))
                if (tool.required.isNotEmpty()) {
                    putJsonArray("required") {
                        tool.required.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
        }

    private fun isValidImplementation(value: JsonElement): Boolean {
        val implementation = value as? JsonObject ?: return false
        return (implementation["name"] as? JsonPrimitive)
            ?.contentOrNull
            ?.isNotBlank() == true &&
            (implementation["version"] as? JsonPrimitive)
                ?.contentOrNull
                ?.isNotBlank() == true
    }

    private suspend fun respondResult(
        call: ApplicationCall,
        id: JsonElement,
        result: JsonObject,
    ) {
        call.respondText(
            text = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            }.toString(),
            contentType = ContentType.Application.Json,
            status = HttpStatusCode.OK,
        )
    }

    private suspend fun respondError(
        call: ApplicationCall,
        id: JsonElement?,
        code: Int,
        message: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        data: JsonObject? = null,
    ) {
        call.respondText(
            text = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                    data?.let { put("data", it) }
                }
            }.toString(),
            contentType = ContentType.Application.Json,
            status = status,
        )
    }

    /** Decode the header-safe sentinel required for non-ASCII MCP names. */
    internal fun decodeMcpHeaderValue(value: String): String? {
        if (!value.startsWith(BASE64_PREFIX) || !value.endsWith(BASE64_SUFFIX)) {
            return value.takeIf { raw ->
                raw == raw.trim() && raw.all { char -> char.code in 0x20..0x7e }
            }
        }
        val encoded = value.substring(
            BASE64_PREFIX.length,
            value.length - BASE64_SUFFIX.length,
        )
        return runCatching {
            val bytes = Base64.getDecoder().decode(encoded)
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private const val BASE64_PREFIX = "=?base64?"
    private const val BASE64_SUFFIX = "?="

}
