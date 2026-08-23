package cn.com.omnimind.baselib.llm

import java.security.MessageDigest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps ACP/MCP tool identities onto the stricter OpenAI Responses function-name wire format.
 *
 * Tool names stored in conversation history may contain namespace separators such as `.` or `/`.
 * Responses only accepts ASCII letters, digits, `_`, and `-`. The plan is built from the whole
 * request so current tools, forced tool choice, and historical function calls always share the
 * same collision-safe name, while the model response can be restored before tool routing.
 */
object OpenAiResponsesFunctionNameCodec {
    private const val MAX_WIRE_NAME_LENGTH = 64
    private val validWireName = Regex("^[a-zA-Z0-9_-]+$")
    private val invalidWireCharacters = Regex("[^a-zA-Z0-9_-]+")
    private val repeatedUnderscores = Regex("_+")

    class Plan internal constructor(
        private val originalToWire: Map<String, String>,
        private val wireToOriginal: Map<String, String>,
    ) {
        fun encode(name: String): String = originalToWire[name.trim()] ?: encodeStandalone(name)

        fun restore(name: String): String = wireToOriginal[name.trim()] ?: name

        fun encodeRequest(request: ChatCompletionRequest): ChatCompletionRequest {
            val messages = request.messages.map { message ->
                val toolCalls = message.toolCalls?.map { toolCall ->
                    toolCall.copy(
                        function = toolCall.function.copy(
                            name = encode(toolCall.function.name),
                        ),
                    )
                }
                message.copy(toolCalls = toolCalls)
            }
            val tools = request.tools.map { tool ->
                tool.copy(function = tool.function.copy(name = encode(tool.function.name)))
            }
            val functions = request.functions?.map { function ->
                function.copy(name = encode(function.name))
            }
            return request.copy(
                messages = messages,
                tools = tools,
                functions = functions,
                toolChoice = request.toolChoice?.mapFunctionName(::encode),
                functionCall = request.functionCall?.mapFunctionName(::encode),
            )
        }

        fun restoreTurn(turn: ChatCompletionTurn): ChatCompletionTurn {
            val restoredCalls = turn.message.toolCalls?.map { toolCall ->
                toolCall.copy(
                    function = toolCall.function.copy(
                        name = restore(toolCall.function.name),
                    ),
                )
            }
            return turn.copy(message = turn.message.copy(toolCalls = restoredCalls))
        }
    }

    fun planFor(request: ChatCompletionRequest): Plan {
        val names = buildList {
            request.tools.forEach { add(it.function.name) }
            request.functions.orEmpty().forEach { add(it.name) }
            request.messages.forEach { message ->
                message.toolCalls.orEmpty().forEach { add(it.function.name) }
            }
            request.toolChoice.functionNameOrNull()?.let(::add)
            request.functionCall.functionNameOrNull()?.let(::add)
        }.map(String::trim).filter(String::isNotEmpty).distinct()

        val originalToWire = linkedMapOf<String, String>()
        val occupied = linkedMapOf<String, String>()

        // Keep already-valid names unchanged and reserve them before encoding namespaced names.
        names.filter(::isValidWireName).forEach { original ->
            originalToWire[original] = original
            occupied[original] = original
        }
        names.filterNot(::isValidWireName).forEach { original ->
            var salt = 0
            var candidate: String
            do {
                candidate = encodedCandidate(original, salt++)
            } while (occupied[candidate]?.let { it != original } == true)
            originalToWire[original] = candidate
            occupied[candidate] = original
        }

        return Plan(
            originalToWire = originalToWire,
            wireToOriginal = originalToWire.entries.associate { (original, wire) -> wire to original },
        )
    }

    internal fun isValidWireName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            trimmed.length <= MAX_WIRE_NAME_LENGTH &&
            validWireName.matches(trimmed)
    }

    private fun encodeStandalone(name: String): String {
        val normalized = name.trim()
        if (isValidWireName(normalized)) return normalized
        return encodedCandidate(normalized, salt = 0)
    }

    private fun encodedCandidate(original: String, salt: Int): String {
        val semanticBase = original
            .replace(invalidWireCharacters, "_")
            .replace(repeatedUnderscores, "_")
            .trim('_', '-')
            .ifEmpty { "tool" }
        val digest = sha256Hex(if (salt == 0) original else "$original#$salt").take(12)
        val suffix = "__$digest"
        return semanticBase.take(MAX_WIRE_NAME_LENGTH - suffix.length) + suffix
    }

    private fun sha256Hex(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun JsonElement?.functionNameOrNull(): String? {
        val objectValue = this as? JsonObject ?: return null
        val nestedFunction = objectValue["function"] as? JsonObject
        return (nestedFunction?.get("name") ?: objectValue["name"])
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun JsonElement.mapFunctionName(transform: (String) -> String): JsonElement {
        val objectValue = this as? JsonObject ?: return this
        val nestedFunction = objectValue["function"] as? JsonObject
        if (nestedFunction != null) {
            val rawName = nestedFunction["name"]?.jsonPrimitive?.contentOrNull ?: return this
            return JsonObject(
                objectValue + (
                    "function" to JsonObject(
                        nestedFunction + ("name" to JsonPrimitive(transform(rawName))),
                    )
                ),
            )
        }
        val rawName = objectValue["name"]?.jsonPrimitive?.contentOrNull ?: return this
        return JsonObject(objectValue + ("name" to JsonPrimitive(transform(rawName))))
    }
}
