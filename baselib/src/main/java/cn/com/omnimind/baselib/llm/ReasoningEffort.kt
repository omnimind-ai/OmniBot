package cn.com.omnimind.baselib.llm

/**
 * Host vocabulary for the reasoning setting.
 *
 * ACP does not standardize a universal "reasoning effort" field. ACP Agents
 * advertise their own session config options; this vocabulary is only the
 * value set used by the built-in Agent/Provider path. Provider adapters may
 * reduce it to the values supported by their upstream API.
 */
object ReasoningEffort {
    const val NONE = "none"
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val XHIGH = "xhigh"
    const val MAX = "max"

    val supported: Set<String> = linkedSetOf(NONE, LOW, MEDIUM, HIGH, XHIGH, MAX)

    /** Normalize app/UI aliases at the host boundary; unknown ACP option IDs stay unknown. */
    fun normalize(value: String?): String? {
        return when (value?.trim()?.lowercase().orEmpty()) {
            "no", NONE, "off", "disabled" -> NONE
            LOW, "min", "minimal", "minimum" -> LOW
            MEDIUM, "med" -> MEDIUM
            HIGH -> HIGH
            XHIGH, "x-high", "x high", "extra_high", "extra-high", "very_high",
            "very-high" -> XHIGH
            MAX -> MAX
            else -> null
        }
    }
}
