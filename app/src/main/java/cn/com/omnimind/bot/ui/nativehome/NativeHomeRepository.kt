package cn.com.omnimind.bot.ui.nativehome

import cn.com.omnimind.bot.preferences.UiPreferencesStore
import android.content.Context
import android.content.SharedPreferences
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.util.LegacyFlutterPreferences
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.mcp.McpServerState
import cn.com.omnimind.bot.webchat.ConversationDomainService
import cn.com.omnimind.nativeui.ScheduledConversationTask
import cn.com.omnimind.nativeui.ConversationSummary
import cn.com.omnimind.nativeui.NativeHomeState
import cn.com.omnimind.nativeui.QuickPrompt
import cn.com.omnimind.nativeui.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Temporary read adapter for existing storage. It creates no database or preference namespace. */
internal class NativeHomeRepository(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)

    private val conversations = ConversationDomainService(this.context)
    private val scheduler = WorkspaceScheduledTaskScheduler(this.context)
    private val refreshRevision = MutableStateFlow(0)
    private val expansionWriteMutex = Mutex()
    private val preferenceChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()
    private val recentOnlyChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == "flutter.recent_conversations_only_enabled") trySend(Unit)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.map { preferences.getBoolean("flutter.recent_conversations_only_enabled", false) }.distinctUntilChanged()

    private val history = combine(
        DatabaseHelper.observeConversations(), refreshRevision, recentOnlyChanges,
    ) { _, _, recentOnly ->
        if (recentOnly) {
            conversations.archiveConversationsUpdatedBefore(System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
        }
        // This owner resolves legacy modes and each conversation's immutable Harness binding.
        conversations.listConversationPayloads(includeArchived = true).map { row ->
            ConversationSummary(
                id = (row.getValue("id") as Number).toLong(),
                title = row["title"]?.toString().orEmpty(),
                preview = row["lastMessage"]?.toString().orEmpty(),
                mode = row["mode"]?.toString().orEmpty(),
                updatedAt = (row["updatedAt"] as Number).toLong(),
                pinned = row["isPinned"] == true,
                parentId = (row["parentConversationId"] as? Number)?.toLong(),
                parentMode = row["parentConversationMode"] as? String,
                scheduledTaskId = row["scheduledTaskId"] as? String,
                agentId = row["agentId"] as? String,
                createdAt = (row["createdAt"] as Number).toLong(),
                archived = row["isArchived"] == true,
            )
        }
    }.flowOn(Dispatchers.IO)

    val snapshots = combine(history, preferenceChanges) { history, _ ->
        val hiddenIds = listOf("hidden_agent_conversation_ids", "hidden_codex_conversation_ids")
            .flatMap { LegacyFlutterPreferences.readStringList(preferences, "flutter.$it") }
            .mapNotNull(String::toLongOrNull).toSet()
        readPreferences().copy(
            conversations = history.filter {
                !(it.mode == "agent" && it.id in hiddenIds)
            },
            scheduledTasks = scheduler.listConversationTasks().mapNotNull { task ->
                val parentId = (task["parentConversationId"] ?: task["subagentConversationId"])
                    ?.toString()?.toLongOrNull() ?: return@mapNotNull null
                val parentMode = task["parentConversationMode"]?.toString()?.takeIf(String::isNotBlank)
                    ?.let(conversations::normalizeConversationMode)
                ScheduledConversationTask(task["id"]?.toString().orEmpty(), parentId, parentMode)
            },
            expandedSections = readExpandedSections(),
            loading = false,
        )
    }.flowOn(Dispatchers.IO)

    fun refresh() { refreshRevision.update { it + 1 } }

    private fun readExpandedSections(): Map<String, Boolean> = runCatching {
        val json = JSONObject(preferences.getString(EXPANDED_SECTIONS, "{}").orEmpty())
        json.keys().asSequence().associateWith { json.optBoolean(it, true) }
    }.getOrDefault(emptyMap())

    suspend fun setSectionExpanded(key: String, expanded: Boolean) = withContext(Dispatchers.IO) {
        expansionWriteMutex.withLock {
            val sections = readExpandedSections().toMutableMap().apply { put(key, expanded) }
            check(preferences.edit().putString(EXPANDED_SECTIONS, JSONObject(sections).toString()).commit())
        }
    }

    suspend fun setArchived(conversationId: Long, archived: Boolean) = withContext(Dispatchers.IO) {
        // Archive durable history through the shared domain owner. This never cancels an ACP turn.
        conversations.setConversationArchived(conversationId, archived)
    }

    fun readPreferences(): NativeHomeState {
        val saved = UiPreferencesStore.get(context).read()
        val english = resolveNativeHomeLocale(saved.language).language == "en"
        val quickPrompts = saved.home.prompts.map { prompt ->
            QuickPrompt(prompt.id, if (english) prompt.titleEn ?: prompt.title else prompt.title,
                if (english) prompt.promptEn ?: prompt.prompt else prompt.prompt)
        }.sortedBy { saved.home.pinnedIds.indexOf(it.id).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
        return NativeHomeState(
            theme = when (saved.theme) {
                "light" -> ThemePreference.Light
                "dark" -> ThemePreference.Dark
                else -> ThemePreference.System
            },
            workspaceMemoryConfigured = preferences.getBoolean("flutter.workspace_memory_configured", false),
            greetingEnabled = saved.home.greetingEnabled,
            quickPrompts = quickPrompts,
            recentConversationsOnly = preferences.getBoolean("flutter.recent_conversations_only_enabled", false),
            leftHanded = preferences.getString("flutter.habitual_hand", "right") == "left",
        )
    }

    suspend fun localServiceState(): McpServerState = withContext(Dispatchers.IO) {
        val state = if (McpServerManager.isPersistedEnabled()) McpServerManager.ensureRunning(context)
            else McpServerManager.currentState()
        state
    }

    suspend fun setLocalServiceEnabled(enabled: Boolean): McpServerState = withContext(Dispatchers.IO) {
        // McpServerManager remains the only owner of the service and token lifecycle.
        McpServerManager.setEnabled(context, enabled)
    }

    suspend fun refreshLocalServiceToken(): McpServerState = withContext(Dispatchers.IO) {
        McpServerManager.refreshToken(context)
    }

    private companion object {
        const val EXPANDED_SECTIONS = "flutter.home_drawer_expanded_sections_v1"
    }
}
