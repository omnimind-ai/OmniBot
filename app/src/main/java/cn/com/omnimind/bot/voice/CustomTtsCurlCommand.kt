package cn.com.omnimind.bot.voice

import cn.com.omnimind.baselib.llm.SceneVoiceConfigStore

/**
 * 解析用户粘贴的自定义 TTS `curl` 命令。
 *
 * 设计目标：保持纯逻辑、不依赖 okhttp / Android，方便单元测试。调用方（播放管理器）
 * 负责根据 [ParsedCurl] 构建实际的 OkHttp 请求。
 *
 * 约定：命令中的 [SceneVoiceConfigStore.TEXT_PLACEHOLDER]（即 `{{text}}`）会在发送前被
 * 替换为待合成文本，替换时按 JSON 字符串转义，以适配文档里 `"input": "{{text}}"` 的写法。
 */
object CustomTtsCurlCommand {

    data class ParsedCurl(
        val url: String,
        val method: String,
        val headers: List<Pair<String, String>>,
        val body: String?
    )

    /** 将命令中的 `{{text}}` 占位符替换为 JSON 转义后的待合成文本。 */
    fun substituteText(command: String, text: String): String {
        return command.replace(SceneVoiceConfigStore.TEXT_PLACEHOLDER, jsonEscape(text))
    }

    /** 解析 curl 命令，抽取 URL、方法、请求头与请求体。 */
    fun parse(command: String): ParsedCurl {
        val tokens = tokenize(command)
        var url: String? = null
        var method: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        val bodyParts = mutableListOf<String>()

        var i = 0
        if (tokens.isNotEmpty() && tokens[0].equals("curl", ignoreCase = true)) {
            i = 1
        }
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == "-X" || token == "--request" -> {
                    method = tokens.getOrNull(i + 1)?.uppercase()
                    i += 2
                }
                token.startsWith("--request=") -> {
                    method = token.removePrefix("--request=").uppercase()
                    i++
                }
                token == "-H" || token == "--header" -> {
                    tokens.getOrNull(i + 1)?.let { parseHeader(it)?.let(headers::add) }
                    i += 2
                }
                token.startsWith("--header=") -> {
                    parseHeader(token.removePrefix("--header="))?.let(headers::add)
                    i++
                }
                token == "-d" || token == "--data" || token == "--data-raw" ||
                    token == "--data-binary" || token == "--data-ascii" -> {
                    tokens.getOrNull(i + 1)?.let(bodyParts::add)
                    i += 2
                }
                DATA_PREFIXES.any { token.startsWith(it) } -> {
                    val prefix = DATA_PREFIXES.first { token.startsWith(it) }
                    bodyParts.add(token.removePrefix(prefix))
                    i++
                }
                token == "--url" -> {
                    url = url ?: tokens.getOrNull(i + 1)
                    i += 2
                }
                token.startsWith("--url=") -> {
                    url = url ?: token.removePrefix("--url=")
                    i++
                }
                token in NO_ARG_FLAGS -> i++
                token in ARG_FLAGS_TO_SKIP -> i += 2
                token.startsWith("-") && token.length > 1 -> i++
                else -> {
                    if (url == null) {
                        url = token
                    }
                    i++
                }
            }
        }

        val resolvedUrl = url?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("curl 命令中未找到请求地址（URL）")
        val body = if (bodyParts.isEmpty()) null else bodyParts.joinToString(separator = "&")
        val resolvedMethod = method?.takeIf { it.isNotBlank() }
            ?: if (body != null) "POST" else "GET"
        return ParsedCurl(
            url = resolvedUrl,
            method = resolvedMethod,
            headers = headers,
            body = body
        )
    }

    private val DATA_PREFIXES = listOf(
        "--data-raw=",
        "--data-binary=",
        "--data-ascii=",
        "--data="
    )

    private val NO_ARG_FLAGS = setOf(
        "-s", "--silent",
        "-v", "--verbose",
        "-L", "--location",
        "-k", "--insecure",
        "--compressed",
        "-f", "--fail",
        "-#", "--progress-bar",
        "-g", "--globoff",
        "-i", "--include"
    )

    // 需要连同其后一个参数一起跳过的选项（我们不需要它们的值）。
    private val ARG_FLAGS_TO_SKIP = setOf(
        "-o", "--output",
        "-A", "--user-agent",
        "-e", "--referer",
        "-u", "--user",
        "-m", "--max-time",
        "--connect-timeout",
        "--retry"
    )

    private fun parseHeader(raw: String): Pair<String, String>? {
        val idx = raw.indexOf(':')
        if (idx <= 0) {
            return null
        }
        val name = raw.substring(0, idx).trim()
        val value = raw.substring(idx + 1).trim()
        return if (name.isEmpty()) null else name to value
    }

    /**
     * 类 shell 分词：支持单引号（原样）、双引号（处理 `\" \\ \` \$` 转义）、反斜杠续行以及
     * 引号外的反斜杠转义。
     */
    private fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var hasToken = false
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when (c) {
                ' ', '\t', '\r', '\n' -> {
                    if (hasToken) {
                        tokens.add(current.toString())
                        current.setLength(0)
                        hasToken = false
                    }
                    i++
                }
                '\\' -> {
                    val next = input.getOrNull(i + 1)
                    when (next) {
                        null -> i++
                        '\n' -> i += 2
                        '\r' -> {
                            i += 2
                            if (input.getOrNull(i) == '\n') i++
                        }
                        else -> {
                            current.append(next)
                            hasToken = true
                            i += 2
                        }
                    }
                }
                '\'' -> {
                    hasToken = true
                    i++
                    while (i < n && input[i] != '\'') {
                        current.append(input[i])
                        i++
                    }
                    if (i < n) i++
                }
                '"' -> {
                    hasToken = true
                    i++
                    while (i < n && input[i] != '"') {
                        val ch = input[i]
                        if (ch == '\\' && i + 1 < n) {
                            val next = input[i + 1]
                            if (next == '"' || next == '\\' || next == '`' || next == '$') {
                                current.append(next)
                                i += 2
                            } else if (next == '\n') {
                                i += 2
                            } else {
                                current.append(ch)
                                i++
                            }
                        } else {
                            current.append(ch)
                            i++
                        }
                    }
                    if (i < n) i++
                }
                else -> {
                    current.append(c)
                    hasToken = true
                    i++
                }
            }
        }
        if (hasToken) {
            tokens.add(current.toString())
        }
        return tokens
    }

    private fun jsonEscape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else -> if (ch < ' ') {
                    sb.append("\\u").append("%04x".format(ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }
}
