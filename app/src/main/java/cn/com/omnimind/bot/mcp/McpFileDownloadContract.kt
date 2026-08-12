package cn.com.omnimind.bot.mcp

/** Keeps short-lived file credentials out of URLs, histories, referrers, and proxy logs. */
internal object McpFileDownloadContract {
    const val TOKEN_HEADER = "X-OmniBot-File-Token"

    fun buildUrl(host: String, port: Int, fileId: String): String {
        val authorityHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        return "http://$authorityHost:$port/mcp/file/$fileId"
    }

    fun buildHeaders(token: String): Map<String, String> = mapOf(TOKEN_HEADER to token)
}
