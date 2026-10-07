package cn.com.omnimind.bot.agent.projection

import android.content.Context
import android.content.SharedPreferences

/**
 * The Agent composer's per-turn command preferences (batch 5d-1a): model,
 * reasoning effort, collaboration mode and permission mode, stored per
 * conversation with a global fallback.
 *
 * Ported from `chat_page_agent.dart` (`_readAgentPreference`,
 * `_writeAgentPreference`, `_clearAgentPreference`, `_agentPreferenceKey`).
 * The keys are unchanged, so the Flutter page and a native composer read and
 * write the same values while both exist. The model key carries its model
 * source ([agentModelSourceKey]): a model chosen for one catalog is never
 * read back for another.
 */
class AgentCommandPreferences(private val store: KeyValueStore) {
    /** Minimal storage seam so the rules are testable without Android. */
    interface KeyValueStore {
        fun getString(key: String): String?
        fun putString(key: String, value: String)
        fun remove(key: String)
    }

    enum class Kind(val storageKey: String) {
        Model("model"),
        ReasoningEffort("reasoning_effort"),
        CollaborationMode("collaboration_mode"),
        PermissionMode("permission_mode"),
    }

    /**
     * The conversation's value, else the global one, else null.
     *
     * The legacy `chat_codex_command_preference` keys are not read. Dart
     * looked like it fell back to them, but it called
     * `getString(key, defaultValue: '') ?? getString(legacyKey)`, and the
     * default makes the fallback unreachable; reading them now would revive
     * values the page cleared (a plan mode turned off). Verified against
     * `StorageService.getString`.
     */
    fun read(kind: Kind, conversationId: Int?, modelSource: String): String? {
        if (conversationId != null) {
            firstStored(kind, modelSource, conversationId)?.let { return it }
        }
        return firstStored(kind, modelSource, null)
    }

    /** Writes the global value and, when a conversation is known, its scoped value. */
    fun write(kind: Kind, value: String, conversationId: Int?, modelSource: String) {
        val normalized = value.trim()
        if (normalized.isEmpty()) return
        store.putString(key(PREFIX, kind, modelSource, null), normalized)
        if (conversationId != null) store.putString(key(PREFIX, kind, modelSource, conversationId), normalized)
    }

    fun clear(kind: Kind, conversationId: Int?, modelSource: String) {
        store.remove(key(PREFIX, kind, modelSource, null))
        if (conversationId != null) store.remove(key(PREFIX, kind, modelSource, conversationId))
    }

    /**
     * The settings a new turn starts from when the composer has none in
     * memory. Permission defaults to full access, the selector's initial
     * value; an explicit stored choice stays authoritative.
     */
    fun turnSettings(conversationId: Int?, modelSource: String): AgentTurnSettings = AgentTurnSettings(
        model = read(Kind.Model, conversationId, modelSource),
        reasoningEffort = normalizeAgentReasoningEffort(read(Kind.ReasoningEffort, conversationId, modelSource)),
        collaborationMode = read(Kind.CollaborationMode, conversationId, modelSource),
        permission = AgentPermissionMode.fromPreference(read(Kind.PermissionMode, conversationId, modelSource))
            ?: AgentPermissionMode.FullAccess,
    )

    private fun firstStored(kind: Kind, modelSource: String, conversationId: Int?): String? =
        store.getString(key(PREFIX, kind, modelSource, conversationId))?.trim()?.ifEmpty { null }

    private fun key(prefix: String, kind: Kind, modelSource: String, conversationId: Int?): String {
        val source = if (kind == Kind.Model) ".$modelSource" else ""
        val scope = if (conversationId == null) "global" else "conversation.$conversationId"
        // Flutter's shared_preferences stores every key under "flutter.".
        return "flutter.$prefix.${kind.storageKey}$source.$scope"
    }

    companion object {
        private const val PREFIX = "chat_agent_command_preference"

        fun forContext(context: Context): AgentCommandPreferences {
            val preferences = context.applicationContext
                .getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
            return AgentCommandPreferences(SharedPreferencesStore(preferences))
        }
    }

    private class SharedPreferencesStore(private val preferences: SharedPreferences) : KeyValueStore {
        override fun getString(key: String): String? = preferences.getString(key, null)
        override fun putString(key: String, value: String) = preferences.edit().putString(key, value).apply()
        override fun remove(key: String) = preferences.edit().remove(key).apply()
    }
}

/** The Agent settings one turn is sent with. */
data class AgentTurnSettings(
    val model: String?,
    val reasoningEffort: String?,
    val collaborationMode: String?,
    val permission: AgentPermissionMode,
)

/** Dart `_normalizeAgentReasoningEffort`: canonical spellings, unknown values kept. */
fun normalizeAgentReasoningEffort(value: String?): String? {
    val text = value?.trim()?.lowercase().orEmpty()
    if (text.isEmpty()) return null
    return when (text) {
        "no", "none", "off" -> "none"
        "min", "minimal", "minimum" -> "minimal"
        "med", "medium" -> "medium"
        "extra_high", "extra-high", "very_high", "very-high", "x-high", "x high", "xhigh" -> "xhigh"
        else -> text
    }
}
