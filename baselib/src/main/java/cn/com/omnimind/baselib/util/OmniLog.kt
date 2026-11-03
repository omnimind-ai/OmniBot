package cn.com.omnimind.baselib.util

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    private var minLevel = Level.VERBOSE

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
    private val runtimeLogWriter by lazy {
        RuntimeLogWriter(
            scope = runtimeLogScope,
            append = RuntimeLogStore::append,
            onError = { Log.w(globalTag + "RuntimeLogStore", "append runtime log failed", it) },
        )
    }

    private fun storeRuntimeLog(entry: RuntimeLogEntry) {
        if (!runtimeLogEnabled) return
        if (entry.tag == "RuntimeLogStore") return
        if (entry.tag == "[AssistsCoreChannel]") return
        runtimeLogWriter.offer(entry)
    }

    private fun boundedEntry(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
        isCrash: Boolean = false,
    ): RuntimeLogEntry = RuntimeLogEntry(
        level = level,
        tag = tag,
        message = message,
        stackTrace = throwable?.let(::boundedRuntimeLogStackTrace),
        isCrash = isCrash,
    ).bounded()

    private fun RuntimeLogEntry.logcatMessage(): String =
        if (stackTrace.isNullOrEmpty()) message else "$message\n$stackTrace"

    fun storeCrashLog(tag: String, message: String, throwable: Throwable) {
        if (!runtimeLogEnabled) return
        try {
            RuntimeLogStore.append(
                boundedEntry(
                    level = "ERROR",
                    tag = tag,
                    message = message,
                    throwable = throwable,
                    isCrash = true,
                ),
            )
        } catch (error: Exception) {
            Log.w(globalTag + "RuntimeLogStore", "append crash log failed", error)
        }
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
        Log.v(actualTag, message, throwable)
        notifyReporter(tag, message, "VERBOSE")
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
        Log.v(actualTag, message, throwable)
        notifyReporter(tag, message, "DEBUG")
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
        Log.i(actualTag, message, throwable)
        notifyReporter(tag, message, "INFO")
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
        Log.w(actualTag, message, throwable)
        notifyReporter(tag, message, "WARN")
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
        val entry = boundedEntry("ERROR", tag, message, throwable)
        Log.e(globalTag + entry.tag, entry.logcatMessage())
        notifyReporter(entry.tag, entry.message, entry.level)
        storeRuntimeLog(entry)
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
        val entry = boundedEntry("ASSERT", tag, message, throwable)
        // Keep the platform's ASSERT/terrible-failure behavior, but never hand
        // it an unbounded Throwable message to stringify a second time.
        Log.wtf(globalTag + entry.tag, entry.logcatMessage())
        notifyReporter(entry.tag, entry.message, entry.level)
        storeRuntimeLog(entry)
    }
}
