package cn.com.omnimind.baselib.util

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

internal object RuntimeLogLimits {
    const val MAX_COUNT = 200
    const val MAX_BYTES = 256 * 1024
    const val MAX_LEGACY_READ_BYTES = 512 * 1024
    const val MESSAGE_BYTES = 4 * 1024
    const val STACK_BYTES = 8 * 1024
    const val QUEUE_CAPACITY = 32
    const val OMITTED = "\n[内容过长，已省略]"
}

/**
 * Limits UTF-8 JSON bytes without first encoding the potentially enormous input.
 * Escapes count toward the budget and surrogate pairs are never split. The same
 * bound also covers the raw UTF-8 strings sent through the Flutter channel.
 */
internal fun boundedRuntimeLogText(value: String, maxBytes: Int): String {
    require(maxBytes >= 64) { "Runtime log text budget must leave space for the omission notice" }
    var invalidSurrogate = false
    fun endWithin(budget: Int): Int {
        var offset = 0
        var bytes = 0
        while (offset < value.length) {
            val char = value[offset]
            val pair = char.isHighSurrogate() &&
                offset + 1 < value.length && value[offset + 1].isLowSurrogate()
            if (char.isSurrogate() && !pair) invalidSurrogate = true
            val cost = when {
                pair -> 4
                char == '"' || char == '\\' -> 2
                char.code < 0x20 || char == '\u2028' || char == '\u2029' -> 6
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                else -> 3
            }
            if (bytes + cost > budget) break
            bytes += cost
            offset += if (pair) 2 else 1
        }
        return offset
    }

    val fullEnd = endWithin(maxBytes)
    val omitted = fullEnd < value.length
    if (!omitted && !invalidSurrogate) return value
    val end = if (omitted) {
        // The notice contains one newline, represented as two JSON bytes.
        endWithin(maxBytes - RuntimeLogLimits.OMITTED.toByteArray(Charsets.UTF_8).size - 1)
    } else {
        fullEnd
    }
    return buildString(end + if (omitted) RuntimeLogLimits.OMITTED.length else 0) {
        var index = 0
        while (index < end) {
            val char = value[index++]
            when {
                char.isHighSurrogate() && index < end && value[index].isLowSurrogate() -> {
                    append(char)
                    append(value[index++])
                }
                char.isSurrogate() -> append('\uFFFD')
                else -> append(char)
            }
        }
        if (omitted) append(RuntimeLogLimits.OMITTED)
    }
}

internal fun RuntimeLogEntry.bounded(): RuntimeLogEntry {
    val safeId = boundedRuntimeLogText(id, 128)
    val safeLevel = boundedRuntimeLogText(level, 64)
    val safeTag = boundedRuntimeLogText(tag, 256)
    val safeMessage = boundedRuntimeLogText(message, RuntimeLogLimits.MESSAGE_BYTES)
    val safeStack = stackTrace?.let { boundedRuntimeLogText(it, RuntimeLogLimits.STACK_BYTES) }
    if (safeId === id && safeLevel === level && safeTag === tag &&
        safeMessage === message && safeStack === stackTrace
    ) return this
    return copy(id = safeId, level = safeLevel, tag = safeTag, message = safeMessage, stackTrace = safeStack)
}

/** Build a bounded trace directly; Throwable.toString() can copy a huge message. */
internal fun boundedRuntimeLogStackTrace(throwable: Throwable): String {
    val result = StringBuilder(1024)
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var cause: Throwable? = throwable
    var causes = 0
    var exhausted = false
    // Char budget bounds intermediate allocations; the final pass bounds UTF-8
    // and JSON escaping too. Do not retain Throwable objects in the writer queue.
    fun append(value: String) {
        val remaining = RuntimeLogLimits.STACK_BYTES - result.length
        if (value.length > remaining) exhausted = true
        if (remaining > 0) result.append(value, 0, minOf(value.length, remaining))
    }
    while (cause != null && !exhausted) {
        if (!seen.add(cause) || causes++ == 8) {
            exhausted = true
            break
        }
        if (result.isNotEmpty()) append("Caused by: ")
        append(cause.javaClass.name)
        cause.message?.let {
            append(": ")
            append(it)
        }
        append("\n")
        if (!exhausted) {
            val frames = cause.stackTrace
            for (index in 0 until minOf(frames.size, 256)) {
                if (exhausted) break
                val frame = frames[index]
                append("    at ")
                append(frame.className)
                append(".")
                append(frame.methodName)
                append("(")
                append(if (frame.isNativeMethod) "Native Method" else frame.fileName ?: "Unknown Source")
                if (!frame.isNativeMethod && frame.lineNumber >= 0) {
                    append(":")
                    append(frame.lineNumber.toString())
                }
                append(")\n")
            }
            if (frames.size > 256) exhausted = true
        }
        cause = cause.cause
    }
    if (exhausted) result.append(RuntimeLogLimits.OMITTED)
    return boundedRuntimeLogText(result.toString(), RuntimeLogLimits.STACK_BYTES)
}

/** A single writer and a finite queue; error storms cannot retain unlimited jobs. */
internal class RuntimeLogWriter(
    scope: CoroutineScope,
    capacity: Int = RuntimeLogLimits.QUEUE_CAPACITY,
    private val append: suspend (RuntimeLogEntry) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val pending = Channel<RuntimeLogEntry>(
        capacity = capacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val job = scope.launch {
        for (entry in pending) {
            try {
                append(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError(error)
            }
        }
    }

    fun offer(entry: RuntimeLogEntry): Boolean = pending.trySend(entry.bounded()).isSuccess

    internal suspend fun closeAndJoin() {
        pending.close()
        job.join()
    }
}
