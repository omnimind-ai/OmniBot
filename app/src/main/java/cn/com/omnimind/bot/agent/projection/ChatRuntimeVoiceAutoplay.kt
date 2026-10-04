package cn.com.omnimind.bot.agent.projection

/**
 * Assistant-reply voice autoplay: the port of Dart
 * `VoicePlaybackCoordinator.onAssistantMessageUpdated` and
 * `SceneVoiceTextProcessing`. It speaks each sealed sentence once while a
 * reply streams and the rest when it completes.
 *
 * Manual playback controls (play/pause/replay state per message) stay with
 * the UI owner; only the streaming tracker moves with the runtime owner.
 */
class ChatRuntimeVoiceAutoplay(
    /** Voice scene bound (provider + model) or custom curl ready, and autoplay on. */
    private val autoplayEnabled: () -> Boolean,
    /** Native `SceneVoicePlaybackManager.speakText`; returns whether it was accepted. */
    private val speak: (messageId: String, text: String, enqueue: Boolean) -> Boolean,
) : ChatRuntimeVoice {
    private class Tracker {
        var lastText = ""
        var nextIndex = 0
        var hasQueuedAny = false

        fun reset() {
            lastText = ""
            nextIndex = 0
            hasQueuedAny = false
        }
    }

    private val trackers = HashMap<String, Tracker>()

    override fun onAssistantMessageUpdated(messageId: String, text: String, isFinal: Boolean) {
        if (!autoplayEnabled()) {
            if (isFinal) trackers.remove(messageId)
            return
        }
        val normalizedText = text.trimEnd()
        if (normalizedText.isEmpty()) return
        val tracker = trackers.getOrPut(messageId) { Tracker() }
        // A shorter prefix of the text already seen is a stale replay.
        if (tracker.lastText.isNotEmpty() && normalizedText.length < tracker.lastText.length &&
            tracker.lastText.startsWith(normalizedText)
        ) {
            return
        }
        if (tracker.lastText.isNotEmpty() && !normalizedText.startsWith(tracker.lastText)) tracker.reset()
        val extraction = SceneVoiceTextProcessing.extractSealedSegments(normalizedText, tracker.nextIndex, isFinal)
        tracker.lastText = normalizedText
        tracker.nextIndex = extraction.nextIndex
        for (segment in extraction.segments) {
            if (speak(messageId, segment, tracker.hasQueuedAny)) tracker.hasQueuedAny = true
        }
        if (isFinal) trackers.remove(messageId)
    }

    /** Drops tracking when the voice scene becomes unavailable. */
    fun clear() = trackers.clear()
}

/** Port of Dart `SceneVoiceTextProcessing`. */
object SceneVoiceTextProcessing {
    data class Extraction(val segments: List<String>, val nextIndex: Int)

    private val segmentTerminators = setOf('。', '！', '？', '!', '?', '；', ';', '：', ':', '\n')

    fun extractSealedSegments(fullText: String, fromIndex: Int, isFinal: Boolean): Extraction {
        if (fullText.isEmpty()) return Extraction(emptyList(), 0)
        val safeFromIndex = fromIndex.coerceIn(0, fullText.length)
        val segments = ArrayList<String>()
        var insideFence = isInsideCodeFence(fullText, safeFromIndex)
        var segmentStart = safeFromIndex
        var cursor = safeFromIndex
        while (cursor < fullText.length) {
            if (startsFence(fullText, cursor)) {
                insideFence = !insideFence
                cursor += 3
                continue
            }
            if (!insideFence && fullText[cursor] in segmentTerminators) {
                val sanitized = sanitizeForSpeech(fullText.substring(segmentStart, cursor + 1))
                if (sanitized.isNotEmpty()) segments.add(sanitized)
                segmentStart = cursor + 1
            }
            cursor += 1
        }
        if (isFinal && segmentStart < fullText.length) {
            val sanitized = sanitizeForSpeech(fullText.substring(segmentStart))
            if (sanitized.isNotEmpty()) segments.add(sanitized)
            segmentStart = fullText.length
        }
        return Extraction(segments, segmentStart)
    }

    // Dart (JavaScript) `\s` is Unicode whitespace while Java's is ASCII;
    // spell the Dart set out. `\d` stays ASCII in both.
    private const val WS = "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private const val NON_WS = "[^\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
    private val codeFence = Regex("```(?:$WS|$NON_WS)*?```")
    private val image = Regex("!\\[([^\\]]*)\\]\\(([^)]+)\\)")
    private val link = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    private val inlineCode = Regex("`([^`]+)`")
    private val url = Regex("https?://$NON_WS+")
    private val tag = Regex("<[^>]+>")
    private val blockMarker = Regex("(^|\\n)$WS{0,3}[#>*-]+$WS*")
    private val orderedMarker = Regex("(^|\\n)$WS{0,3}\\d+\\.$WS*")
    private val whitespace = Regex("$WS+")

    fun sanitizeForSpeech(rawText: String): String {
        var value = rawText
        value = value.replace(codeFence, " ")
        value = value.replace(image) { it.groupValues[1].trim() }
        value = value.replace(link) { it.groupValues[1].trim() }
        value = value.replace(inlineCode) { it.groupValues[1].trim() }
        value = value.replace(url, " ")
        value = value.replace(tag, " ")
        value = value.replace(blockMarker, " ")
        value = value.replace(orderedMarker, " ")
        value = value.replace("**", "").replace("__", "").replace("~~", "")
        value = value.replace(whitespace, " ")
        return value.trim()
    }

    private fun startsFence(value: String, index: Int): Boolean =
        index + 2 < value.length && value.regionMatches(index, "```", 0, 3)

    private fun isInsideCodeFence(value: String, index: Int): Boolean {
        var cursor = 0
        var insideFence = false
        while (cursor < index) {
            if (startsFence(value, cursor)) {
                insideFence = !insideFence
                cursor += 3
                continue
            }
            cursor += 1
        }
        return insideFence
    }
}
