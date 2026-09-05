package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.assists.controller.http.HttpController
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.i18n.PromptLocale
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.ModelProviderProfile
import cn.com.omnimind.baselib.llm.ModelSceneRegistry
import cn.com.omnimind.baselib.llm.OmniOfficialProvider
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.llm.ProviderCustomHeaderUtils
import cn.com.omnimind.baselib.llm.SceneModelBindingStore
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.workspace.memory.LongTermMemoryIndex
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.sqrt

data class WorkspaceMemoryEmbeddingConfig(
    val enabled: Boolean,
    val configured: Boolean,
    val sceneId: String,
    val providerProfileId: String?,
    val providerProfileName: String?,
    val modelId: String?,
    val apiBase: String?,
    val hasApiKey: Boolean,
    val usesPlatform: Boolean = false,
)

data class WorkspaceMemorySearchHit(
    val id: String,
    val text: String,
    val source: String,
    val date: String?,
    val score: Double,
    /** Stable long-term entry slug; null for daily memory and lesson chunks. */
    val slug: String? = null
)

data class WorkspaceMemorySearchResult(
    val query: String,
    val usedEmbedding: Boolean,
    val fallbackLexical: Boolean,
    val hits: List<WorkspaceMemorySearchHit>
)

data class WorkspaceMemoryPromptContext(
    val soul: String,
    val longTermMemory: String,
    val todayShortMemory: String,
)

data class WorkspaceShortMemoryEntry(
    val id: String,
    val date: String,
    val time: String,
    val content: String,
    val timestampMillis: Long,
    val quickLogId: String? = null
)

data class WorkspaceMemoryRollupStatus(
    val enabled: Boolean,
    val lastRunAtMillis: Long?,
    val lastRunSummary: String?
)

internal data class MemoryChunk(
    val id: String,
    val source: String,
    val date: String?,
    val text: String,
    val slug: String? = null
)

internal data class MemoryIndexEntry(
    val id: String,
    val source: String,
    val date: String?,
    val text: String,
    val slug: String? = null,
    val embedding: List<Double> = emptyList(),
    val embeddingConfigId: String? = null,
    val embeddingDimensions: Int? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

internal fun WorkspaceMemoryEmbeddingConfig.embeddingConfigId(): String? {
    if (!configured) return null
    val normalizedModelId = modelId?.trim().orEmpty()
    if (normalizedModelId.isEmpty()) return null
    val normalizedApiBase = apiBase
        ?.let(ModelProviderConfigStore::stripDirectRequestUrlMarker)
        ?.trim()
        ?.trimEnd('/')
        .orEmpty()
    val rawIdentity = listOf(
        if (usesPlatform) "platform" else "byok",
        providerProfileId?.trim().orEmpty(),
        normalizedApiBase,
        normalizedModelId,
    ).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(rawIdentity.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun MemoryIndexEntry.canReuseFor(
    chunk: MemoryChunk,
    config: WorkspaceMemoryEmbeddingConfig,
    requestedEmbeddingConfigId: String?,
    expectedEmbeddingDimensions: Int?,
    shouldRequestEmbeddings: Boolean,
): Boolean {
    if (text != chunk.text) return false
    if (slug != chunk.slug) return false
    if (!config.configured || !shouldRequestEmbeddings) return true
    val storedDimensions = embeddingDimensions ?: return false
    return requestedEmbeddingConfigId != null &&
        embeddingConfigId == requestedEmbeddingConfigId &&
        embedding.isNotEmpty() &&
        storedDimensions == embedding.size &&
        (expectedEmbeddingDimensions == null || storedDimensions == expectedEmbeddingDimensions)
}

internal fun embeddingsAreCompatible(a: List<Double>, b: List<Double>): Boolean =
    a.isNotEmpty() && a.size == b.size

internal fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
    if (!embeddingsAreCompatible(a, b)) return 0.0
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        val av = a[i]
        val bv = b[i]
        dot += av * bv
        normA += av * av
        normB += bv * bv
    }
    if (normA <= 0 || normB <= 0) return 0.0
    return dot / (sqrt(normA) * sqrt(normB))
}

/** Every persisted short-memory day participates in semantic recall. */
internal fun shortMemoryFilesForIndex(files: Array<File>?): List<File> =
    files
        ?.filter { it.isFile && it.name.endsWith(".md") }
        ?.sortedByDescending { it.name }
        .orEmpty()

internal fun explicitByokEmbeddingProfile(
    bindingProviderProfileId: String?,
    boundProfile: ModelProviderProfile?,
): ModelProviderProfile? {
    val normalizedBindingId = bindingProviderProfileId?.trim().orEmpty()
    if (normalizedBindingId.isEmpty() ||
        OmniOfficialProvider.isOfficialProfile(normalizedBindingId)
    ) {
        return null
    }
    return boundProfile?.takeIf { it.id == normalizedBindingId }
}

private data class RollupInference(
    val summary: String?,
    val longTermCandidates: List<String>
)

class WorkspaceMemoryService(
    private val context: Context,
    private val workspaceManager: AgentWorkspaceManager = AgentWorkspaceManager(context)
) {
    companion object {
        const val SCENE_MEMORY_EMBEDDING = "scene.memory.embedding"
        const val SCENE_MEMORY_ROLLUP = "scene.memory.rollup"

        private const val TAG = "WorkspaceMemoryService"
        private const val ROLLUP_SUBMIT_TOOL = "submit_memory_rollup_result"
        private const val KEY_EMBEDDING_ENABLED = "workspace_memory_embedding_enabled_v1"
        private const val KEY_ROLLUP_ENABLED = "workspace_memory_rollup_enabled_v1"
        private const val KEY_ROLLUP_LAST_RUN_AT = "workspace_memory_rollup_last_run_at_v1"
        private const val KEY_ROLLUP_LAST_SUMMARY = "workspace_memory_rollup_last_summary_v1"
        private val QUICK_LOG_MARKER_REGEX =
            Regex("^\\[quick-log:([A-Za-z0-9-]+)]\\s*(.*)$")
        private val DAILY_TIME_PREFIX_REGEX =
            Regex("^\\[([0-2]\\d:[0-5]\\d:[0-5]\\d)]\\s*(.*)$")
        private val LEGACY_QUICK_LOG_PREFIX_REGEX =
            Regex("^(?:Quick log|\\u65e5\\u5fd7\\u901f\\u8bb0)[:\\uff1a]?\\s*", RegexOption.IGNORE_CASE)
        private val SHORT_MEMORY_FILE_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yy-MM-dd")
        // Services are created per request, so this lock must be process-wide
        // to protect read-modify-write memory updates across ACP agents.
        private val MEMORY_WRITE_LOCKS = ConcurrentHashMap<String, Any>()
    }

    private val gson = Gson()
    private val mmkv: MMKV? = MMKV.defaultMMKV()
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val platformEmbeddingGateway = PlatformEmbeddingGateway()

    private fun memoryWriteLock(): Any {
        val key = context.applicationContext.filesDir.absolutePath
        return MEMORY_WRITE_LOCKS.getOrPut(key) { Any() }
    }

    private fun currentLocale(): PromptLocale = AppLocaleManager.resolvePromptLocale(context)

    private fun t(zh: String, en: String): String {
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> zh
            PromptLocale.EN_US -> en
        }
    }

    private fun noDailyMemorySummary(): String =
        t("无当日短期记忆，跳过整理。", "No daily short-term memory found. Rollup skipped.")

    private fun emptyDailyMemorySummary(): String =
        t("当日短期记忆为空，跳过整理。", "The daily short-term memory file is empty. Rollup skipped.")

    private fun emptyTodayShortMemoryText(): String =
        t("（今日短期记忆为空）", "(Today's short-term memory is empty)")

    private fun emptyLongTermMemoryText(): String =
        t("（暂无长期记忆）", "(No long-term memory yet)")

    fun ensureInitialized() {
        workspaceManager.ensureRuntimeDirectories()
    }

    fun readSoul(): String {
        return AgentPromptSettingsStore.readSoul(context)
    }

    fun readChatPrompt(): String {
        return AgentPromptSettingsStore.readChatPrompt(context)
    }

    fun writeSoul(content: String) {
        AgentPromptSettingsStore.writeSoul(context, content)
    }

    fun writeChatPrompt(content: String) {
        AgentPromptSettingsStore.writeChatPrompt(context, content)
    }

    fun readLongTermMemory(): String {
        ensureInitialized()
        return workspaceManager.longTermMemoryMarkdownFile().readText()
    }

    fun writeLongTermMemory(content: String) {
        ensureInitialized()
        synchronized(memoryWriteLock()) {
            workspaceManager.longTermMemoryMarkdownFile().writeText(content.trimEnd() + "\n")
        }
    }

    fun readDailyMemory(date: LocalDate = LocalDate.now()): String {
        ensureInitialized()
        val file = workspaceManager.dailyShortMemoryFile(date)
        if (!file.exists()) {
            return ""
        }
        return file.readText()
    }

    fun appendDailyMemory(
        text: String,
        date: LocalDate = LocalDate.now()
    ): File {
        ensureInitialized()
        return synchronized(memoryWriteLock()) {
            val normalized = text.trim()
            require(normalized.isNotEmpty()) { "memory text is empty" }
            val file = workspaceManager.dailyShortMemoryFile(date)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText(
                    "# ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} Daily Memory\n\n"
                )
            }
            val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss")
                .format(LocalDateTime.now())
            file.appendText("- [$timestamp] $normalized\n")
            file
        }
    }

    fun listShortMemoryEntries(
        days: Int? = null,
        limit: Int? = null
    ): List<WorkspaceShortMemoryEntry> {
        ensureInitialized()
        val now = LocalDate.now()
        val entries = mutableListOf<WorkspaceShortMemoryEntry>()
        val dates = days?.let { requestedDays ->
            generateSequence(0) { offset -> offset + 1 }
                .take(requestedDays.coerceAtLeast(0))
                .map { offset -> now.minusDays(offset.toLong()) }
                .toList()
        } ?: workspaceManager.shortMemoriesDirectory()
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isFile && file.name.endsWith(".md") }
            .mapNotNull { file -> parseLocalDateFromFileName(file.nameWithoutExtension) }
            .sortedDescending()
            .toList()
        dates.forEach { date ->
            entries += parseDailyShortMemoryEntries(
                date = date,
                content = readDailyMemory(date)
            )
        }
        val sorted = entries.sortedWith(
            compareByDescending<WorkspaceShortMemoryEntry> { it.timestampMillis }
                .thenByDescending { it.id }
        )
        val seenQuickLogIds = mutableSetOf<String>()
        return sorted.filter { entry ->
            val quickLogId = entry.quickLogId
            quickLogId == null || seenQuickLogIds.add(quickLogId)
        }.let { uniqueEntries ->
            limit?.let(uniqueEntries::take) ?: uniqueEntries
        }
    }

    fun appendQuickLogMemory(
        logId: String,
        content: String,
        date: LocalDate = LocalDate.now()
    ): WorkspaceShortMemoryEntry {
        ensureInitialized()
        val normalizedLogId = logId.trim()
        val normalizedContent = normalizeQuickLogContent(content)
        require(normalizedLogId.isNotEmpty()) { "log id is empty" }
        require(normalizedContent.isNotEmpty()) { "memory text is empty" }

        return synchronized(memoryWriteLock()) {
            val file = workspaceManager.dailyShortMemoryFile(date)
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText("# ${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} Daily Memory\n\n")
            }
            val timestamp = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalDateTime.now())
            file.appendText("- [$timestamp] ${buildQuickLogBody(normalizedLogId, normalizedContent)}\n")
            parseDailyShortMemoryEntries(date, file.readText())
                .lastOrNull { it.quickLogId == normalizedLogId }
                ?: WorkspaceShortMemoryEntry(
                    id = normalizedLogId,
                    date = date.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    time = timestamp,
                    content = normalizedContent,
                    timestampMillis = parseTimestampMillis(date, timestamp),
                    quickLogId = normalizedLogId
                )
        }
    }

    fun updateQuickLogMemory(
        logId: String,
        previousContent: String,
        newContent: String
    ): WorkspaceShortMemoryEntry? {
        ensureInitialized()
        val normalizedLogId = logId.trim()
        val normalizedNewContent = normalizeQuickLogContent(newContent)
        require(normalizedLogId.isNotEmpty()) { "log id is empty" }
        require(normalizedNewContent.isNotEmpty()) { "memory text is empty" }

        val candidateFiles = workspaceManager.shortMemoriesDirectory()
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.sortedByDescending { it.name }
            .orEmpty()

        return synchronized(memoryWriteLock()) {
            candidateFiles.forEach { file ->
                val date = parseLocalDateFromFileName(file.nameWithoutExtension) ?: return@forEach
                val lines = file.readLines().toMutableList()
                val updated = updateQuickLogLineInMemoryFile(
                    lines = lines,
                    normalizedLogId = normalizedLogId,
                    normalizedPreviousContent = normalizeQuickLogContent(previousContent),
                    normalizedNewContent = normalizedNewContent
                )
                if (updated) {
                    writeDailyMemoryLines(file, lines)
                    return@synchronized parseDailyShortMemoryEntries(date, file.readText())
                        .firstOrNull { it.quickLogId == normalizedLogId }
                }
            }
            null
        }
    }

    fun deleteQuickLogMemory(logId: String, contentHint: String): Boolean {
        ensureInitialized()
        val normalizedLogId = logId.trim()
        require(normalizedLogId.isNotEmpty()) { "log id is empty" }

        val candidateFiles = workspaceManager.shortMemoriesDirectory()
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".md") }
            ?.sortedByDescending { it.name }
            .orEmpty()

        return synchronized(memoryWriteLock()) {
            candidateFiles.forEach { file ->
                val lines = file.readLines().toMutableList()
                val deleted = deleteQuickLogLineFromMemoryFile(
                    lines = lines,
                    normalizedLogId = normalizedLogId,
                    normalizedContentHint = normalizeQuickLogContent(contentHint)
                )
                if (deleted) {
                    writeDailyMemoryLines(file, lines)
                    return@synchronized true
                }
            }
            false
        }
    }

    fun appendLongTermMemory(text: String): Boolean {
        ensureInitialized()
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "memory text is empty" }
        val file = workspaceManager.longTermMemoryMarkdownFile()
        return synchronized(memoryWriteLock()) {
            file.appendText("- $normalized\n")
            true
        }
    }

    fun buildPromptContext(): WorkspaceMemoryPromptContext {
        ensureInitialized()
        val soul = readSoul().trim()
        val longMemory = readLongTermMemory().trim()
        val todayDaily = summarizeTodayShortMemory()
        return WorkspaceMemoryPromptContext(
            soul = soul,
            longTermMemory = longMemory,
            todayShortMemory = todayDaily,
        )
    }

    fun searchMemory(query: String, limit: Int = Int.MAX_VALUE): WorkspaceMemorySearchResult {
        ensureInitialized()
        val normalizedQuery = query.trim()
        require(normalizedQuery.isNotEmpty()) { "query is empty" }
        val embeddingConfig = resolveEmbeddingConfig()
        val chunks = collectChunks()
        val queryEmbedding = if (embeddingConfig.configured) {
            runCatching { requestEmbedding(embeddingConfig, normalizedQuery) }
                .onFailure {
                    OmniLog.w(TAG, "embedding query failed: ${it.message}")
                }
                .getOrNull()
                ?.takeIf { vector -> vector.isNotEmpty() && vector.all(Double::isFinite) }
        } else {
            null
        }
        val index = refreshAndLoadIndex(
            chunks = chunks,
            config = embeddingConfig,
            expectedEmbeddingDimensions = queryEmbedding?.size,
            shouldRequestEmbeddings = queryEmbedding != null,
        )
        val usedEmbedding = queryEmbedding != null &&
            index.any { embeddingsAreCompatible(queryEmbedding, it.embedding) }

        val scored = index.map { entry ->
            val lexical = lexicalScore(normalizedQuery, entry.text)
            val hasCompatibleEmbedding = queryEmbedding != null &&
                embeddingsAreCompatible(queryEmbedding, entry.embedding)
            val semantic = if (hasCompatibleEmbedding) {
                cosineSimilarity(queryEmbedding, entry.embedding)
            } else {
                0.0
            }
            val score = if (hasCompatibleEmbedding) {
                semantic * 0.82 + lexical * 0.18
            } else {
                lexical
            }
            WorkspaceMemorySearchHit(
                id = entry.id,
                text = entry.text,
                source = entry.source,
                date = entry.date,
                score = score,
                slug = entry.slug
            )
        }.sortedByDescending { it.score }
            .take(limit)
            .filter { it.score > 0.01 }

        return WorkspaceMemorySearchResult(
            query = normalizedQuery,
            usedEmbedding = usedEmbedding,
            fallbackLexical = !usedEmbedding,
            hits = scored
        )
    }

    fun rollupDay(date: LocalDate = LocalDate.now()): Map<String, Any?> {
        ensureInitialized()
        val dailyFile = workspaceManager.dailyShortMemoryFile(date)
        if (!dailyFile.exists()) {
            val summary = noDailyMemorySummary()
            saveRollupStatus(summary)
            return mapOf(
                "success" to true,
                "date" to date.toString(),
                "summary" to summary,
                "longTermWrites" to 0
            )
        }
        val content = dailyFile.readText()
        val lines = extractDailyLinesForRollup(content)

        if (lines.isEmpty()) {
            val summary = emptyDailyMemorySummary()
            saveRollupStatus(summary)
            return mapOf(
                "success" to true,
                "date" to date.toString(),
                "summary" to summary,
                "longTermWrites" to 0
            )
        }

        val longTermSnapshot = readLongTermMemory().trim()
        val rollupInference = inferRollupByLlm(
            date = date,
            dailyLines = lines,
            longTermMemory = longTermSnapshot
        )
        val longTermCandidates = (
            rollupInference?.longTermCandidates
                ?.takeIf { it.isNotEmpty() }
                ?: selectHeuristicLongTermCandidates(lines)
            )

        var writes = 0
        longTermCandidates.forEach { item ->
            val normalized = sanitizeLongTermCandidate(item)
            if (normalized.isNotEmpty() && appendLongTermMemory(normalized)) {
                writes += 1
            }
        }

        val rollupAt = Instant.now().toString()
        val aiSummary = rollupInference?.summary?.trim()?.takeIf { it.isNotEmpty() }
        val rollupSummary = if (aiSummary != null) {
            t(
                "$aiSummary（沉淀 $writes 条长期记忆）",
                "$aiSummary ($writes long-term memories written)"
            )
        } else {
            t(
                "已整理 ${lines.size} 条短期记忆，沉淀 $writes 条长期记忆。",
                "Rolled up ${lines.size} short-term memory entries and wrote $writes long-term memories."
            )
        }
        val rollupSource = if (rollupInference != null) "scene.memory.rollup" else "heuristic"
        dailyFile.appendText(
            "\n## Nightly Rollup @ $rollupAt\n" +
                "- source: $rollupSource\n" +
                "- inputLines: ${lines.size}\n" +
                "- $rollupSummary\n"
        )
        refreshAndLoadIndex(collectChunks(), resolveEmbeddingConfig())
        saveRollupStatus(rollupSummary)
        return mapOf(
            "success" to true,
            "date" to date.toString(),
            "summary" to rollupSummary,
            "longTermWrites" to writes,
            "usedAi" to (rollupInference != null),
            "fallbackHeuristic" to (rollupInference == null),
            "sourceScene" to SCENE_MEMORY_ROLLUP,
            "dailyLineCount" to lines.size
        )
    }

    fun getEmbeddingConfigForUi(): WorkspaceMemoryEmbeddingConfig {
        ensureInitialized()
        return resolveEmbeddingConfig()
    }

    fun saveEmbeddingConfigForUi(
        enabled: Boolean,
        providerProfileId: String? = null,
        modelId: String? = null
    ): WorkspaceMemoryEmbeddingConfig {
        ensureInitialized()
        mmkv?.encode(KEY_EMBEDDING_ENABLED, enabled)
        val normalizedProfileId = providerProfileId?.trim().orEmpty()
        val normalizedModelId = modelId?.trim().orEmpty()
        if (normalizedProfileId.isNotEmpty() && normalizedModelId.isNotEmpty()) {
            SceneModelBindingStore.saveBinding(
                SCENE_MEMORY_EMBEDDING,
                normalizedProfileId,
                normalizedModelId
            )
        }
        return resolveEmbeddingConfig()
    }

    fun getRollupStatusForUi(): WorkspaceMemoryRollupStatus {
        val enabled = mmkv?.decodeBool(KEY_ROLLUP_ENABLED, false) ?: false
        val lastRunAt = mmkv?.decodeLong(KEY_ROLLUP_LAST_RUN_AT, 0L)?.takeIf { it > 0 }
        val lastSummary = mmkv?.decodeString(KEY_ROLLUP_LAST_SUMMARY)?.trim()?.ifEmpty { null }
        return WorkspaceMemoryRollupStatus(
            enabled = enabled,
            lastRunAtMillis = lastRunAt,
            lastRunSummary = lastSummary
        )
    }

    fun saveRollupEnabled(enabled: Boolean): WorkspaceMemoryRollupStatus {
        mmkv?.encode(KEY_ROLLUP_ENABLED, enabled)
        return getRollupStatusForUi()
    }

    fun isRollupEnabled(): Boolean {
        return mmkv?.decodeBool(KEY_ROLLUP_ENABLED, false) ?: false
    }

    private fun saveRollupStatus(summary: String) {
        mmkv?.encode(KEY_ROLLUP_LAST_RUN_AT, System.currentTimeMillis())
        mmkv?.encode(KEY_ROLLUP_LAST_SUMMARY, summary)
    }

    private fun summarizeTodayShortMemory(): String {
        val today = readDailyMemory(LocalDate.now())
        if (today.isBlank()) {
            return emptyTodayShortMemoryText()
        }
        val lines = today.lineSequence()
            .mapNotNull { raw ->
                val line = raw.trim()
                if (!line.startsWith("- ")) {
                    return@mapNotNull null
                }
                val item = line.removePrefix("- ").trim()
                if (item.isEmpty()) {
                    return@mapNotNull null
                }
                val (timeText, bodyText) = extractTimestampAndBody(item)
                val content = stripQuickLogDecorators(bodyText)
                if (content.isEmpty() || isRollupMetadataLine(content)) {
                    return@mapNotNull null
                }
                val prefix = if (timeText.isEmpty()) "" else "[$timeText] "
                "- $prefix$content"
            }
            .toList()
        return if (lines.isEmpty()) emptyTodayShortMemoryText() else lines.joinToString("\n")
    }

    private fun extractDailyLinesForRollup(content: String): List<String> {
        val lines = mutableListOf<String>()
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("- ")) {
                return@forEach
            }
            val item = line.removePrefix("- ").trim()
            if (item.isEmpty() || isRollupMetadataLine(item)) {
                return@forEach
            }
            val normalized = normalizeRollupLine(item)
            if (normalized.isNotEmpty()) {
                lines += normalized
            }
        }
        return lines
    }

    private fun isRollupMetadataLine(item: String): Boolean {
        val lower = item.lowercase(Locale.getDefault())
        return lower.startsWith("source:") ||
            lower.startsWith("inputlines:") ||
            lower.startsWith("rolled up ") ||
            (item.startsWith("已整理") && item.contains("条短期记忆")) ||
            (item.contains("沉淀") && item.contains("长期记忆")) ||
            (lower.contains("long-term memories") && lower.contains("written"))
    }

    private fun normalizeRollupLine(raw: String): String {
        return raw
            .replace(Regex("^\\[[0-2]\\d:[0-5]\\d:[0-5]\\d]\\s*"), "")
            .replace(QUICK_LOG_MARKER_REGEX, "$2")
            .replace(LEGACY_QUICK_LOG_PREFIX_REGEX, "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun selectHeuristicLongTermCandidates(lines: List<String>): List<String> {
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { raw ->
                val lower = raw.lowercase(Locale.getDefault())
                lower.startsWith("长期:") ||
                    lower.startsWith("long-term:") ||
                    raw.length >= 18
            }
            .map(::sanitizeLongTermCandidate)
            .filter { it.isNotEmpty() }
    }

    private fun inferRollupByLlm(
        date: LocalDate,
        dailyLines: List<String>,
        longTermMemory: String
    ): RollupInference? {
        if (dailyLines.isEmpty()) {
            return null
        }

        val toolResponse = runCatching {
            val request = buildRollupToolRequest(
                date = date,
                dailyLines = dailyLines,
                longTermMemory = longTermMemory
            )
            runBlocking {
                HttpController.postSceneChatCompletion(request)
            }
        }.onFailure {
            OmniLog.w(TAG, "rollup tool-call request failed: ${it.message}")
        }.getOrNull()

        if (toolResponse != null && toolResponse.success) {
            parseRollupInferenceFromToolCalls(toolResponse.toolCalls)?.let { return it }
            val contentInference = parseRollupInference(toolResponse.content)
            if (contentInference != null) {
                return contentInference
            }
            OmniLog.w(TAG, "rollup tool-call parse empty; fallback to legacy prompt")
        } else if (toolResponse != null) {
            OmniLog.w(
                TAG,
                "rollup tool-call unsuccessful code=${toolResponse.code} message=${toolResponse.message}"
            )
        }

        val prompt = buildRollupLegacyPrompt(
            date = date,
            dailyLines = dailyLines,
            longTermMemory = longTermMemory
        )
        val responseText = runCatching {
            runBlocking {
                HttpController.postLLMRequest(SCENE_MEMORY_ROLLUP, prompt).message
            }
        }.onFailure {
            OmniLog.w(TAG, "rollup legacy llm request failed: ${it.message}")
        }.getOrNull()?.trim().orEmpty()
        if (responseText.isEmpty()) {
            return null
        }
        val parsed = parseRollupInference(responseText)
        if (parsed == null) {
            OmniLog.w(TAG, "rollup legacy llm parse failed, fallback heuristic")
        }
        return parsed
    }

    private fun buildRollupToolRequest(
        date: LocalDate,
        dailyLines: List<String>,
        longTermMemory: String
    ): ChatCompletionRequest {
        val parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "dailySummary",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    t(
                                        "当日短期记忆的总结。",
                                        "A summary of the day's short-term memory."
                                    )
                                )
                            )
                        }
                    )
                    put(
                        "longTermCandidates",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    t(
                                        "可沉淀为长期记忆的稳定信息列表。",
                                        "Stable facts that should be promoted into long-term memory."
                                    )
                                )
                            )
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                }
                            )
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("dailySummary"))
                    add(JsonPrimitive("longTermCandidates"))
                }
            )
        }
        return ChatCompletionRequest(
            model = SCENE_MEMORY_ROLLUP,
            messages = listOf(
                ChatCompletionMessage(
                    role = "system",
                    content = JsonPrimitive(buildRollupToolSystemPrompt())
                ),
                ChatCompletionMessage(
                    role = "user",
                    content = JsonPrimitive(
                        buildRollupToolUserPrompt(
                            date = date,
                            dailyLines = dailyLines,
                            longTermMemory = longTermMemory
                        )
                    )
                )
            ),
            maxCompletionTokens = null,
            temperature = 0.2,
            tools = listOf(
                ChatCompletionTool(
                    function = ChatCompletionFunction(
                        name = ROLLUP_SUBMIT_TOOL,
                        description = t(
                            "提交 Workspace 当日记忆整理结果。",
                            "Submit the workspace daily-memory rollup result."
                        ),
                        parameters = parameters
                    )
                )
            ),
            parallelToolCalls = false
        )
    }

    private fun buildRollupToolSystemPrompt(): String {
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> """
                你是 Workspace 记忆整理助手。
                目标：基于当日短期记忆，输出当日总结，并筛选可沉淀为长期记忆的信息。

                规则：
                1. 只保留长期稳定且对未来任务有帮助的信息（偏好、长期约束、稳定事实）。
                2. 忽略一次性临时细节、随机聊天内容、瞬时状态。
                3. 候选长期记忆每条一句话，中文为主，避免重复。
                4. 如果没有可沉淀内容，longTermCandidates 返回空数组。
                5. 必须通过工具 $ROLLUP_SUBMIT_TOOL 提交结果，不要输出普通文本。
            """.trimIndent()
            PromptLocale.EN_US -> """
                You are the Workspace memory rollup assistant.
                Goal: summarize the day's short-term memory and identify information that should be promoted into long-term memory.

                Rules:
                1. Keep only stable information that will still help future tasks, such as preferences, long-term constraints, and durable facts.
                2. Ignore one-off temporary details, random chat content, and transient states.
                3. Each long-term candidate must be a single sentence, with no duplicates.
                4. If nothing should be promoted, return an empty longTermCandidates array.
                5. You must submit the result through the $ROLLUP_SUBMIT_TOOL tool and must not output normal text.
            """.trimIndent()
        }
    }

    private fun buildRollupToolUserPrompt(
        date: LocalDate,
        dailyLines: List<String>,
        longTermMemory: String
    ): String {
        val dailyBlock = dailyLines.joinToString("\n") { "- $it" }
        val longTermBlock = longTermMemory.ifBlank { emptyLongTermMemoryText() }
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> """
                日期：$date

                当日短期记忆原文：
                $dailyBlock

                现有长期记忆（用于避免重复）：
                $longTermBlock
            """.trimIndent()
            PromptLocale.EN_US -> """
                Date: $date

                Daily short-term memory:
                $dailyBlock

                Existing long-term memory (to avoid duplicates):
                $longTermBlock
            """.trimIndent()
        }
    }

    private fun buildRollupLegacyPrompt(
        date: LocalDate,
        dailyLines: List<String>,
        longTermMemory: String
    ): String {
        val dailyBlock = dailyLines.joinToString("\n") { "- $it" }
        val longTermBlock = longTermMemory.ifBlank { emptyLongTermMemoryText() }
        return when (currentLocale()) {
            PromptLocale.ZH_CN -> """
                你是 Workspace 记忆整理助手。请基于当日短期记忆，为用户生成当日总结，并筛选可沉淀为长期记忆的信息。

                规则：
                1. 只保留长期稳定且对未来任务有帮助的信息（偏好、长期约束、稳定事实）。
                2. 忽略一次性临时细节、随机聊天内容、瞬时状态。
                3. 候选长期记忆每条一句话，中文为主，避免重复。
                4. 如果没有可沉淀内容，longTermCandidates 返回空数组。
                5. 只能输出 JSON，不要输出 Markdown 代码块或解释。

                输出格式：
                {
                  "dailySummary": "当日总结",
                  "longTermCandidates": ["候选1", "候选2"]
                }

                日期：$date

                当日短期记忆原文：
                $dailyBlock

                现有长期记忆（用于避免重复）：
                $longTermBlock
            """.trimIndent()
            PromptLocale.EN_US -> """
                You are the Workspace memory rollup assistant. Based on the day's short-term memory, generate a daily summary and identify information that should become long-term memory.

                Rules:
                1. Keep only stable information that will help future tasks, such as preferences, long-term constraints, and durable facts.
                2. Ignore one-off temporary details, random chat content, and transient states.
                3. Each long-term candidate must be a single sentence, with no duplicates.
                4. If nothing should be promoted, return an empty longTermCandidates array.
                5. Output JSON only. Do not output Markdown code fences or explanations.

                Output format:
                {
                  "dailySummary": "one-sentence summary",
                  "longTermCandidates": ["candidate 1", "candidate 2"]
                }

                Date: $date

                Daily short-term memory:
                $dailyBlock

                Existing long-term memory (to avoid duplicates):
                $longTermBlock
            """.trimIndent()
        }
    }

    private fun parseRollupInferenceFromToolCalls(toolCalls: List<AssistantToolCall>): RollupInference? {
        if (toolCalls.isEmpty()) {
            return null
        }
        val preferred = toolCalls.firstOrNull {
            it.function.name.trim().equals(ROLLUP_SUBMIT_TOOL, ignoreCase = true)
        } ?: toolCalls.firstOrNull() ?: return null
        val rawArguments = preferred.function.arguments.trim()
        if (rawArguments.isEmpty()) {
            return null
        }
        val jsonText = extractFirstJsonObject(rawArguments) ?: rawArguments
        val payload = runCatching { JSONObject(jsonText) }
            .onFailure { OmniLog.w(TAG, "rollup tool args parse failed: ${it.message}") }
            .getOrNull() ?: return null
        val summary = firstNonBlank(payload, listOf("dailySummary", "summary", "todaySummary"))
        val candidates = extractLongTermCandidates(payload)
        if (summary.isNullOrBlank() && candidates.isEmpty()) {
            return null
        }
        return RollupInference(
            summary = summary,
            longTermCandidates = candidates
        )
    }

    private fun parseRollupInference(raw: String): RollupInference? {
        val jsonText = extractFirstJsonObject(raw) ?: return null
        val payload = runCatching { JSONObject(jsonText) }
            .onFailure { OmniLog.w(TAG, "rollup parse json failed: ${it.message}") }
            .getOrNull() ?: return null
        val summary = firstNonBlank(payload, listOf("dailySummary", "summary", "todaySummary"))
        val candidates = extractLongTermCandidates(payload)
        return RollupInference(
            summary = summary,
            longTermCandidates = candidates
        )
    }

    private fun extractLongTermCandidates(payload: JSONObject): List<String> {
        val candidateArray = listOf(
            "longTermCandidates",
            "long_term_candidates",
            "longTermMemories",
            "long_term_memories",
            "memoryCandidates"
        ).asSequence()
            .mapNotNull { key -> payload.optJSONArray(key) }
            .firstOrNull()
            ?: JSONArray()

        val items = mutableListOf<String>()
        for (index in 0 until candidateArray.length()) {
            val raw = candidateArray.opt(index)
            val value = when (raw) {
                is JSONObject -> firstNonBlank(raw, listOf("text", "memory", "content", "fact"))
                else -> raw?.toString()
            }.orEmpty()
            val normalized = sanitizeLongTermCandidate(value)
            if (normalized.isNotEmpty()) {
                items += normalized
            }
        }
        return items
    }

    private fun firstNonBlank(payload: JSONObject, keys: List<String>): String? {
        keys.forEach { key ->
            val value = payload.optString(key).trim()
            if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) {
                return value
            }
        }
        return null
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
        if (!fence.isNullOrBlank()) {
            return extractFirstJsonObject(fence)
        }
        val start = trimmed.indexOf('{')
        if (start < 0) {
            return null
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until trimmed.length) {
            val ch = trimmed[index]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return trimmed.substring(start, index + 1)
                    }
                }
            }
        }
        return null
    }

    private fun sanitizeLongTermCandidate(raw: String): String {
        return raw.trim()
            .replace(Regex("^(?:[-*]|\\d+[.)、])\\s*"), "")
            .replace(Regex("^长期[:：]\\s*"), "")
            .replace(Regex("^long[- ]?term[:：]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun resolveEmbeddingConfig(): WorkspaceMemoryEmbeddingConfig {
        val enabled = mmkv?.decodeBool(KEY_EMBEDDING_ENABLED, true) ?: true
        val sceneProfile = ModelSceneRegistry.getRuntimeProfile(SCENE_MEMORY_EMBEDDING)
        val binding = SceneModelBindingStore.getBinding(SCENE_MEMORY_EMBEDDING)
        val boundProfile = binding?.providerProfileId
            ?.let(ModelProviderConfigStore::getProfile)
        val explicitByokProfile = explicitByokEmbeddingProfile(
            bindingProviderProfileId = binding?.providerProfileId,
            boundProfile = boundProfile,
        )
        if (explicitByokProfile != null) {
            return resolveByokEmbeddingConfig(
                enabled = enabled,
                profile = explicitByokProfile,
                modelId = binding?.modelId,
            )
        }
        if (OmniOfficialProvider.shouldExpose()) {
            // A process can already be text-ready while still holding the
            // catalog cached before embedding was published. Refresh that
            // incomplete catalog before deciding to fall back to lexical
            // retrieval.
            val platformStatus = if (enabled) {
                runBlocking { PlatformAiProvisioner.ensureEmbeddingReadyStatus() }
            } else {
                PlatformAiProvisioner.status()
            }
            val platformProfile = PlatformAiProvisioner.officialProfileOrNull()
            val platformModelId = platformStatus.defaultEmbeddingModelId
            val declared = platformStatus.embeddingModels.any { it.id == platformModelId }
            return WorkspaceMemoryEmbeddingConfig(
                enabled = enabled,
                configured = enabled &&
                    platformStatus.ready &&
                    platformProfile?.ready == true &&
                    !platformModelId.isNullOrBlank() &&
                    declared,
                sceneId = SCENE_MEMORY_EMBEDDING,
                providerProfileId = OmniOfficialProvider.PROFILE_ID,
                providerProfileName = OmniOfficialProvider.PROFILE_NAME,
                modelId = platformModelId,
                apiBase = platformProfile?.baseUrl,
                hasApiKey = false,
                usesPlatform = true,
            )
        }
        val applicableBinding = binding?.takeIf {
            boundProfile != null && !OmniOfficialProvider.isOfficialProfile(it.providerProfileId)
        }
        val profile = boundProfile
            ?.takeIf { applicableBinding != null }
            ?: ModelProviderConfigStore.getEditingProfile()
        val modelId = applicableBinding?.modelId?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: sceneProfile?.model?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("scene.") }
        return resolveByokEmbeddingConfig(
            enabled = enabled,
            profile = profile,
            modelId = modelId,
        )
    }

    private fun resolveByokEmbeddingConfig(
        enabled: Boolean,
        profile: ModelProviderProfile,
        modelId: String?,
    ): WorkspaceMemoryEmbeddingConfig {
        val normalizedModelId = modelId?.trim()?.takeIf(String::isNotEmpty)
        val apiBase = profile.baseUrl.trim().ifEmpty { null }
        val apiKey = profile.apiKey.trim()
        if (profile.protocolType == "anthropic") {
            return WorkspaceMemoryEmbeddingConfig(
                enabled = enabled,
                configured = false,
                sceneId = SCENE_MEMORY_EMBEDDING,
                providerProfileId = profile.id,
                providerProfileName = profile.name,
                modelId = normalizedModelId,
                apiBase = apiBase,
                hasApiKey = apiKey.isNotEmpty(),
            )
        }
        val configured = enabled &&
            !apiBase.isNullOrBlank() &&
            apiKey.isNotEmpty() &&
            !normalizedModelId.isNullOrBlank()
        return WorkspaceMemoryEmbeddingConfig(
            enabled = enabled,
            configured = configured,
            sceneId = SCENE_MEMORY_EMBEDDING,
            providerProfileId = profile.id,
            providerProfileName = profile.name,
            modelId = normalizedModelId,
            apiBase = apiBase,
            hasApiKey = apiKey.isNotEmpty(),
        )
    }

    private fun collectChunks(): List<MemoryChunk> {
        val chunks = mutableListOf<MemoryChunk>()
        val longTermContent = readLongTermMemory()
        chunks += splitLongTermMemoryToChunks(
            source = ".omnibot/memory/MEMORY.md",
            content = longTermContent
        )

        val shortDir = workspaceManager.shortMemoriesDirectory()
        shortMemoryFilesForIndex(shortDir.listFiles())
            .forEach { file ->
                val date = file.nameWithoutExtension
                chunks += splitMarkdownToChunks(
                    source = ".omnibot/memory/short-memories/${file.name}",
                    date = date,
                    content = file.readText()
                )
            }

        return chunks
    }

    private fun splitMarkdownToChunks(
        source: String,
        date: String?,
        content: String
    ): List<MemoryChunk> {
        if (content.isBlank()) return emptyList()
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("#") }
            .map {
                val value = if (it.startsWith("- ")) it.removePrefix("- ").trim() else it
                stripQuickLogDecorators(value)
            }
            .filter { it.isNotEmpty() }
            .toList()
        val merged = mutableListOf<String>()
        val buffer = StringBuilder()
        lines.forEach { line ->
            if (buffer.length + line.length + 1 > 260) {
                val value = buffer.toString().trim()
                if (value.isNotEmpty()) {
                    merged += value
                }
                buffer.clear()
            }
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
        }
        val tail = buffer.toString().trim()
        if (tail.isNotEmpty()) {
            merged += tail
        }
        return merged.map { text ->
            MemoryChunk(
                id = stableChunkId(source, date, text),
                source = source,
                date = date,
                text = text
            )
        }
    }

    /**
     * Long-term memory has a second public address: `memory_load(slug)`.
     * Keep that address attached to the indexed chunk instead of exposing the
     * internal embedding chunk hash as if it were a loadable slug.
     */
    private fun splitLongTermMemoryToChunks(
        source: String,
        content: String
    ): List<MemoryChunk> {
        if (content.isBlank()) return emptyList()
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("- ") }
            .map { it.removePrefix("- ").trim() }
            .map(::stripQuickLogDecorators)
            .filter { it.isNotEmpty() }
            .map { text ->
                MemoryChunk(
                    id = stableChunkId(source, null, text),
                    source = source,
                    date = null,
                    text = text,
                    slug = LongTermMemoryIndex.makeSlug(text)
                )
            }
            .toList()
    }

    private fun refreshAndLoadIndex(
        chunks: List<MemoryChunk>,
        config: WorkspaceMemoryEmbeddingConfig,
        expectedEmbeddingDimensions: Int? = null,
        shouldRequestEmbeddings: Boolean = config.configured,
    ): List<MemoryIndexEntry> {
        val indexFile = File(workspaceManager.memoryIndexDirectory(), "index.json")
        val existing = loadIndex(indexFile).associateBy { it.id }.toMutableMap()
        val requestedEmbeddingConfigId = config.embeddingConfigId()
        val next = mutableListOf<MemoryIndexEntry>()
        chunks.forEach { chunk ->
            val old = existing.remove(chunk.id)
            if (old?.canReuseFor(
                    chunk = chunk,
                    config = config,
                    requestedEmbeddingConfigId = requestedEmbeddingConfigId,
                    expectedEmbeddingDimensions = expectedEmbeddingDimensions,
                    shouldRequestEmbeddings = shouldRequestEmbeddings,
                ) == true
            ) {
                next += requireNotNull(old)
                return@forEach
            }
            val embedding = if (config.configured && shouldRequestEmbeddings) {
                runCatching { requestEmbedding(config, chunk.text) }
                    .onFailure { OmniLog.w(TAG, "embedding chunk failed: ${it.message}") }
                    .getOrElse { emptyList() }
                    .takeIf { vector ->
                        vector.isNotEmpty() &&
                            vector.all(Double::isFinite) &&
                            (
                                expectedEmbeddingDimensions == null ||
                                    vector.size == expectedEmbeddingDimensions
                                )
                    }
                    .orEmpty()
            } else {
                emptyList()
            }
            next += MemoryIndexEntry(
                id = chunk.id,
                source = chunk.source,
                date = chunk.date,
                text = chunk.text,
                slug = chunk.slug,
                embedding = embedding,
                embeddingConfigId = requestedEmbeddingConfigId.takeIf { embedding.isNotEmpty() },
                embeddingDimensions = embedding.size.takeIf { it > 0 },
            )
        }
        saveIndex(indexFile, next)
        return next
    }

    private fun loadIndex(indexFile: File): List<MemoryIndexEntry> {
        if (!indexFile.exists()) return emptyList()
        val raw = runCatching { indexFile.readText() }.getOrDefault("")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<MemoryIndexEntry>>() {}.type
            gson.fromJson<List<MemoryIndexEntry>>(raw, type) ?: emptyList()
        }.getOrElse {
            OmniLog.w(TAG, "parse index failed: ${it.message}")
            emptyList()
        }
    }

    private fun saveIndex(indexFile: File, entries: List<MemoryIndexEntry>) {
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(gson.toJson(entries))
    }

    private fun requestEmbedding(
        config: WorkspaceMemoryEmbeddingConfig,
        text: String
    ): List<Double> {
        check(config.configured) { "embedding config not ready" }
        if (config.usesPlatform) {
            return runBlocking {
                platformEmbeddingGateway.embed(config.modelId.orEmpty(), text)
            }
        }
        val apiBase = ModelProviderConfigStore.stripDirectRequestUrlMarker(config.apiBase!!)
        val modelId = config.modelId!!.trim()
        val profile = config.providerProfileId?.let { ModelProviderConfigStore.getProfile(it) }
            ?: ModelProviderConfigStore.getEditingProfile()
        val apiKey = profile.apiKey.trim()
        val url = if (apiBase.endsWith("/v1", ignoreCase = true)) {
            "$apiBase/embeddings"
        } else {
            "$apiBase/v1/embeddings"
        }
        val requestJson = JSONObject().apply {
            put("model", modelId)
            put("input", JSONArray().put(text))
        }
        val mergedHeaders = ProviderCustomHeaderUtils.mergeHeaders(
            builtIn = linkedMapOf(
                "Content-Type" to "application/json",
                "Authorization" to "Bearer $apiKey"
            ),
            custom = profile.customHeaders
        )
        val request = Request.Builder()
            .url(url)
            .apply {
                mergedHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "embedding request failed(${response.code}): ${body.take(320)}"
                )
            }
            val payload = JSONObject(body)
            val data = payload.optJSONArray("data") ?: JSONArray()
            if (data.length() == 0) return emptyList()
            val first = data.optJSONObject(0) ?: return emptyList()
            val embedding = first.optJSONArray("embedding") ?: JSONArray()
            return buildList {
                for (i in 0 until embedding.length()) {
                    add(embedding.optDouble(i))
                }
            }
        }
    }

    private fun lexicalScore(query: String, text: String): Double {
        val nq = normalizeText(query)
        val nt = normalizeText(text)
        if (nq.isEmpty() || nt.isEmpty()) return 0.0
        if (nt.contains(nq)) {
            return 1.0
        }
        val qTokens = tokenize(query)
        val tTokens = tokenize(text).toSet()
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0
        val hit = qTokens.count { tTokens.contains(it) }
        return hit.toDouble() / qTokens.size.toDouble()
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase(Locale.getDefault())
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
    }

    private fun normalizeText(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), "")
            .trim()
    }

    private fun stableChunkId(source: String, date: String?, text: String): String {
        val raw = "$source|${date.orEmpty()}|${normalizeText(text)}"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(24)
    }

    private fun parseDailyShortMemoryEntries(
        date: LocalDate,
        content: String
    ): List<WorkspaceShortMemoryEntry> {
        if (content.isBlank()) {
            return emptyList()
        }
        val dateText = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val entries = mutableListOf<WorkspaceShortMemoryEntry>()
        var lineIndex = 0
        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("- ")) {
                return@forEach
            }
            val item = line.removePrefix("- ").trim()
            if (item.isEmpty()) {
                return@forEach
            }
            val (timeText, bodyText) = extractTimestampAndBody(item)
            val quickLogId = extractQuickLogId(bodyText)
            val displayContent = stripQuickLogDecorators(bodyText)
            if (displayContent.isEmpty() || isRollupMetadataLine(displayContent)) {
                return@forEach
            }
            entries += WorkspaceShortMemoryEntry(
                id = quickLogId ?: makeStableShortMemoryId(dateText, lineIndex, displayContent),
                date = dateText,
                time = timeText.ifEmpty { "00:00:00" },
                content = displayContent,
                timestampMillis = parseTimestampMillis(date, timeText),
                quickLogId = quickLogId
            )
            lineIndex += 1
        }
        return entries
    }

    private fun updateQuickLogLineInMemoryFile(
        lines: MutableList<String>,
        normalizedLogId: String,
        normalizedPreviousContent: String,
        normalizedNewContent: String
    ): Boolean {
        val matchedIndexes = mutableListOf<Int>()
        var lastMatchedTimeText = ""
        for (index in lines.indices) {
            val raw = lines[index].trim()
            if (!raw.startsWith("- ")) {
                continue
            }
            val item = raw.removePrefix("- ").trim()
            val (timeText, bodyText) = extractTimestampAndBody(item)
            val quickLogId = extractQuickLogId(bodyText)
            val displayContent = normalizeQuickLogContent(stripQuickLogDecorators(bodyText))
            val matches = quickLogId == normalizedLogId ||
                (quickLogId == null &&
                    normalizedPreviousContent.isNotEmpty() &&
                    displayContent == normalizedPreviousContent)
            if (!matches) {
                continue
            }
            matchedIndexes += index
            if (timeText.isNotEmpty()) {
                lastMatchedTimeText = timeText
            }
        }
        val targetIndex = matchedIndexes.lastOrNull() ?: return false
        for (index in matchedIndexes.asReversed()) {
            if (index != targetIndex) {
                lines.removeAt(index)
            }
        }
        val prefix = if (lastMatchedTimeText.isEmpty()) "" else "[$lastMatchedTimeText] "
        lines[targetIndex - (matchedIndexes.size - 1)] =
            "- $prefix${buildQuickLogBody(normalizedLogId, normalizedNewContent)}"
        return true
    }

    private fun deleteQuickLogLineFromMemoryFile(
        lines: MutableList<String>,
        normalizedLogId: String,
        normalizedContentHint: String
    ): Boolean {
        val matchedIndexes = mutableListOf<Int>()
        for (index in lines.indices) {
            val raw = lines[index].trim()
            if (!raw.startsWith("- ")) {
                continue
            }
            val item = raw.removePrefix("- ").trim()
            val (_, bodyText) = extractTimestampAndBody(item)
            val quickLogId = extractQuickLogId(bodyText)
            val displayContent = normalizeQuickLogContent(stripQuickLogDecorators(bodyText))
            val matches = quickLogId == normalizedLogId ||
                (quickLogId == null &&
                    normalizedContentHint.isNotEmpty() &&
                    displayContent == normalizedContentHint)
            if (!matches) {
                continue
            }
            matchedIndexes += index
        }
        if (matchedIndexes.isEmpty()) {
            return false
        }
        matchedIndexes.asReversed().forEach { index ->
            lines.removeAt(index)
        }
        return true
    }

    private fun writeDailyMemoryLines(file: File, lines: List<String>) {
        val text = if (lines.isEmpty()) "" else lines.joinToString("\n").trimEnd() + "\n"
        file.writeText(text)
    }

    private fun extractTimestampAndBody(item: String): Pair<String, String> {
        val match = DAILY_TIME_PREFIX_REGEX.find(item)
        if (match == null) {
            return "" to item.trim()
        }
        return (match.groupValues.getOrNull(1)?.trim().orEmpty()) to
            (match.groupValues.getOrNull(2)?.trim().orEmpty())
    }

    private fun buildQuickLogBody(logId: String, content: String): String {
        return "[quick-log:$logId] $content"
    }

    private fun extractQuickLogId(bodyText: String): String? {
        val match = QUICK_LOG_MARKER_REGEX.find(bodyText.trim()) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun stripQuickLogDecorators(raw: String): String {
        val withoutMarker = raw.trim().replace(QUICK_LOG_MARKER_REGEX, "$2").trim()
        return withoutMarker.replace(LEGACY_QUICK_LOG_PREFIX_REGEX, "").trim()
    }

    private fun normalizeQuickLogContent(raw: String): String {
        return stripQuickLogDecorators(raw)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun parseLocalDateFromFileName(value: String): LocalDate? {
        return runCatching {
            LocalDate.parse(value, SHORT_MEMORY_FILE_DATE_FORMAT)
        }.recoverCatching {
            LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)
        }.getOrNull()
    }

    private fun parseTimestampMillis(date: LocalDate, timeText: String): Long {
        val localTime = runCatching {
            LocalTime.parse(timeText.ifEmpty { "00:00:00" })
        }.getOrNull() ?: LocalTime.MIDNIGHT
        return LocalDateTime.of(date, localTime)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    private fun makeStableShortMemoryId(date: String, lineIndex: Int, content: String): String {
        return stableChunkId(
            source = ".omnibot/memory/short-memories",
            date = date,
            text = "$lineIndex|$content"
        )
    }
}
