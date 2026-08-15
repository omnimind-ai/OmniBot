package cn.com.omnimind.bot.mcp

import cn.com.omnimind.bot.webchat.AgentRunService
import cn.com.omnimind.bot.webchat.BrowserMirrorService
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.bot.webchat.RealtimeHub
import cn.com.omnimind.bot.webchat.WebChatAvatarService
import cn.com.omnimind.bot.webchat.WorkspaceFileService
import com.google.gson.Gson
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.*
import kotlinx.coroutines.flow.collect

/**
 * WebChat API 路由注册。
 *
 * 从 McpServerManager 拆分而来，包含对话管理、事件流、工作区文件、浏览器镜像等路由。
 */
object WebChatRoutes {

    private val gson by lazy { Gson() }

    /** Serialize heterogeneous WebChat payloads explicitly instead of relying on Ktor's map serializer. */
    private suspend fun ApplicationCall.respondJson(
        payload: Any?,
        status: HttpStatusCode = HttpStatusCode.OK
    ) {
        respondText(gson.toJson(payload), ContentType.Application.Json, status)
    }

    fun Route.registerWebChatRoutes(
        conversationService: ConversationDomainService,
        workspaceFileService: WorkspaceFileService,
        browserMirrorService: BrowserMirrorService,
        agentRunService: AgentRunService,
        webChatAvatarService: WebChatAvatarService
    ) {
        route("/webchat/api") {
            get("/bootstrap") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                call.respondJson(
                    mapOf(
                        "server" to McpServerManager.currentState().toMap(),
                        "capabilities" to mapOf(
                            "conversations" to true,
                            "streaming" to true,
                            "workspace" to true,
                            "browserMirror" to true
                        ),
                        "agentProfiles" to conversationService.listWebAgentProfiles(),
                        "routes" to mapOf(
                            "events" to "/webchat/api/events",
                            "browserFrame" to "/webchat/api/browser/frame",
                            "workspaceDownload" to "/webchat/api/workspaces/download"
                        ),
                        "workspace" to workspaceFileService.bootstrapPayload(),
                        "browser" to browserMirrorService.snapshot()
                    )
                )
            }

            get("/avatar") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val avatar = webChatAvatarService.load()
                call.response.header(HttpHeaders.CacheControl, "private, max-age=300")
                call.respondBytes(avatar.bytes, avatar.contentType)
            }

            get("/conversations") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val includeArchived = call.request.queryParameters.boolean("includeArchived", true)
                val archivedOnly = call.request.queryParameters.boolean("archivedOnly", false)
                call.respondJson(
                    conversationService.listConversationPayloads(
                        includeArchived = includeArchived,
                        archivedOnly = archivedOnly
                    )
                )
            }

            post("/conversations") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val body = call.receive<Map<String, Any?>>()
                call.respondJson(
                    conversationService.createConversation(
                        title = body["title"]?.toString() ?: "新对话",
                        mode = body["mode"]?.toString() ?: "normal",
                        summary = body["summary"]?.toString(),
                        parentConversationId = (body["parentConversationId"] as? Number)
                            ?.toLong(),
                        parentConversationMode = body["parentConversationMode"]?.toString(),
                        scheduledTaskId = body["scheduledTaskId"]?.toString(),
                        agentId = body["agentId"]?.toString()
                    ),
                    HttpStatusCode.Created
                )
            }

            patch("/conversations/{conversationId}") {
                if (!McpServerManager.requireWebChatAuth(call)) return@patch
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@patch
                }
                val body = call.receive<Map<String, Any?>>().toMutableMap()
                body["id"] = conversationId
                call.respondJson(
                    conversationService.updateConversationFromPayload(body)
                )
            }

            delete("/conversations/{conversationId}") {
                if (!McpServerManager.requireWebChatAuth(call)) return@delete
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@delete
                }
                conversationService.deleteConversation(conversationId)
                call.respondJson(mapOf("success" to true))
            }

            get("/conversations/{conversationId}/messages") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@get
                }
                val mode = call.request.queryParameters["mode"] ?: "normal"
                val finalizeInterruptedEntries = !agentRunService.hasActiveConversationRun(
                    conversationId = conversationId,
                    conversationMode = mode
                )
                call.respondJson(
                    conversationService.listConversationMessages(
                        conversationId = conversationId,
                        conversationMode = mode,
                        finalizeInterruptedEntries = finalizeInterruptedEntries
                    )
                )
            }

            post("/conversations/{conversationId}/runs") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val conversationId = call.parameters["conversationId"]?.toLongOrNull()
                if (conversationId == null || conversationId <= 0L) {
                    call.respondJson(mapOf("error" to "INVALID_CONVERSATION_ID"), HttpStatusCode.BadRequest)
                    return@post
                }
                val body = call.receive<Map<String, Any?>>()
                val accepted = runCatching {
                    agentRunService.startConversationRun(conversationId, body)
                }.getOrElse { error ->
                    call.respondJson(mapOf("error" to (error.message ?: "RUN_START_FAILED")), HttpStatusCode.Conflict)
                    return@post
                }
                call.respondJson(accepted, HttpStatusCode.Accepted)
            }

            post("/tasks/{taskId}/cancel") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val taskId = call.parameters["taskId"]?.trim().takeUnless { it.isNullOrEmpty() }
                call.respondJson(agentRunService.cancelTask(taskId))
            }

            post("/tasks/{taskId}/clarify") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val taskId = call.parameters["taskId"]?.trim().takeUnless { it.isNullOrEmpty() }
                val body = call.receive<Map<String, Any?>>()
                val reply = body["reply"]?.toString() ?: body["userInput"]?.toString().orEmpty()
                if (reply.isBlank()) {
                    call.respondJson(mapOf("error" to "EMPTY_REPLY"), HttpStatusCode.BadRequest)
                    return@post
                }
                call.respondJson(agentRunService.clarifyTask(taskId, reply))
            }

            get("/events") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.response.header(HttpHeaders.Connection, "keep-alive")
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    write(": connected\n\n")
                    flush()
                    RealtimeHub.stream().collect { event ->
                        write("id: ${event.id}\n")
                        write("event: ${event.event}\n")
                        write("data: ${gson.toJson(event.data)}\n\n")
                        flush()
                    }
                }
            }

            registerWorkspaceRoutes(workspaceFileService)
            registerBrowserRoutes(browserMirrorService)
        }
    }

    private fun Route.registerWorkspaceRoutes(
        workspaceFileService: WorkspaceFileService
    ) {
        route("/workspaces") {
            get {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val path = call.request.queryParameters["path"]
                val recursive = call.request.queryParameters.boolean("recursive", false)
                val maxDepth = call.request.queryParameters["maxDepth"]?.toIntOrNull() ?: 2
                val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
                call.respondJson(
                    if (path.isNullOrBlank()) {
                        workspaceFileService.bootstrapPayload()
                    } else {
                        workspaceFileService.list(
                            path = path,
                            recursive = recursive,
                            maxDepth = maxDepth,
                            limit = limit
                        )
                    }
                )
            }

            get("/file") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val path = call.request.queryParameters["path"]
                if (path.isNullOrBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@get
                }
                val maxChars = call.request.queryParameters["maxChars"]?.toIntOrNull() ?: 64_000
                val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
                val lineStart = call.request.queryParameters["lineStart"]?.toIntOrNull()
                val lineCount = call.request.queryParameters["lineCount"]?.toIntOrNull()
                call.respondJson(
                    workspaceFileService.readFile(
                        path = path,
                        maxChars = maxChars,
                        offset = offset,
                        lineStart = lineStart,
                        lineCount = lineCount
                    )
                )
            }

            put("/file") {
                if (!McpServerManager.requireWebChatAuth(call)) return@put
                val body = call.receive<Map<String, Any?>>()
                val path = body["path"]?.toString().orEmpty()
                if (path.isBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@put
                }
                call.respondJson(
                    workspaceFileService.writeFile(
                        path = path,
                        content = body["content"]?.toString() ?: "",
                        append = body["append"] == true
                    )
                )
            }

            post("/move") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val body = call.receive<Map<String, Any?>>()
                val sourcePath = body["sourcePath"]?.toString().orEmpty()
                val targetPath = body["targetPath"]?.toString().orEmpty()
                if (sourcePath.isBlank() || targetPath.isBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@post
                }
                call.respondJson(
                    workspaceFileService.move(
                        sourcePath = sourcePath,
                        targetPath = targetPath,
                        overwrite = body["overwrite"] == true
                    )
                )
            }

            delete("/file") {
                if (!McpServerManager.requireWebChatAuth(call)) return@delete
                val path = call.request.queryParameters["path"]
                if (path.isNullOrBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@delete
                }
                workspaceFileService.delete(
                    path = path,
                    recursive = call.request.queryParameters.boolean("recursive", false)
                )
                call.respondJson(mapOf("success" to true))
            }

            get("/download") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val path = call.request.queryParameters["path"]
                if (path.isNullOrBlank()) {
                    call.respondJson(mapOf("error" to "MISSING_PATH"), HttpStatusCode.BadRequest)
                    return@get
                }
                val (file, _) = workspaceFileService.resolveDownloadFile(path)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    io.ktor.http.ContentDisposition.Attachment.withParameter(
                        io.ktor.http.ContentDisposition.Parameters.FileName,
                        file.name
                    ).toString()
                )
                call.respondFile(file)
            }
        }
    }

    private fun Route.registerBrowserRoutes(
        browserMirrorService: BrowserMirrorService
    ) {
        route("/browser") {
            get("/snapshot") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                call.respondJson(browserMirrorService.snapshot())
            }

            get("/frame") {
                if (!McpServerManager.requireWebChatAuth(call)) return@get
                val frame = browserMirrorService.frameBytes()
                if (frame == null) {
                    call.respondJson(mapOf("error" to "BROWSER_FRAME_UNAVAILABLE"), HttpStatusCode.NotFound)
                } else {
                    call.respondBytes(frame, ContentType.Image.PNG)
                }
            }

            post("/action") {
                if (!McpServerManager.requireWebChatAuth(call)) return@post
                val body = call.receive<Map<String, Any?>>()
                call.respondJson(browserMirrorService.executeAction(body))
            }
        }
    }

    private fun io.ktor.http.Parameters.boolean(
        key: String,
        defaultValue: Boolean
    ): Boolean {
        return when (this[key]?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> defaultValue
        }
    }
}
