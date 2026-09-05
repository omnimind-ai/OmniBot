package cn.com.omnimind.bot.agent.tool.handlers

import cn.com.omnimind.bot.agent.*
import cn.com.omnimind.bot.agent.AgentCallback
import cn.com.omnimind.bot.agent.AgentExecutionEnvironment
import cn.com.omnimind.bot.agent.AgentToolExecutionHandle
import cn.com.omnimind.bot.agent.AgentToolRegistry
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Base64
import java.util.Locale

internal fun decodeImageWriteContentForFileName(fileName: String, content: String): ByteArray? {
    if (!isBinaryImageFileName(fileName)) {
        return null
    }
    val trimmed = content.trim()
    if (trimmed.isBlank()) {
        return null
    }
    val encoded = when {
        trimmed.startsWith("data:", ignoreCase = true) -> {
            val commaIndex = trimmed.indexOf(',')
            if (commaIndex <= 0) return null
            val header = trimmed.substring(0, commaIndex)
            if (!header.contains(";base64", ignoreCase = true)) return null
            trimmed.substring(commaIndex + 1)
        }
        trimmed.startsWith("base64:", ignoreCase = true) -> trimmed.substringAfter(':')
        else -> trimmed
    }.filterNot { it.isWhitespace() }
    if (encoded.length < 16 || !encoded.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) {
        return null
    }
    val padded = encoded + "=".repeat((4 - encoded.length % 4) % 4)
    val bytes = runCatching { Base64.getDecoder().decode(padded) }
        .recoverCatching { Base64.getUrlDecoder().decode(padded) }
        .getOrNull()
        ?: return null
    return bytes.takeIf { bytesMatchImageExtension(fileName, it) }
}

internal fun normalizeSvgWriteContentForFileName(fileName: String, content: String): String {
    if (!fileName.endsWith(".svg", ignoreCase = true)) {
        return content
    }
    val trimmed = content.trim()
    val svgStart = trimmed.indexOf("<svg", ignoreCase = true)
    val svgEnd = trimmed.lastIndexOf("</svg>", ignoreCase = true)
    if (svgStart < 0 || svgEnd < svgStart) {
        return content
    }
    return inlineSimpleSvgClassStyles(trimmed.substring(svgStart, svgEnd + "</svg>".length))
}

private fun inlineSimpleSvgClassStyles(svg: String): String {
    val classStyles = mutableMapOf<String, Map<String, String>>()
    val styleRegex = Regex("""<style\b[^>]*>([\s\S]*?)</style>""", RegexOption.IGNORE_CASE)
    val classRuleRegex = Regex("""\.([A-Za-z_][A-Za-z0-9_-]*)\s*\{([^}]*)}""")
    styleRegex.findAll(svg).forEach { styleMatch ->
        classRuleRegex.findAll(styleMatch.groups[1]?.value.orEmpty()).forEach { ruleMatch ->
            val className = ruleMatch.groups[1]?.value.orEmpty()
            val declarations = ruleMatch.groups[2]?.value.orEmpty()
                .split(';')
                .mapNotNull { declaration ->
                    val parts = declaration.split(':', limit = 2)
                    if (parts.size != 2) return@mapNotNull null
                    val property = parts[0].trim().lowercase(Locale.US)
                    val value = parts[1].trim()
                    if (property.isBlank() || value.isBlank()) null else property to value
                }
                .toMap()
            if (className.isNotBlank() && declarations.isNotEmpty()) {
                classStyles[className] = declarations
            }
        }
    }
    if (classStyles.isEmpty()) {
        return svg
    }

    val withoutStyleBlocks = svg.replace(styleRegex, "")
    val elementWithClassRegex = Regex("""<([A-Za-z][A-Za-z0-9:_-]*)([^<>]*?)\sclass=(["'])([^"']+)\3([^<>]*?)(/?)>""")
    return withoutStyleBlocks.replace(elementWithClassRegex) { match ->
        val tagName = match.groups[1]?.value.orEmpty()
        val beforeClass = match.groups[2]?.value.orEmpty()
        val classNames = match.groups[4]?.value.orEmpty().split(Regex("""\s+"""))
        val afterClass = match.groups[5]?.value.orEmpty()
        val selfClosing = match.groups[6]?.value.orEmpty()
        val declarations = linkedMapOf<String, String>()
        classNames.forEach { className ->
            classStyles[className]?.forEach { (property, value) ->
                declarations[property] = value
            }
        }
        if (declarations.isEmpty()) {
            return@replace match.value
        }
        val rawAttributes = "$beforeClass$afterClass"
        val attributeRegex = Regex("""\s([A-Za-z_:][A-Za-z0-9:_.-]*)=(["'])(.*?)\2""")
        val existingAttributes = attributeRegex.findAll(rawAttributes)
            .map { it.groups[1]?.value.orEmpty().lowercase(Locale.US) }
            .toSet()
        val inlineAttributes = declarations
            .filterKeys { it !in existingAttributes }
            .entries
            .joinToString("") { (property, value) -> " $property=\"$value\"" }
        "<$tagName$rawAttributes$inlineAttributes$selfClosing>"
    }
}

private fun isBinaryImageFileName(fileName: String): Boolean {
    return when (fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> true
        else -> false
    }
}

private fun bytesMatchImageExtension(fileName: String, bytes: ByteArray): Boolean {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
    return when (extension) {
        "png" -> bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        "jpg", "jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        "webp" -> bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte()
        "gif" -> bytes.size >= 6 &&
            bytes[0] == 0x47.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x38.toByte() &&
            (bytes[4] == 0x37.toByte() || bytes[4] == 0x39.toByte()) &&
            bytes[5] == 0x61.toByte()
        "bmp" -> bytes.size >= 2 &&
            bytes[0] == 0x42.toByte() &&
            bytes[1] == 0x4D.toByte()
        else -> false
    }
}

class FileToolHandler(
    private val helper: SharedHelper,
    private val workspaceManager: AgentWorkspaceManager
) : ToolHandler {
    override val toolNames: Set<String> = setOf(
        "file_read", "file_write", "file_edit", "file_list", "file_search", "file_stat", "file_move",
        "read", "write", "edit", "glob", "grep"
    )

    override suspend fun execute(
        toolCall: cn.com.omnimind.baselib.llm.AssistantToolCall,
        args: JsonObject,
        runtimeDescriptor: AgentToolRegistry.RuntimeToolDescriptor,
        env: AgentExecutionEnvironment,
        callback: AgentCallback,
        toolHandle: AgentToolExecutionHandle
    ): ToolExecutionResult {
        return when (toolCall.function.name) {
            "file_read", "read" -> executeFileRead(
                args,
                env.workspaceDescriptor,
                callback,
            )
            "file_write", "write" -> executeFileWrite(args, env.workspaceDescriptor, callback)
            "file_edit", "edit" -> executeFileEdit(args, env.workspaceDescriptor, callback)
            "file_list", "glob" -> executeFileList(
                args,
                env.workspaceDescriptor,
                callback,
            )
            "file_search", "grep" -> executeFileSearch(
                args,
                env.workspaceDescriptor,
                callback,
            )
            "file_stat" -> executeFileStat(args, env.workspaceDescriptor, callback)
            "file_move" -> executeFileMove(args, env.workspaceDescriptor, callback)
            else -> ToolExecutionResult.Error(toolCall.function.name, "Unknown file tool")
        }
    }

    private suspend fun executeFileRead(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_read"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists()) { "文件不存在：${file.absolutePath}" }
            require(file.isFile) { "目标不是文件：${file.absolutePath}" }
            val offset = args["offset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
            val lineStart = args["lineStart"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val lineCount = args["lineCount"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val shellPath = workspaceManager.shellPathForAndroid(file) ?: file.absolutePath
            val mimeType = workspaceManager.guessMimeType(file)
            val imageReadResult = if (isImageFile(file, mimeType)) {
                AgentImageAttachmentSupport.buildFileReadImageResult(
                    file = file,
                    shellPath = shellPath,
                    mimeTypeHint = mimeType,
                    uri = artifact.uri,
                    sizeBytes = file.length()
                )
            } else {
                null
            }
            val payload = if (imageReadResult != null) {
                imageReadResult.payload
            } else {
                val content = file.readText()
                val sliced = when {
                    lineStart != null -> {
                        val lines = content.lines()
                        val from = (lineStart - 1).coerceAtMost(lines.size)
                        val until = if (lineCount != null) {
                            (from + lineCount).coerceAtMost(lines.size)
                        } else {
                            lines.size
                        }
                        lines.subList(from, until).joinToString("\n")
                    }
                    offset > 0 -> content.drop(offset)
                    else -> content
                }
                linkedMapOf<String, Any?>(
                    "path" to shellPath,
                    "androidPath" to file.absolutePath,
                    "uri" to artifact.uri,
                    "content" to sliced,
                    "size" to file.length(),
                    "mimeType" to mimeType
                )
            }
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                imageDataUrl = imageReadResult?.imageDataUrl,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "读取文件失败")
        }
    }

    private fun isImageFile(file: File, mimeType: String): Boolean {
        if (mimeType.startsWith("image/", ignoreCase = true)) {
            return true
        }
        val lowerName = file.name.lowercase()
        return lowerName.endsWith(".png") ||
            lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".gif") ||
            lowerName.endsWith(".bmp") ||
            lowerName.endsWith(".heic") ||
            lowerName.endsWith(".heif")
    }

    private suspend fun executeFileWrite(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_write"
        return try {
            val path = args["path"]?.jsonPrimitive?.content?.trim().orEmpty()
            val content = args["content"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("缺少 content")
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                path
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在写入文件")
            val file = workspaceManager.resolvePath(
                inputPath = path,
                workspace = workspace,
                allowPublicStorage = true
            )
            val append = args["append"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content)
            } else {
                val imageBytes = decodeImageWriteContentForFileName(file.name, content)
                if (imageBytes != null) {
                    file.writeBytes(imageBytes)
                } else {
                    file.writeText(normalizeSvgWriteContentForFileName(file.name, content))
                }
            }
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "size" to file.length(),
                "append" to append
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (append) "已追加写入文件：${file.name}" else "已写入文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "写入文件失败")
        }
    }

    private suspend fun executeFileEdit(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_edit"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在编辑文件")
            val file = workspaceManager.resolvePath(
                inputPath = args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace = workspace,
                allowPublicStorage = true
            )
            require(file.exists() && file.isFile) { "目标文件不存在：${file.absolutePath}" }
            val oldText = args["oldText"]?.jsonPrimitive?.content ?: throw IllegalArgumentException("缺少 oldText")
            val newText = args["newText"]?.jsonPrimitive?.content ?: ""
            val replaceAll = args["replaceAll"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val original = file.readText()
            require(original.contains(oldText)) { "文件中未找到 oldText" }
            val updated = if (replaceAll) {
                original.replace(oldText, newText)
            } else {
                original.replaceFirst(oldText, newText)
            }
            file.writeText(updated)
            val artifact = workspaceManager.buildArtifactForFile(file, toolName)
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "uri" to artifact.uri,
                "replaceAll" to replaceAll
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已更新文件：${file.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = listOf(artifact),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "编辑文件失败")
        }
    }

    private suspend fun executeFileList(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_list"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            helper.requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val recursive = args["recursive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxDepth = args["maxDepth"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            val limit = args["limit"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            val files = if (recursive) {
                val walked = directory.walkTopDown()
                val depthLimited = if (maxDepth != null) walked.maxDepth(maxDepth) else walked
                val result = if (limit != null) depthLimited.take(limit) else depthLimited
                result.drop(1).toList()
            } else {
                val entries = directory.listFiles()?.sortedBy { it.name.lowercase() }
                    ?: emptyList()
                if (limit != null) entries.take(limit) else entries
            }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to files.size,
                "items" to files.map { entry ->
                    mapOf(
                        "name" to entry.name,
                        "path" to (workspaceManager.shellPathForAndroid(entry) ?: entry.absolutePath),
                        "androidPath" to entry.absolutePath,
                        "isDirectory" to entry.isDirectory,
                        "size" to if (entry.isFile) entry.length() else 0L
                    )
                }
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("共找到 ${files.size} 项"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id,
                actions = listOf(
                    helper.buildOpenDirectoryAction(workspaceManager, workspace, directory, "打开目录")
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "列目录失败")
        }
    }

    private suspend fun executeFileSearch(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_search"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            val query = args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
            require(query.isNotEmpty()) { "缺少 query" }
            val pathArg = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            helper.requirePublicStorageAccessIfNeeded(callback, pathArg)?.let { return it }
            val directory = if (pathArg.isBlank()) {
                File(workspace.androidRootPath)
            } else {
                workspaceManager.resolvePath(pathArg, workspace, allowPublicStorage = true)
            }
            require(directory.exists() && directory.isDirectory) { "目录不存在：${directory.absolutePath}" }
            val caseSensitive = args["caseSensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val maxResults = args["maxResults"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            val searchNeedle = if (caseSensitive) query else query.lowercase()
            val results = mutableListOf<Map<String, Any?>>()
            directory.walkTopDown().forEach { file ->
                if (maxResults != null && results.size >= maxResults) return@forEach
                if (!file.isFile) return@forEach
                val normalizedName = if (caseSensitive) file.name else file.name.lowercase()
                if (normalizedName.contains(searchNeedle)) {
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "file_name",
                            "snippet" to file.name
                        )
                    )
                    return@forEach
                }
                val matchingLine = runCatching {
                    file.bufferedReader().useLines { lines ->
                        lines.firstOrNull { line ->
                            val haystack = if (caseSensitive) line else line.lowercase()
                            haystack.contains(searchNeedle)
                        }
                    }
                }.getOrNull() ?: return@forEach
                val haystack = if (caseSensitive) matchingLine else matchingLine.lowercase()
                val index = haystack.indexOf(searchNeedle)
                if (index >= 0) {
                    val start = (index - 40).coerceAtLeast(0)
                    val end = (index + query.length + 120).coerceAtMost(matchingLine.length)
                    results.add(
                        mapOf(
                            "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                            "androidPath" to file.absolutePath,
                            "matchType" to "content",
                            "snippet" to matchingLine.substring(start, end)
                        )
                    )
                }
            }
            val payload = linkedMapOf<String, Any?>(
                "query" to query,
                "path" to (workspaceManager.shellPathForAndroid(directory) ?: directory.absolutePath),
                "androidPath" to directory.absolutePath,
                "count" to results.size,
                "items" to results
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized(if (results.isEmpty()) "未找到匹配结果" else "找到 ${results.size} 个匹配结果"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "搜索文件失败")
        }
    }

    private suspend fun executeFileStat(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_stat"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["path"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            val file = workspaceManager.resolvePath(
                args["path"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowRootDirectories = true,
                allowPublicStorage = true
            )
            require(file.exists()) { "路径不存在：${file.absolutePath}" }
            val artifact = file.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "path" to (workspaceManager.shellPathForAndroid(file) ?: file.absolutePath),
                "androidPath" to file.absolutePath,
                "name" to file.name,
                "exists" to file.exists(),
                "isDirectory" to file.isDirectory,
                "isFile" to file.isFile,
                "size" to if (file.isFile) file.length() else 0L,
                "lastModified" to file.lastModified(),
                "mimeType" to if (file.isFile) workspaceManager.guessMimeType(file) else "inode/directory",
                "uri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已读取路径信息：${file.name.ifBlank { workspaceManager.shellPathForAndroid(file) ?: file.absolutePath }}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id,
                actions = if (file.isDirectory) {
                    listOf(helper.buildOpenDirectoryAction(workspaceManager, workspace, file))
                } else {
                    emptyList()
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "查看文件信息失败")
        }
    }

    private suspend fun executeFileMove(
        args: JsonObject,
        workspace: AgentWorkspaceDescriptor,
        callback: AgentCallback
    ): ToolExecutionResult {
        val toolName = "file_move"
        return try {
            helper.requireWorkspaceStorageAccess(callback)?.let { return it }
            helper.requirePublicStorageAccessIfNeeded(
                callback,
                args["sourcePath"]?.jsonPrimitive?.contentOrNull,
                args["targetPath"]?.jsonPrimitive?.contentOrNull
            )?.let { return it }
            helper.reportToolProgress(callback, toolName, "正在移动文件")
            val source = workspaceManager.resolvePath(
                args["sourcePath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            val target = workspaceManager.resolvePath(
                args["targetPath"]?.jsonPrimitive?.content?.trim().orEmpty(),
                workspace,
                allowPublicStorage = true
            )
            require(source.exists()) { "源文件不存在：${source.absolutePath}" }
            val overwrite = args["overwrite"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            require(overwrite || !target.exists()) { "目标已存在：${target.absolutePath}" }
            target.parentFile?.mkdirs()
            if (overwrite && target.exists()) {
                target.deleteRecursively()
            }
            source.copyRecursively(target, overwrite = overwrite)
            source.deleteRecursively()
            val artifact = target.takeIf { it.isFile }?.let { workspaceManager.buildArtifactForFile(it, toolName) }
            val payload = linkedMapOf<String, Any?>(
                "sourcePath" to (workspaceManager.shellPathForAndroid(source) ?: source.absolutePath),
                "androidSourcePath" to source.absolutePath,
                "targetPath" to (workspaceManager.shellPathForAndroid(target) ?: target.absolutePath),
                "androidTargetPath" to target.absolutePath,
                "overwrite" to overwrite,
                "targetUri" to artifact?.uri
            )
            ToolExecutionResult.ContextResult(
                toolName = toolName,
                summaryText = helper.localized("已移动到：${target.name}"),
                previewJson = helper.encodeLocalizedPayload(payload),
                rawResultJson = helper.encodeLocalizedPayload(payload),
                success = true,
                artifacts = artifact?.let { listOf(it) } ?: emptyList(),
                workspaceId = workspace.id
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            helper.workspacePermissionResult(e, callback)?.let { return it }
            helper.errorResult(toolName, e.message, "移动文件失败")
        }
    }
}
