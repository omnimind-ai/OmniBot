package cn.com.omnimind.bot.mcp

import cn.com.omnimind.baselib.i18n.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * MCP 工具执行器
 */
object McpToolExecutors {
    private const val MAX_WAIT_TIME_MS = 120_000L
    private const val POLL_INTERVAL_MS = 500L
    private fun brandName(): String = AppLocaleManager.brandName()

    /**
     * 执行文件传输工具
     */
    suspend fun executeFileTransfer(
        args: Map<String, Any?>?
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        val action = (args?.get("action") as? String)?.trim()?.lowercase() ?: "latest"
        val fileId = args?.get("fileId") as? String
        val afterFileId = args?.get("afterFileId") as? String
        val limit = (args?.get("limit") as? Number)?.toInt()
        val timeoutMs = (args?.get("timeoutMs") as? Number)?.toLong()
            ?.coerceIn(1_000L, MAX_WAIT_TIME_MS)
            ?: MAX_WAIT_TIME_MS

        when (action) {
            "latest" -> {
                val record = McpFileInbox.latest()
                    ?: return@withContext McpResponseBuilder.buildTextResponse(
                        "No files in inbox. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                    )
                return@withContext buildFileTransferResponse(record)
            }
            "get" -> {
                if (fileId.isNullOrBlank()) {
                    return@withContext McpResponseBuilder.buildErrorText("Missing fileId")
                }
                val record = McpFileInbox.getFile(fileId)
                    ?: return@withContext McpResponseBuilder.buildErrorText("File not found: $fileId")
                return@withContext buildFileTransferResponse(record)
            }
            "list" -> {
                val records = McpFileInbox.list(limit)
                if (records.isEmpty()) {
                    return@withContext McpResponseBuilder.buildTextResponse(
                        "No files in inbox. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                    )
                }
                val itemsText = records.joinToString("\n") { record ->
                    "- id=${record.id}, name=${record.fileName}, size=${record.sizeBytes}, receivedAt=${record.createdAt}"
                }
                return@withContext mapOf(
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to "Received files:\n$itemsText"
                        )
                    ),
                    "files" to records.map { record ->
                        mapOf(
                            "id" to record.id,
                            "name" to record.fileName,
                            "mimeType" to record.mimeType,
                            "sizeBytes" to record.sizeBytes,
                            "receivedAt" to record.createdAt,
                        )
                    }
                )
            }
            "clear" -> {
                val cleared = if (!fileId.isNullOrBlank()) {
                    if (McpFileInbox.removeFile(fileId)) 1 else 0
                } else {
                    McpFileInbox.clearAll()
                }
                return@withContext McpResponseBuilder.buildTextResponse("Cleared $cleared file(s) from inbox.")
            }
            "wait" -> {
                val startTime = System.currentTimeMillis()
                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val record = McpFileInbox.latest()
                    if (record != null && (afterFileId == null || record.id != afterFileId)) {
                        return@withContext buildFileTransferResponse(record)
                    }
                    delay(POLL_INTERVAL_MS)
                }
                return@withContext McpResponseBuilder.buildTextResponse(
                    "No file received within timeout. Ask the user to share or open the file with ${brandName()}, then call file_transfer again."
                )
            }
            else -> {
                return@withContext McpResponseBuilder.buildErrorText("Unknown action: $action")
            }
        }
    }
    
    private fun buildFileTransferResponse(record: McpFileRecord): Map<String, Any?> {
        val text = buildString {
            appendLine("File is available in the OmniBot inbox.")
            appendLine("")
            appendLine("File ID: ${record.id}")
            appendLine("Name: ${record.fileName}")
            appendLine("Size: ${record.sizeBytes} bytes")
            appendLine("MIME: ${record.mimeType ?: "unknown"}")
            appendLine("ReceivedAt: ${record.createdAt}")
            appendLine("")
            appendLine("Authenticated file download is not exposed until the MCP client has a non-persistent credential transport.")
        }
        return mapOf(
            "content" to listOf(mapOf("type" to "text", "text" to text)),
            "file" to mapOf(
                "id" to record.id,
                "name" to record.fileName,
                "mimeType" to record.mimeType,
                "sizeBytes" to record.sizeBytes,
                "receivedAt" to record.createdAt,
            ),
        )
    }
}
