package cn.com.omnimind.bot.agent

/** Execution metadata for the shared prompt loop, never inferred from a tool's title. */
internal object AgentToolExecutionPolicy {
    const val MAX_PARALLEL_READS = 4

    // Only handlers whose operations are independent reads are admitted here.
    // Shells, browser/device state, writes, approvals, and unknown capabilities
    // retain exclusive execution, even when their names sound read-only.
    private val parallelReads = setOf(
        "file_read", "file_list", "file_search", "file_stat",
        "skills_read", "memory_load", "context_time_now",
    )

    fun supportsParallelRead(name: String): Boolean = name in parallelReads

    fun <T> batches(calls: List<T>, supportsParallel: (T) -> Boolean): List<List<T>> {
        val batches = mutableListOf<List<T>>()
        var reads = mutableListOf<T>()
        fun flush() {
            if (reads.isNotEmpty()) {
                batches += reads.toList()
                reads = mutableListOf()
            }
        }
        for (call in calls) {
            if (supportsParallel(call)) {
                reads += call
            } else {
                flush()
                batches += listOf(call)
            }
        }
        flush()
        return batches
    }
}
