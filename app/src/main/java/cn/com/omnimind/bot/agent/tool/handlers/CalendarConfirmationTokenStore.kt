package cn.com.omnimind.bot.agent.tool.handlers

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Process-local, single-use grants created only after a trusted local UI action. */
internal class LocalUserConfirmationTokenStore(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Grant(
        val argumentDigest: String,
        val expiresAtMillis: Long,
    )

    private val random = SecureRandom()
    private val grants = ConcurrentHashMap<String, Grant>()

    init {
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    fun issueFromTrustedUserAction(toolName: String, arguments: JsonObject): String {
        val now = nowMillis()
        removeExpired(now)
        val tokenBytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        grants[token] = Grant(
            argumentDigest = digest(toolName, arguments),
            expiresAtMillis = safeExpiry(now),
        )
        return token
    }

    /** Removes the token before checking it, so every outcome is single-use. */
    fun consume(token: String, toolName: String, arguments: JsonObject): Boolean {
        if (token.isBlank()) return false
        val grant = grants.remove(token) ?: return false
        val now = nowMillis()
        if (now >= grant.expiresAtMillis) return false
        return MessageDigest.isEqual(
            grant.argumentDigest.toByteArray(Charsets.US_ASCII),
            digest(toolName, arguments).toByteArray(Charsets.US_ASCII),
        )
    }

    private fun safeExpiry(now: Long): Long =
        if (Long.MAX_VALUE - now < ttlMillis) Long.MAX_VALUE else now + ttlMillis

    private fun removeExpired(now: Long) {
        grants.entries.removeIf { (_, grant) -> now >= grant.expiresAtMillis }
    }

    private fun digest(toolName: String, arguments: JsonObject): String {
        val canonical = buildString {
            append(toolName)
            append('\n')
            appendCanonical(arguments, this)
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun appendCanonical(element: JsonElement, output: StringBuilder) {
        when (element) {
            JsonNull -> output.append("null")
            is JsonPrimitive -> output.append(element.toString())
            is JsonArray -> {
                output.append('[')
                element.forEachIndexed { index, child ->
                    if (index > 0) output.append(',')
                    appendCanonical(child, output)
                }
                output.append(']')
            }
            is JsonObject -> {
                output.append('{')
                element.entries
                    .asSequence()
                    .filterNot { (key, _) -> key in UNTRUSTED_CONFIRMATION_FIELDS }
                    .sortedBy { (key, _) -> key }
                    .forEachIndexed { index, (key, child) ->
                        if (index > 0) output.append(',')
                        output.append(JsonPrimitive(key).toString())
                        output.append(':')
                        appendCanonical(child, output)
                    }
                output.append('}')
            }
        }
    }

    companion object {
        private const val DEFAULT_TTL_MILLIS = 30_000L
        private const val TOKEN_BYTES = 32
        private val UNTRUSTED_CONFIRMATION_FIELDS = setOf("confirmed", "confirmationToken")
    }
}
