package cn.com.omnimind.baselib.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object OmniLog {
    enum class Level {
        VERBOSE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        ASSERT,
        DISABLE,
    }

    /**
     * log params
     */
    private var globalTag = "[Omni]"
    private var minLevel = Level.INFO

    /**
     * Optional extra reporter to mirror logs elsewhere (e.g. testbot -> scheduler).
     * Default null to preserve existing behavior.
     */
    @Volatile
    var extraLogReporter: ((tag: String, message: String, level: String) -> Unit)? = null

    /**
     * Notify the extra reporter if set.
     */
    private fun notifyReporter(tag: String, message: String, level: String) {
        try {
            extraLogReporter?.invoke(tag, message, level)
        } catch (_: Exception) {
            // Never let reporting side effects impact business execution.
        }
    }

    /**
     * get Log level
     */
    fun getLogLevel(): Level = minLevel

    /**
     * set Log level
     * set to DISABLE means disable OmniLog
     */
    fun setLogLevel(level: Level) {
        this.minLevel = level
    }

    /**
     * Whether to mirror ERROR, ASSERT, and crash logs to [RuntimeLogStore].
     */
    @Volatile
    var runtimeLogEnabled: Boolean = true

    private val runtimeLogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun storeRuntimeLog(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!runtimeLogEnabled) return
        if (tag == "RuntimeLogStore") return
        if (tag == "[AssistsCoreChannel]") return
        val safeMessage = SensitiveDataSanitizer.sanitize(message)
        val stack = throwable?.let {
            SensitiveDataSanitizer.sanitize(buildStackTraceString(it), MAX_STACK_TRACE_CHARS)
        }
        val entry = RuntimeLogEntry(
            level = level,
            tag = tag,
            message = safeMessage,
            stackTrace = stack,
            isCrash = false,
        )
        runtimeLogScope.launch {
            runCatching {
                RuntimeLogStore.append(entry)
            }.onFailure {
                Log.w(globalTag + "RuntimeLogStore", "append runtime log failed", it)
            }
        }
    }

    fun storeCrashLog(tag: String, message: String, throwable: Throwable) {
        if (!runtimeLogEnabled) return
        runCatching {
            RuntimeLogStore.append(
                RuntimeLogEntry(
                    level = "ERROR",
                    tag = tag,
                    message = SensitiveDataSanitizer.sanitize(message),
                    stackTrace = SensitiveDataSanitizer.sanitize(
                        buildStackTraceString(throwable),
                        MAX_STACK_TRACE_CHARS,
                    ),
                    isCrash = true,
                )
            )
        }.onFailure {
            Log.w(globalTag + "RuntimeLogStore", "append crash log failed", it)
        }
    }

    private fun buildStackTraceString(throwable: Throwable): String {
        return buildString {
            appendLine(throwable.toString())
            throwable.stackTrace.forEach { appendLine("    at $it") }
            var cause = throwable.cause
            while (cause != null) {
                appendLine("Caused by: $cause")
                cause.stackTrace.forEach { appendLine("    at $it") }
                cause = cause.cause
            }
        }
    }

    private fun safeLogMessage(message: String, throwable: Throwable?): String {
        val safeMessage = SensitiveDataSanitizer.sanitize(message)
        val safeStack = throwable?.let {
            SensitiveDataSanitizer.sanitize(buildStackTraceString(it), MAX_STACK_TRACE_CHARS)
        }.orEmpty()
        return if (safeStack.isBlank()) safeMessage else "$safeMessage\n$safeStack"
    }

    /**
     * log verbose
     * Place at the beginning of each function call to monitor function invocation
     */
    @JvmOverloads
    fun v(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.VERBOSE.ordinal) return
        val actualTag = globalTag + tag
        val safeMessage = safeLogMessage(message, throwable)
        Log.v(actualTag, safeMessage)
        notifyReporter(tag, safeMessage, "VERBOSE")
    }

    /**
     * log debug
     * Record event outcome details
     */
    @JvmOverloads
    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.DEBUG.ordinal) return
        val actualTag = globalTag + tag
        val safeMessage = safeLogMessage(message, throwable)
        Log.d(actualTag, safeMessage)
        notifyReporter(tag, safeMessage, "DEBUG")
    }

    /**
     * log info
     * Track user behavior and record critical data
     */
    @JvmOverloads
    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.INFO.ordinal) return
        val actualTag = globalTag + tag
        val safeMessage = safeLogMessage(message, throwable)
        Log.i(actualTag, safeMessage)
        notifyReporter(tag, safeMessage, "INFO")
    }

    /**
     * log warn
     * Record handled exceptions that won't terminate the application
     */
    @JvmOverloads
    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.WARN.ordinal) return
        val actualTag = globalTag + tag
        val safeMessage = safeLogMessage(message, throwable)
        Log.w(actualTag, safeMessage)
        notifyReporter(tag, safeMessage, "WARN")
    }

    /**
     * log error
     * Record serious failures that affect functionality
     */
    @JvmOverloads
    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.ERROR.ordinal) return
        val actualTag = globalTag + tag
        val safeMessage = safeLogMessage(message, throwable)
        Log.e(actualTag, safeMessage)
        notifyReporter(tag, safeMessage, "ERROR")
        storeRuntimeLog("ERROR", tag, message, throwable)
    }

    /**
     * log assert
     * Record critical errors that should never occur (what the fuck message)
     */
    @JvmOverloads
    fun wtf(
        tag: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        if (minLevel.ordinal > Level.ASSERT.ordinal) return
        val actualTag = globalTag + tag
        val safeMessage = safeLogMessage(message, throwable)
        Log.wtf(actualTag, safeMessage)
        notifyReporter(tag, safeMessage, "ASSERT")
        storeRuntimeLog("ASSERT", tag, message, throwable)
    }

    private const val MAX_STACK_TRACE_CHARS = 24_000
}
