package cn.com.omnimind.bot.agent

/**
 * Additional user input owned by one already-running ACP prompt. The inbox
 * never starts, cancels, retries or completes a turn. Its owner consumes input
 * only between model/tool rounds, and seals admission before PromptResponse.
 */
class AgentPromptInbox(
    val hostTurnId: String?,
    private val publishAccepted: (Entry) -> Unit = {},
) {
    data class Entry(val requestId: String, val text: String)

    enum class Admission { ACCEPTED, DUPLICATE, PROMPT_REQUIRED }

    private val lock = Any()
    private val accepted = linkedMapOf<String, Entry>()
    private val pending = ArrayDeque<Entry>()
    private var sealed = false

    fun offer(
        entry: Entry,
        expectedTurnId: String?,
        onAccepted: (Entry) -> Unit = publishAccepted,
    ): Admission = synchronized(lock) {
        require(entry.requestId.isNotBlank()) { "Steering requestId is required" }
        require(entry.text.isNotBlank()) { "Steering prompt is empty" }
        if (expectedTurnId != null && expectedTurnId != hostTurnId) {
            return@synchronized Admission.PROMPT_REQUIRED
        }
        accepted[entry.requestId]?.let { known ->
            require(known == entry) { "Steering requestId was already used for different input" }
            return@synchronized Admission.DUPLICATE
        }
        if (sealed) return@synchronized Admission.PROMPT_REQUIRED
        // Publish the official user item before making it available to the
        // model. A fast final round cannot overtake this input's history echo.
        onAccepted(entry)
        accepted[entry.requestId] = entry
        pending.addLast(entry)
        Admission.ACCEPTED
    }

    fun drain(): List<Entry> = synchronized(lock) { drainLocked() }

    /** Atomically take the last input, or close admission before finishing. */
    fun drainOrSeal(): List<Entry> = synchronized(lock) {
        drainLocked().also { if (it.isEmpty()) sealed = true }
    }

    fun acceptedEntries(): List<Entry> = synchronized(lock) { accepted.values.toList() }

    fun close() = synchronized(lock) {
        sealed = true
        pending.clear()
    }

    private fun drainLocked(): List<Entry> = pending.toList().also { pending.clear() }
}
