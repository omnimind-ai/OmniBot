package cn.com.omnimind.bot.terminal

import android.content.Context
import com.ai.assistance.operit.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class AlpineFileSystemService(context: Context) {
    private val appContext = context.applicationContext
    private val terminalManager = TerminalManager.getInstance(appContext)

    suspend fun list(path: String): Map<String, Any?> {
        val normalizedPath = normalizeAbsolutePath(path)
        val command = """
            set -eu
            target=${shellQuote(normalizedPath)}
            [ -d "${'$'}target" ]
            find "${'$'}target" -mindepth 1 -maxdepth 1 -exec sh -c '
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
            printf "%s\n" "${'$'}size"
            head -c $safeLimit -- "${'$'}target" | base64
        """.trimIndent()
        val output = execute(command, "alpine-fs-read", READ_TIMEOUT_MS)
        val firstBreak = output.indexOf('\n')
        require(firstBreak >= 0) { "Invalid Alpine file response." }
        val size = output.substring(0, firstBreak).trim().toLongOrNull() ?: 0L
        val encoded = output.substring(firstBreak + 1).filterNot(Char::isWhitespace)
        val bytes = if (encoded.isEmpty()) {
            ByteArray(0)
        } else {
            Base64.getMimeDecoder().decode(encoded)
        }
        return mapOf(
            "path" to normalizedPath,
            "size" to size,
            "truncated" to (size > safeLimit),
            "content" to bytes.toString(StandardCharsets.UTF_8)
        )
    }

    suspend fun write(path: String, content: String): Map<String, Any?> {
        val normalizedPath = normalizeMutablePath(path)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) {
            "Text files larger than $MAX_WRITE_BYTES bytes are not supported by the editor."
        }
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
                  cat -- "${'$'}source" > "${'$'}target"
                  stat -c %s -- "${'$'}target"
                  exit 0
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
        val parent = parentPath(normalizedPath)
        execute(
            """
                set -eu
                mkdir -p -- ${shellQuote(parent)}
                [ -e ${shellQuote(normalizedPath)} ] || : > ${shellQuote(normalizedPath)}
            """.trimIndent(),
            "alpine-fs-touch",
            MUTATION_TIMEOUT_MS
        )
        return mapOf("path" to normalizedPath, "isDirectory" to false)
    }

    suspend fun move(sourcePath: String, targetPath: String): Map<String, Any?> {
        val source = normalizeMutablePath(sourcePath)
        val target = normalizeMutablePath(targetPath)
        execute(
            """
                set -eu
                mkdir -p -- ${shellQuote(parentPath(target))}
                mv -- ${shellQuote(source)} ${shellQuote(target)}
            """.trimIndent(),
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
            val trimmed = path.trim().replace('\\', '/')
            require(trimmed.startsWith('/')) { "An absolute Alpine path is required." }
            require('\u0000' !in trimmed && '\n' !in trimmed && '\r' !in trimmed) {
                "Invalid Alpine path."
            }
            val segments = ArrayDeque<String>()
            trimmed.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeLast()
                    else -> segments.addLast(segment)
                }
            }
            return if (segments.isEmpty()) "/" else segments.joinToString(prefix = "/", separator = "/")
        }

        internal fun parseListOutput(output: String): List<Map<String, Any?>> {
            return output.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull { line ->
                    val fields = line.split('|', limit = 11)
                    if (fields.size != 11) return@mapNotNull null
                    mapOf(
                        "isDirectory" to (fields[0] == "1"),
                        "isFile" to (fields[1] == "1"),
                        "isLink" to (fields[2] == "1"),
                        "size" to (fields[3].toLongOrNull() ?: 0L),
                        "modifiedAt" to (fields[4].toLongOrNull() ?: 0L),
                        "mode" to fields[5],
                        "readable" to (fields[6] == "1"),
                        "writable" to (fields[7] == "1"),
                        "path" to decodeField(fields[8]),
                        "name" to decodeField(fields[9]),
                        "linkTarget" to decodeField(fields[10])
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

        private fun decodeField(value: String): String {
            if (value.isEmpty()) return ""
            return runCatching {
                Base64.getDecoder().decode(value).toString(StandardCharsets.UTF_8)
            }.getOrDefault("")
        }

        private fun shellQuote(value: String): String {
            return "'" + value.replace("'", "'\"'\"'") + "'"
        }
    }
}
