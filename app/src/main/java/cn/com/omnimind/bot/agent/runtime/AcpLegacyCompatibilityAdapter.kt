package cn.com.omnimind.bot.agent.runtime

/**
 * The only request adapter for the pre-ACP conversation vocabulary.
 *
 * Application code must use the official ACP session methods.  This adapter
 * exists only so older clients can continue sending thread/turn requests
 * while they are being migrated.  It deliberately does not invent an ACP
 * lifecycle: the returned method is handled by the same session path as a
 * canonical caller.
 */
internal object AcpLegacyCompatibilityAdapter {
    const val THREAD_START = "thread/start"
    const val THREAD_RESUME = "thread/resume"
    const val THREAD_READ = "thread/read"
    const val THREAD_LIST = "thread/list"
    const val THREAD_LOADED_LIST = "thread/loaded/list"
    const val THREAD_ARCHIVE = "thread/archive"
    const val THREAD_UNARCHIVE = "thread/unarchive"
    const val THREAD_NAME_SET = "thread/name/set"
    const val TURN_START = "turn/start"
    const val TURN_STEER = "turn/steer"
    const val TURN_INTERRUPT = "turn/interrupt"

    data class Request(
        val method: String,
        val args: Map<String, Any?>,
        val legacyMethod: String? = null,
    )

    fun adaptResponse(request: Request, response: Any?): Any? {
        if (request.legacyMethod == null || response !is Map<*, *>) {
            return response
        }
        val result = LinkedHashMap<String, Any?>().apply {
            response.forEach { (key, value) ->
                put(key.toString(), value)
            }
        }
        when (request.legacyMethod) {
            THREAD_LIST,
            THREAD_LOADED_LIST -> {
                // Old clients read `threads`; canonical ACP clients read
                // `sessions`. Keep one list and expose both names during the
                // migration window instead of maintaining another store.
                result["sessions"]?.let { result.putIfAbsent("threads", it) }
                result["threads"]?.let { result.putIfAbsent("sessions", it) }
            }
        }
        return result
    }

    /**
     * Translate only old public method names.  Internal remote transport
     * requests are not passed through this function; they remain inside the
     * remote compatibility implementation.
     */
    fun adapt(method: String, args: Map<String, Any?>): Request = when (method) {
        THREAD_START -> Request("session/new", args, method)
        THREAD_RESUME -> Request("session/resume", args, method)
        THREAD_READ -> Request("session/load", args, method)
        THREAD_LIST,
        THREAD_LOADED_LIST -> Request("session/list", args, method)
        THREAD_ARCHIVE -> Request("session/archive", args, method)
        THREAD_UNARCHIVE -> Request("session/unarchive", args, method)
        THREAD_NAME_SET -> Request("session/name/set", args, method)
        TURN_START -> Request("session/prompt", args, method)
        TURN_INTERRUPT -> Request("session/cancel", args, method)
        // ACP has no equivalent for steering an already admitted prompt.
        // Fail at the compatibility boundary instead of creating a private
        // second Turn lifecycle in the runtime manager.
        TURN_STEER -> throw UnsupportedOperationException(
            "ACP does not define turn/steer; send a new session/prompt instead.",
        )
        else -> Request(method, args)
    }

    fun isLegacyMethod(method: String): Boolean = when (method) {
        THREAD_START,
        THREAD_RESUME,
        THREAD_READ,
        THREAD_LIST,
        THREAD_LOADED_LIST,
        THREAD_ARCHIVE,
        THREAD_UNARCHIVE,
        THREAD_NAME_SET,
        TURN_START,
        TURN_STEER,
        TURN_INTERRUPT -> true
        else -> false
    }
}
