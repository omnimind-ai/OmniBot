package cn.com.omnimind.bot.terminal

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class AlpineFileSystemService(context: Context) {
    private val appContext = context.applicationContext
    private val terminalManager = TerminalManager.getInstance(appContext)

    suspend fun list(path: String): Map<String, Any?> {
        val normalizedPath = normalizeAbsolutePath(path)
        val command = buildListCommand(normalizedPath)
        val output = execute(command, "alpine-fs-list", LIST_TIMEOUT_MS)
        return mapOf(
            "path" to normalizedPath,
            "entries" to parseListOutput(output)
        )
    }

    suspend fun read(path: String, maxBytes: Int = DEFAULT_MAX_READ_BYTES): Map<String, Any?> {
        val normalizedPath = normalizeAbsolutePath(path)
        val safeLimit = maxBytes.coerceIn(1, MAX_READ_BYTES)
        val command = """
            set -eu
            target=${shellQuote(normalizedPath)}
            [ -f "${'$'}target" ]
            size=${'$'}(stat -c %s -- "${'$'}target")
            is_link=0
            writable=0
            [ -L "${'$'}target" ] && is_link=1
            [ -w "${'$'}target" ] && writable=1
            printf "%s|%s|%s\n" "${'$'}size" "${'$'}is_link" "${'$'}writable"
            head -c $safeLimit -- "${'$'}target" | base64
        """.trimIndent()
        val output = execute(command, "alpine-fs-read", READ_TIMEOUT_MS)
        val firstBreak = output.indexOf('\n')
        require(firstBreak >= 0) { "Invalid Alpine file response." }
        val metadata = output.substring(0, firstBreak).split('|', limit = 3)
        require(metadata.size == 3) { "Invalid Alpine file metadata." }
        val size = metadata[0].toLongOrNull() ?: 0L
        val isLink = metadata[1] == "1"
        val writable = metadata[2] == "1"
        val encoded = output.substring(firstBreak + 1).filterNot(Char::isWhitespace)
        val bytes = if (encoded.isEmpty()) {
            ByteArray(0)
        } else {
            Base64.getMimeDecoder().decode(encoded)
        }
        val content = decodeEditableUtf8(bytes)
        val truncated = size > safeLimit
        return mapOf(
            "path" to normalizedPath,
            "size" to size,
            "truncated" to truncated,
            "isLink" to isLink,
            "binary" to (content == null),
            "editable" to (content != null && writable && !isLink && !truncated),
            "content" to content.orEmpty()
        )
    }

    suspend fun write(path: String, content: String): Map<String, Any?> {
        val normalizedPath = normalizeMutablePath(path)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) {
            "Text files larger than $MAX_WRITE_BYTES bytes are not supported by the editor."
        }
        ensureWriteTargetEditable(normalizedPath)
        val parent = parentPath(normalizedPath)
        val transferDir = File(appContext.cacheDir, "alpine-fs-transfer").apply { mkdirs() }
        val transferFile = File(transferDir, UUID.randomUUID().toString())
        return try {
            withContext(Dispatchers.IO) {
                transferFile.writeBytes(bytes)
                transferFile.setReadable(true, false)
            }
            val command = """
                set -eu
                target=${shellQuote(normalizedPath)}
                parent=${shellQuote(parent)}
                source=${shellQuote(transferFile.absolutePath)}
                [ -r "${'$'}source" ]
                mkdir -p "${'$'}parent"
                if [ -L "${'$'}target" ]; then
                  printf "Refusing to replace a symbolic link: %s\n" "${'$'}target" >&2
                  exit 73
                fi
                temporary="${'$'}target.omnibot-tmp-${'$'}${'$'}"
                mode=""
                [ -e "${'$'}target" ] && mode=${'$'}(stat -c %a -- "${'$'}target" 2>/dev/null || true)
                cat -- "${'$'}source" > "${'$'}temporary"
                [ -n "${'$'}mode" ] && chmod "${'$'}mode" "${'$'}temporary"
                mv -f -- "${'$'}temporary" "${'$'}target"
                stat -c %s -- "${'$'}target"
            """.trimIndent()
            val output = execute(command, "alpine-fs-write", WRITE_TIMEOUT_MS)
            mapOf(
                "path" to normalizedPath,
                "size" to (
                    output.trim().lineSequence().lastOrNull()?.toLongOrNull()
                        ?: bytes.size.toLong()
                    )
            )
        } finally {
            withContext(Dispatchers.IO) {
                transferFile.delete()
            }
        }
    }

    private suspend fun ensureWriteTargetEditable(path: String) {
        val command = """
            set -eu
            target=${shellQuote(path)}
            if [ -L "${'$'}target" ]; then
              printf "Refusing to replace a symbolic link: %s\n" "${'$'}target" >&2
              exit 73
            fi
            if [ ! -e "${'$'}target" ]; then
              printf "new\n"
              exit 0
            fi
            [ -f "${'$'}target" ]
            size=${'$'}(stat -c %s -- "${'$'}target")
            if [ "${'$'}size" -gt $MAX_WRITE_BYTES ]; then
              printf "Existing file is too large for text editing: %s\n" "${'$'}target" >&2
              exit 73
            fi
            printf "existing|%s\n" "${'$'}size"
            base64 < "${'$'}target"
        """.trimIndent()
        val output = execute(command, "alpine-fs-write-check", READ_TIMEOUT_MS)
        val firstBreak = output.indexOf('\n')
        val header = if (firstBreak >= 0) output.substring(0, firstBreak) else output.trimEnd()
        if (header == "new") return
        require(header.startsWith("existing|")) { "Invalid Alpine write preflight response." }
        val encoded = if (firstBreak >= 0) {
            output.substring(firstBreak + 1).filterNot(Char::isWhitespace)
        } else {
            ""
        }
        val existingBytes = if (encoded.isEmpty()) {
            ByteArray(0)
        } else {
            Base64.getMimeDecoder().decode(encoded)
        }
        require(decodeEditableUtf8(existingBytes) != null) {
            "Existing file is not valid UTF-8 text."
        }
    }

    suspend fun createDirectory(path: String): Map<String, Any?> {
        val normalizedPath = normalizeMutablePath(path)
        execute(
            "set -eu\nmkdir -p -- ${shellQuote(normalizedPath)}",
            "alpine-fs-mkdir",
            MUTATION_TIMEOUT_MS
        )
        return mapOf("path" to normalizedPath, "isDirectory" to true)
    }

    suspend fun createFile(path: String): Map<String, Any?> {
        val normalizedPath = normalizeMutablePath(path)
        execute(
            buildCreateFileCommand(normalizedPath),
            "alpine-fs-touch",
            MUTATION_TIMEOUT_MS
        )
        return mapOf("path" to normalizedPath, "isDirectory" to false)
    }

    suspend fun move(sourcePath: String, targetPath: String): Map<String, Any?> {
        val source = normalizeMutablePath(sourcePath)
        val target = normalizeMutablePath(targetPath)
        if (source == target) {
            return mapOf("sourcePath" to source, "path" to target)
        }
        execute(
            buildMoveCommand(source, target),
            "alpine-fs-move",
            MUTATION_TIMEOUT_MS
        )
        return mapOf("sourcePath" to source, "path" to target)
    }

    suspend fun delete(path: String): Map<String, Any?> {
        val normalizedPath = normalizeMutablePath(path)
        execute(
            "set -eu\nrm -rf -- ${shellQuote(normalizedPath)}",
            "alpine-fs-delete",
            MUTATION_TIMEOUT_MS
        )
        return mapOf("path" to normalizedPath, "deleted" to true)
    }

    private suspend fun execute(command: String, executorKey: String, timeoutMs: Long): String {
        val result = terminalManager.executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = timeoutMs
        )
        if (!result.isOk || result.exitCode != 0) {
            throw IllegalStateException(
                result.error.ifBlank {
                    result.rawOutputPreview.ifBlank { "Alpine file operation failed." }
                }
            )
        }
        return result.output
    }

    companion object {
        private const val DEFAULT_MAX_READ_BYTES = 512 * 1024
        private const val MAX_READ_BYTES = 1024 * 1024
        private const val MAX_WRITE_BYTES = 1024 * 1024
        private const val LIST_TIMEOUT_MS = 30_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 45_000L
        private const val MUTATION_TIMEOUT_MS = 30_000L

        internal fun normalizeAbsolutePath(path: String): String {
            require(path.startsWith('/')) { "An absolute Alpine path is required." }
            require('\u0000' !in path && '\n' !in path && '\r' !in path) {
                "Invalid Alpine path."
            }
            val segments = ArrayDeque<String>()
            path.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.addLast(segment)
                }
            }
            return if (segments.isEmpty()) "/" else segments.joinToString(prefix = "/", separator = "/")
        }

        internal fun decodeEditableUtf8(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            return decodeStrictUtf8(bytes)
        }

        private fun decodeStrictUtf8(bytes: ByteArray): String? {
            return runCatching {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()
        }

        internal fun buildListCommand(path: String): String {
            return """
                set -eu
                target=${shellQuote(normalizeAbsolutePath(path))}
                [ -d "${'$'}target" ]
                find -H "${'$'}target" -mindepth 1 -maxdepth 1 -exec sh -c '
                  encode() { printf %s "${'$'}1" | base64 | tr -d "\n"; }
                  for item do
                    is_dir=0
                    is_file=0
                    is_link=0
                    [ -d "${'$'}item" ] && is_dir=1
                    [ -f "${'$'}item" ] && is_file=1
                    [ -L "${'$'}item" ] && is_link=1
                    size=${'$'}(stat -c %s -- "${'$'}item" 2>/dev/null || printf 0)
                    modified=${'$'}(stat -c %Y -- "${'$'}item" 2>/dev/null || printf 0)
                    mode=${'$'}(stat -c %a -- "${'$'}item" 2>/dev/null || printf "")
                    readable=0
                    writable=0
                    [ -r "${'$'}item" ] && readable=1
                    [ -w "${'$'}item" ] && writable=1
                    name=${'$'}{item##*/}
                    link_target=""
                    [ "${'$'}is_link" = 1 ] && link_target=${'$'}(readlink -- "${'$'}item" 2>/dev/null || true)
                    printf "%s|%s|%s|%s|%s|%s|%s|%s|%s|%s|%s\n" \
                      "${'$'}is_dir" "${'$'}is_file" "${'$'}is_link" "${'$'}size" \
                      "${'$'}modified" "${'$'}mode" "${'$'}readable" "${'$'}writable" \
                      "${'$'}(encode "${'$'}item")" "${'$'}(encode "${'$'}name")" \
                      "${'$'}(encode "${'$'}link_target")"
                  done
                ' sh {} +
            """.trimIndent()
        }

        internal fun buildCreateFileCommand(path: String): String {
            val target = normalizeMutablePath(path)
            return """
                set -eu
                target=${shellQuote(target)}
                mkdir -p -- ${shellQuote(parentPath(target))}
                if [ -e "${'$'}target" ] || [ -L "${'$'}target" ]; then
                  printf "Target already exists: %s\n" "${'$'}target" >&2
                  exit 73
                fi
                (
                  set -C
                  : > "${'$'}target"
                )
            """.trimIndent()
        }

        internal fun buildMoveCommand(sourcePath: String, targetPath: String): String {
            val source = normalizeMutablePath(sourcePath)
            val target = normalizeMutablePath(targetPath)
            return """
                set -eu
                source=${shellQuote(source)}
                target=${shellQuote(target)}
                if [ ! -e "${'$'}source" ] && [ ! -L "${'$'}source" ]; then
                  printf "Source does not exist: %s\n" "${'$'}source" >&2
                  exit 72
                fi
                if [ -e "${'$'}target" ] || [ -L "${'$'}target" ]; then
                  printf "Target already exists: %s\n" "${'$'}target" >&2
                  exit 73
                fi
                mkdir -p -- ${shellQuote(parentPath(target))}
                mv -n -- "${'$'}source" "${'$'}target"
                if [ -e "${'$'}source" ] || [ -L "${'$'}source" ]; then
                  printf "Target already exists: %s\n" "${'$'}target" >&2
                  exit 73
                fi
            """.trimIndent()
        }

        internal fun parseListOutput(output: String): List<Map<String, Any?>> {
            return output.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull { line ->
                    val fields = line.split('|', limit = 11)
                    if (fields.size != 11) return@mapNotNull null
                    val path = decodeField(fields[8])
                    val name = decodeField(fields[9])
                    val hasValidUtf8Path = path != null && name != null
                    mapOf(
                        "isDirectory" to (fields[0] == "1"),
                        "isFile" to (fields[1] == "1"),
                        "isLink" to (fields[2] == "1"),
                        "size" to (fields[3].toLongOrNull() ?: 0L),
                        "modifiedAt" to (fields[4].toLongOrNull() ?: 0L),
                        "mode" to fields[5],
                        "readable" to (hasValidUtf8Path && fields[6] == "1"),
                        "writable" to (hasValidUtf8Path && fields[7] == "1"),
                        "path" to path.orEmpty(),
                        "name" to name.orEmpty(),
                        "pathToken" to fields[8],
                        "nameToken" to fields[9],
                        "hasValidUtf8Path" to hasValidUtf8Path,
                        "linkTarget" to decodeField(fields[10]).orEmpty()
                    )
                }
                .toList()
        }

        private fun normalizeMutablePath(path: String): String {
            val normalized = normalizeAbsolutePath(path)
            require(normalized != "/") { "The Alpine root directory is not a mutable entry." }
            return normalized
        }

        private fun parentPath(path: String): String {
            val normalized = normalizeAbsolutePath(path)
            val separator = normalized.lastIndexOf('/')
            return if (separator <= 0) "/" else normalized.substring(0, separator)
        }

        private fun decodeField(value: String): String? {
            if (value.isEmpty()) return ""
            return runCatching {
                Base64.getDecoder().decode(value)
            }.getOrNull()?.let(::decodeStrictUtf8)
        }

        private fun shellQuote(value: String): String {
            return "'" + value.replace("'", "'\"'\"'") + "'"
        }
    }
}
