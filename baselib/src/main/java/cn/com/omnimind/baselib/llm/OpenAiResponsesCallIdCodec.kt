package cn.com.omnimind.baselib.llm

import java.security.MessageDigest

/**
 * Encodes local tool-call identities for the OpenAI Responses wire format.
 *
 * Local ACP identities are intentionally opaque and may include session or
 * turn context. Responses limits `call_id` to 64 characters, so this mapping
 * belongs at the final HTTP boundary. Short IDs are preserved; long IDs are
 * replaced by a deterministic digest so a function call and its output keep
 * the same wire identity without changing local history or ACP events.
 */
object OpenAiResponsesCallIdCodec {
    private const val MAX_WIRE_ID_LENGTH = 64
    private const val DIGEST_PREFIX = "call_"
    private const val DIGEST_LENGTH = MAX_WIRE_ID_LENGTH - DIGEST_PREFIX.length

    class Plan internal constructor(
        private val originalToWire: Map<String, String>,
    ) {
        fun encode(callId: String): String {
            val normalized = callId.trim()
            return originalToWire[normalized] ?: encodeStandalone(normalized)
        }
    }

    fun planFor(messages: List<ChatCompletionMessage>): Plan {
        val ids = buildList {
            messages.forEach { message ->
                message.toolCallId?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
                if (message.role == "assistant") {
                    message.toolCalls.orEmpty().forEach { call ->
                        call.id.trim().takeIf(String::isNotEmpty)?.let(::add)
                    }
                }
            }
        }.distinct()

        val originalToWire = linkedMapOf<String, String>()
        val occupied = linkedMapOf<String, String>()
        ids.filter(::isWithinWireLimit).forEach { original ->
            originalToWire[original] = original
            occupied[original] = original
        }
        ids.filterNot(::isWithinWireLimit).forEach { original ->
            var salt = 0
            var candidate: String
            do {
                candidate = encodedCandidate(original, salt++)
            } while (occupied[candidate]?.let { it != original } == true)
            originalToWire[original] = candidate
            occupied[candidate] = original
        }
        return Plan(originalToWire)
    }

    internal fun isWithinWireLimit(callId: String): Boolean = callId.trim().length <= MAX_WIRE_ID_LENGTH

    private fun encodeStandalone(callId: String): String {
        val normalized = callId.trim()
        if (isWithinWireLimit(normalized)) return normalized
        return encodedCandidate(normalized, salt = 0)
    }

    private fun encodedCandidate(original: String, salt: Int): String {
        val source = if (salt == 0) original else "$original#$salt"
        val digest = MessageDigest
            .getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(DIGEST_LENGTH)
        return DIGEST_PREFIX + digest
    }
}
