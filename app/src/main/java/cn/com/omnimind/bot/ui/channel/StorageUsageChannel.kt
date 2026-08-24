package cn.com.omnimind.bot.ui.channel

import android.app.usage.StorageStatsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.os.storage.StorageManager
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

class StorageUsageChannel {
    companion object {
        private const val TAG = "StorageUsageChannel"
        private const val CHANNEL_NAME = "cn.com.omnimind.bot/StorageUsage"

        private const val DATABASE_NAME_PREFIX = "omnibot_cache_database"
        private const val DATABASE_PRIMARY_NAME = "${DATABASE_NAME_PREFIX}oss"

        private const val STORAGE_METRICS_PREFS = "storage_usage_metrics"
        private const val STORAGE_METRICS_HISTORY_KEY = "history_v1"
        private const val MAX_HISTORY_SIZE = 30
        private const val DEFAULT_HISTORY_OUTPUT_SIZE = 10
        private const val APP_BINARY_LIB_TOP_LIMIT = 5
    }

    private var methodChannel: MethodChannel? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun onCreate(context: Context) {
        appContext = context.applicationContext
    }

    fun setChannel(flutterEngine: FlutterEngine) {
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        methodChannel?.setMethodCallHandler(::handleMethodCall)
    }

    fun clear() {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        appContext = null
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getStorageUsageSummary" -> {
                scope.launch {
                    runCatching {
                        val context = requireContext()
                        withContext(Dispatchers.IO) {
                            analyzeStorageUsage(context, persistSnapshot = true)
                        }
                    }.onSuccess { payload ->
                        result.success(payload.summary)
                    }.onFailure { error ->
                        OmniLog.e(TAG, "Analyze storage usage failed", error)
                        result.error("STORAGE_ANALYZE_FAILED", error.message, null)
                    }
                }
            }

            "clearStorageUsageCategory" -> {
                scope.launch {
                    runCatching {
                        val categoryId = call.argument<String>("categoryId")?.trim().orEmpty()
                        if (categoryId.isEmpty()) {
                            error("categoryId is required")
                        }
                        val olderThanDays =
                            (call.argument<Number>("olderThanDays")?.toInt() ?: 0).takeIf { it > 0 }
                        val context = requireContext()
                        withContext(Dispatchers.IO) {
                            clearCategory(context, categoryId, olderThanDays)
                        }
                    }.onSuccess {
                        result.success(it)
                    }.onFailure { error ->
                        OmniLog.e(TAG, "Clear storage category failed", error)
                        result.error("STORAGE_CLEAR_FAILED", error.message, null)
                    }
                }
            }

            "applyStorageCleanupStrategy" -> {
                scope.launch {
                    runCatching {
                        val strategyId = call.argument<String>("strategyId")?.trim().orEmpty()
                        if (strategyId.isEmpty()) {
                            error("strategyId is required")
                        }
                        val olderThanDays =
                            (call.argument<Number>("olderThanDays")?.toInt() ?: 0).takeIf { it > 0 }
                        val targetReleaseBytes =
                            call.argument<Number>("targetReleaseBytes")?.toLong() ?: 0L
                        val context = requireContext()
                        withContext(Dispatchers.IO) {
                            applyCleanupStrategy(
                                context = context,
                                strategyId = strategyId,
                                overrideOlderThanDays = olderThanDays,
                                targetReleaseBytes = targetReleaseBytes,
                            )
                        }
                    }.onSuccess {
                        result.success(it)
                    }.onFailure { error ->
                        OmniLog.e(TAG, "Apply storage cleanup strategy failed", error)
                        result.error("STORAGE_STRATEGY_FAILED", error.message, null)
                    }
                }
            }

            else -> result.notImplemented()
        }
    }

    private fun requireContext(): Context {
        return appContext ?: error("StorageUsageChannel is not initialized")
    }

    private data class AnalysisPayload(
        val summary: Map<String, Any?>,
        val categoryBytes: Map<String, Long>,
        val categoryCleanable: Map<String, Boolean>,
    )

    private data class StoragePaths(
        val dataDir: File,
        val workspaceRoot: File,
        val workspaceInternalRoot: File,
        val workspaceLegacyInternalRoot: File,
        val workspaceLegacyExternalRoot: File,
        val cacheInternalDir: File,
        val cacheExternalDir: File?,
        val sharedDraftsDir: File,
        val mcpInboxDir: File,
        val terminalLocalRoot: File,
        val terminalProotFile: File,
        val terminalLibFile: File,
        val terminalAlpineArchive: File,
        val terminalUbuntuArchive: File,
        val terminalUbuntuPartialArchive: File,
        val appBinaryFiles: List<File>,
        val databaseFiles: List<File>,
    )

    private data class CategoryEntry(
        val id: String,
        val name: String,
        val description: String,
        val bytes: Long,
        val cleanable: Boolean,
        val riskLevel: String,
        val cleanupHint: String? = null,
        val breakdown: List<CategoryBreakdownEntry> = emptyList(),
        val order: Int,
    )

    private data class CategoryBreakdownEntry(
        val label: String,
        val bytes: Long,
    )

    private data class SnapshotPoint(
        val generatedAt: Long,
        val totalBytes: Long,
        val cleanableBytes: Long,
    )

    private data class SnapshotBundle(
        val history: List<SnapshotPoint>,
        val trend: Map<String, Any?>,
    )

    private data class CleanupOutcome(
        val success: Boolean,
        val failedPaths: List<String> = emptyList(),
        val deletedItems: Int = 0,
    ) {
        fun merge(next: CleanupOutcome): CleanupOutcome {
            return CleanupOutcome(
                success = this.success && next.success,
                failedPaths = this.failedPaths + next.failedPaths,
                deletedItems = this.deletedItems + next.deletedItems,
            )
        }
    }

    private data class StrategyAction(
        val categoryId: String,
        val olderThanDays: Int? = null,
        val required: Boolean = true,
    )

    private data class SystemStorageStats(
        val codeBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long,
        val totalBytes: Long,
    )

    private data class CleanupStrategyPreset(
        val id: String,
        val name: String,
        val description: String,
        val riskLevel: String,
        val olderThanDays: Int? = null,
        val targetReleaseBytes: Long? = null,
        val actions: List<StrategyAction>,
    )

    private fun analyzeStorageUsage(
        context: Context,
        persistSnapshot: Boolean,
    ): AnalysisPayload {
        val paths = resolveStoragePaths(context)
        val appBinaryScanBytes = sumUniquePaths(paths.appBinaryFiles)
        val dataDirScanBytes = sumUniquePaths(listOf(paths.dataDir))

        val cacheInternalBytes = sumUniquePaths(listOf(paths.cacheInternalDir))
        val cacheExternalBytes = sumUniquePaths(listOfNotNull(paths.cacheExternalDir))
        val cacheScanBytes = cacheInternalBytes + cacheExternalBytes

        val systemStats = querySystemStorageStats(context)
        val appBinaryBytes = systemStats?.codeBytes ?: appBinaryScanBytes
        val userDataBytes = systemStats?.dataBytes ?: dataDirScanBytes
        val summaryCacheBytes = systemStats?.cacheBytes ?: cacheScanBytes
        val cacheCategoryBytes = maxOf(cacheScanBytes, summaryCacheBytes)
        val appBinaryTopLibs = buildTopNativeLibBreakdown(
            context = context,
            topLimit = APP_BINARY_LIB_TOP_LIMIT,
        )

        val databaseBytes = sumUniquePaths(paths.databaseFiles)
        val conversationEstimateBytes = estimateConversationHistoryBytes().coerceAtMost(databaseBytes)
        val databaseOtherBytes = (databaseBytes - conversationEstimateBytes).coerceAtLeast(0L)

        val workspaceBrowserBytes = sumUniquePaths(listOf(File(paths.workspaceInternalRoot, "browser")))
        val workspaceOffloadsBytes = sumUniquePaths(listOf(File(paths.workspaceInternalRoot, "offloads")))
        val workspaceAttachmentsBytes = sumUniquePaths(listOf(File(paths.workspaceInternalRoot, "attachments")))
        val workspaceSharedBytes = sumUniquePaths(listOf(File(paths.workspaceInternalRoot, "shared")))
        val workspaceMemoryBytes = sumUniquePaths(listOf(File(paths.workspaceInternalRoot, "memory")))
        val workspaceUserFilesBytes = sumWorkspaceUserFiles(paths.workspaceRoot)

        val terminalLocalBytes = sumUniquePaths(listOf(paths.terminalLocalRoot))
        val terminalBootstrapBytes = sumUniquePaths(
            listOf(
                paths.terminalProotFile,
                paths.terminalLibFile,
                paths.terminalAlpineArchive,
                paths.terminalUbuntuArchive,
                paths.terminalUbuntuPartialArchive,
            )
        )

        val sharedDraftsBytes = sumUniquePaths(listOf(paths.sharedDraftsDir))
        val mcpInboxBytes = sumUniquePaths(listOf(paths.mcpInboxDir))
        val legacyWorkspaceBytes = sumUniquePaths(
            listOf(paths.workspaceLegacyInternalRoot, paths.workspaceLegacyExternalRoot)
        )

        val externalKnownBytes = listOf(
            if (isUnderRoot(paths.cacheExternalDir, paths.dataDir)) 0L else cacheExternalBytes,
            if (isUnderRoot(paths.workspaceLegacyExternalRoot, paths.dataDir)) 0L else measurePathSize(paths.workspaceLegacyExternalRoot),
        ).sum()

        val baseCategories = listOf(
            CategoryEntry(
                id = "app_binary",
                name = "应用安装包",
                description = "应用安装文件与 Native 库占用",
                bytes = appBinaryBytes,
                cleanable = false,
                riskLevel = "info",
                breakdown = appBinaryTopLibs,
                order = 1,
            ),
            CategoryEntry(
                id = "cache",
                name = "缓存",
                description = "临时文件与图片缓存，可安全清理",
                bytes = cacheCategoryBytes,
                cleanable = true,
                riskLevel = "safe",
                cleanupHint = "清理后会在使用中自动重新生成",
                order = 2,
            ),
            CategoryEntry(
                id = "conversation_history",
                name = "会话历史",
                description = "对话与工具执行历史（估算）",
                bytes = conversationEstimateBytes,
                cleanable = true,
                riskLevel = "dangerous",
                cleanupHint = "会删除历史消息记录，且不可恢复",
                order = 3,
            ),
            CategoryEntry(
                id = "database_other",
                name = "数据库其他占用",
                description = "索引与系统表等数据库占用",
                bytes = databaseOtherBytes,
                cleanable = false,
                riskLevel = "info",
                order = 4,
            ),
            CategoryEntry(
                id = "workspace_browser",
                name = "Workspace 浏览器产物",
                description = "浏览器截图、下载文件和中间产物",
                bytes = workspaceBrowserBytes,
                cleanable = true,
                riskLevel = "safe",
                cleanupHint = "会删除浏览器工具相关的中间文件",
                order = 5,
            ),
            CategoryEntry(
                id = "workspace_offloads",
                name = "Workspace Offloads",
                description = "工具离线输出与临时文件",
                bytes = workspaceOffloadsBytes,
                cleanable = true,
                riskLevel = "safe",
                cleanupHint = "仅删除离线产物，不影响核心功能",
                order = 6,
            ),
            CategoryEntry(
                id = "workspace_attachments",
                name = "Workspace 附件",
                description = "历史任务使用的附件文件",
                bytes = workspaceAttachmentsBytes,
                cleanable = true,
                riskLevel = "caution",
                cleanupHint = "可能影响历史任务对附件的回看",
                order = 7,
            ),
            CategoryEntry(
                id = "workspace_shared",
                name = "Workspace 共享区",
                description = "跨任务共享的工作区文件",
                bytes = workspaceSharedBytes,
                cleanable = true,
                riskLevel = "caution",
                cleanupHint = "可能影响后续任务复用共享文件",
                order = 8,
            ),
            CategoryEntry(
                id = "workspace_memory",
                name = "Workspace 记忆数据",
                description = "长期/短期记忆与索引数据",
                bytes = workspaceMemoryBytes,
                cleanable = false,
                riskLevel = "info",
                order = 9,
            ),
            CategoryEntry(
                id = "workspace_user_files",
                name = "Workspace 用户文件",
                description = "用户主动保存到 workspace 的文件",
                bytes = workspaceUserFilesBytes,
                cleanable = false,
                riskLevel = "info",
                order = 10,
            ),
            CategoryEntry(
                id = "terminal_runtime_local",
                name = "终端运行时（local）",
                description = "Alpine/Ubuntu 终端 local 运行目录",
                bytes = terminalLocalBytes,
                cleanable = true,
                riskLevel = "dangerous",
                cleanupHint = "会删除终端 local 目录，需重新初始化",
                order = 13,
            ),
            CategoryEntry(
                id = "terminal_runtime_bootstrap",
                name = "终端运行时（引导文件）",
                description = "proot/lib/rootfs 引导文件",
                bytes = terminalBootstrapBytes,
                cleanable = true,
                riskLevel = "dangerous",
                cleanupHint = "会删除终端引导文件，需重新初始化",
                order = 14,
            ),
            CategoryEntry(
                id = "shared_drafts",
                name = "共享草稿",
                description = "外部分享导入的草稿缓存",
                bytes = sharedDraftsBytes,
                cleanable = true,
                riskLevel = "safe",
                cleanupHint = "会删除未发送的草稿附件",
                order = 15,
            ),
            CategoryEntry(
                id = "mcp_inbox",
                name = "MCP 收件箱",
                description = "MCP 文件传输接收目录",
                bytes = mcpInboxBytes,
                cleanable = true,
                riskLevel = "safe",
                cleanupHint = "会删除 MCP 收件箱中的文件",
                order = 16,
            ),
            CategoryEntry(
                id = "legacy_workspace",
                name = "旧版遗留数据",
                description = "升级后可能残留的旧 workspace 目录",
                bytes = legacyWorkspaceBytes,
                cleanable = true,
                riskLevel = "caution",
                cleanupHint = "建议确认无用后再清理",
                order = 17,
            ),
        )

        val localizedBaseCategories = baseCategories.map { localizeCategoryEntry(context, it) }
        val knownBytes = localizedBaseCategories.sumOf { it.bytes }
        val scanTotalBytes = appBinaryScanBytes + dataDirScanBytes + externalKnownBytes
        val baselineTotalBytes = systemStats?.totalBytes ?: scanTotalBytes
        val totalBytes = maxOf(baselineTotalBytes, knownBytes)
        val otherUserDataBytes = (totalBytes - knownBytes).coerceAtLeast(0L)

        val categories = (localizedBaseCategories + localizeCategoryEntry(context, CategoryEntry(
            id = "other_user_data",
            name = "其他数据",
            description = "未命中分类规则的数据",
            bytes = otherUserDataBytes,
            cleanable = false,
            riskLevel = "info",
            order = 99,
        ))).sortedByDescending { it.bytes }

        val cleanableBytes = categories.filter { it.cleanable }.sumOf { it.bytes }
        val generatedAt = System.currentTimeMillis()
        val snapshots = buildSnapshotBundle(
            context = context,
            generatedAt = generatedAt,
            totalBytes = totalBytes,
            cleanableBytes = cleanableBytes,
            persist = persistSnapshot,
        )

        val summary = mapOf(
            "generatedAt" to generatedAt,
            "totalBytes" to totalBytes,
            "appBinaryBytes" to appBinaryBytes,
            "userDataBytes" to userDataBytes,
            "cacheBytes" to summaryCacheBytes,
            "cleanableBytes" to cleanableBytes,
            "packageName" to context.packageName,
            "metricsSource" to if (systemStats != null) "system_storage_stats" else "filesystem_estimate",
            "scanTotalBytes" to scanTotalBytes,
            "systemTotalBytes" to (systemStats?.totalBytes ?: 0L),
            "trend" to snapshots.trend,
            "history" to snapshots.history.map { point ->
                mapOf(
                    "generatedAt" to point.generatedAt,
                    "totalBytes" to point.totalBytes,
                    "cleanableBytes" to point.cleanableBytes,
                )
            },
            "strategyPresets" to cleanupStrategyPresets(context).map { preset ->
                mapOf(
                    "id" to preset.id,
                    "name" to preset.name,
                    "description" to preset.description,
                    "riskLevel" to preset.riskLevel,
                    "olderThanDays" to preset.olderThanDays,
                    "targetReleaseBytes" to (preset.targetReleaseBytes ?: 0L),
                )
            },
            "categories" to categories.map { category ->
                mapOf(
                    "id" to category.id,
                    "name" to category.name,
                    "description" to category.description,
                    "bytes" to category.bytes,
                    "cleanable" to category.cleanable,
                    "riskLevel" to category.riskLevel,
                    "cleanupHint" to category.cleanupHint,
                    "breakdown" to category.breakdown.map { item ->
                        mapOf(
                            "label" to item.label,
                            "bytes" to item.bytes,
                        )
                    },
                    "order" to category.order,
                )
            },
        )

        return AnalysisPayload(
            summary = summary,
            categoryBytes = categories.associate { it.id to it.bytes },
            categoryCleanable = categories.associate { it.id to it.cleanable },
        )
    }

    private fun querySystemStorageStats(context: Context): SystemStorageStats? {
        val manager = context.getSystemService(StorageStatsManager::class.java) ?: return null
        return runCatching {
            val stats = manager.queryStatsForPackage(
                StorageManager.UUID_DEFAULT,
                context.packageName,
                Process.myUserHandle(),
            )
            val codeBytes = stats.appBytes.coerceAtLeast(0L)
            val dataBytes = stats.dataBytes.coerceAtLeast(0L)
            val cacheBytes = stats.cacheBytes.coerceAtLeast(0L)
            SystemStorageStats(
                codeBytes = codeBytes,
                dataBytes = dataBytes,
                cacheBytes = cacheBytes,
                totalBytes = (codeBytes + dataBytes + cacheBytes).coerceAtLeast(0L),
            )
        }.onFailure {
            OmniLog.e(TAG, "Query system storage stats failed", it)
        }.getOrNull()
    }

    private fun clearCategory(
        context: Context,
        categoryId: String,
        olderThanDays: Int?,
    ): Map<String, Any?> {
        val before = analyzeStorageUsage(context, persistSnapshot = false)
        val beforeBytes = before.categoryBytes[categoryId] ?: error("Unknown category: $categoryId")
        if (before.categoryCleanable[categoryId] != true) {
            error("Category is not cleanable: $categoryId")
        }

        val outcome = clearCategoryInternal(
            context = context,
            categoryId = categoryId,
            olderThanDays = olderThanDays,
        )
        val after = analyzeStorageUsage(context, persistSnapshot = true)
        val afterBytes = after.categoryBytes[categoryId] ?: 0L
        val releasedBytes = (beforeBytes - afterBytes).coerceAtLeast(0L)
        val manualActionHint = manualActionHintForCategory(context, categoryId)

        return mapOf(
            "categoryId" to categoryId,
            "success" to outcome.success,
            "beforeBytes" to beforeBytes,
            "afterBytes" to afterBytes,
            "releasedBytes" to releasedBytes,
            "failedPaths" to outcome.failedPaths,
            "retryable" to outcome.failedPaths.isNotEmpty(),
            "manualActionHint" to manualActionHint,
            "summary" to after.summary,
        )
    }

    private fun applyCleanupStrategy(
        context: Context,
        strategyId: String,
        overrideOlderThanDays: Int?,
        targetReleaseBytes: Long,
    ): Map<String, Any?> {
        val strategy = cleanupStrategyPresets(context).firstOrNull { it.id == strategyId }
            ?: error("Unknown strategy: $strategyId")
        val before = analyzeStorageUsage(context, persistSnapshot = false)
        var current = before
        val actionResults = mutableListOf<Map<String, Any?>>()
        var totalReleased = 0L
        var allSuccess = true

        for (action in strategy.actions) {
            val categoryId = action.categoryId
            val beforeBytes = current.categoryBytes[categoryId] ?: 0L
            val canClean = current.categoryCleanable[categoryId] == true
            if (!canClean) {
                if (action.required) {
                    allSuccess = false
                }
                actionResults.add(
                    mapOf(
                        "categoryId" to categoryId,
                        "success" to !action.required,
                        "releasedBytes" to 0L,
                        "failedPaths" to emptyList<String>(),
                        "manualActionHint" to categorySkipHint(context, action.required),
                    )
                )
                continue
            }

            val olderThanDays = overrideOlderThanDays ?: action.olderThanDays ?: strategy.olderThanDays
            val outcome = clearCategoryInternal(
                context = context,
                categoryId = categoryId,
                olderThanDays = olderThanDays,
            )
            val next = analyzeStorageUsage(context, persistSnapshot = false)
            val afterBytes = next.categoryBytes[categoryId] ?: 0L
            val released = (beforeBytes - afterBytes).coerceAtLeast(0L)
            totalReleased += released
            current = next

            if (!outcome.success && action.required) {
                allSuccess = false
            }

            actionResults.add(
                mapOf(
                    "categoryId" to categoryId,
                    "success" to outcome.success,
                    "releasedBytes" to released,
                    "failedPaths" to outcome.failedPaths,
                    "manualActionHint" to manualActionHintForCategory(context, categoryId),
                )
            )

            val target = if (targetReleaseBytes > 0) targetReleaseBytes else (strategy.targetReleaseBytes ?: 0L)
            if (target > 0L && totalReleased >= target) {
                break
            }
        }

        val finalSummary = analyzeStorageUsage(context, persistSnapshot = true)

        return mapOf(
            "strategyId" to strategy.id,
            "strategyName" to strategy.name,
            "success" to allSuccess,
            "releasedBytes" to totalReleased,
            "actionResults" to actionResults,
            "summary" to finalSummary.summary,
        )
    }

    private fun cleanupStrategyPresets(context: Context): List<CleanupStrategyPreset> {
        val presets = listOf(
            CleanupStrategyPreset(
                id = "safe_quick",
                name = "安全快速清理",
                description = "优先清理低风险缓存与临时产物",
                riskLevel = "safe",
                olderThanDays = 3,
                actions = listOf(
                    StrategyAction("cache"),
                    StrategyAction("workspace_browser"),
                    StrategyAction("workspace_offloads"),
                    StrategyAction("shared_drafts"),
                    StrategyAction("mcp_inbox"),
                ),
            ),
            CleanupStrategyPreset(
                id = "balance_deep",
                name = "平衡深度清理",
                description = "释放更多空间，保留核心模型与用户文件",
                riskLevel = "caution",
                olderThanDays = 7,
                actions = listOf(
                    StrategyAction("cache"),
                    StrategyAction("workspace_browser"),
                    StrategyAction("workspace_offloads"),
                    StrategyAction("workspace_attachments"),
                    StrategyAction("workspace_shared"),
                    StrategyAction("shared_drafts"),
                    StrategyAction("mcp_inbox"),
                    StrategyAction("legacy_workspace", required = false),
                ),
            ),
            CleanupStrategyPreset(
                id = "free_1gb_priority",
                name = "目标释放 1GB",
                description = "按高收益顺序清理，尽量达到 1GB 释放目标",
                riskLevel = "dangerous",
                targetReleaseBytes = 1024L * 1024L * 1024L,
                actions = listOf(
                    StrategyAction("cache"),
                    StrategyAction("workspace_browser"),
                    StrategyAction("workspace_offloads"),
                    StrategyAction("terminal_runtime_local", required = false),
                    StrategyAction("terminal_runtime_bootstrap", required = false),
                ),
            ),
        )
        return presets.map { preset -> localizeStrategyPreset(context, preset) }
    }

    private fun clearCategoryInternal(
        context: Context,
        categoryId: String,
        olderThanDays: Int?,
    ): CleanupOutcome {
        val cutoffMillis = olderThanDays?.let { System.currentTimeMillis() - it * 24L * 60L * 60L * 1000L }
        return when (categoryId) {
            "cache" -> mergeOutcomes(
                if (cutoffMillis != null) clearDirectoryContentsByAge(context.cacheDir, cutoffMillis) else clearDirectoryContents(context.cacheDir),
                if (cutoffMillis != null) clearDirectoryContentsByAge(context.externalCacheDir, cutoffMillis) else clearDirectoryContents(context.externalCacheDir),
            )

            "workspace_browser" -> clearWorkspaceInternalSubDir(context, "browser", cutoffMillis)
            "workspace_offloads" -> clearWorkspaceInternalSubDir(context, "offloads", cutoffMillis)
            "workspace_attachments" -> clearWorkspaceInternalSubDir(context, "attachments", cutoffMillis)
            "workspace_shared" -> clearWorkspaceInternalSubDir(context, "shared", cutoffMillis)

            "shared_drafts" -> {
                val directory = File(context.filesDir, "shared_open_drafts")
                if (cutoffMillis != null) clearDirectoryContentsByAge(directory, cutoffMillis) else clearDirectoryContents(directory)
            }

            "mcp_inbox" -> {
                val directory = File(context.filesDir, "mcp_inbox")
                if (cutoffMillis != null) clearDirectoryContentsByAge(directory, cutoffMillis) else clearDirectoryContents(directory)
            }

            "legacy_workspace" -> mergeOutcomes(
                clearDirectoryContents(File(context.filesDir, "workspace")),
                clearDirectoryContents(File(AgentWorkspaceManager.LEGACY_EXTERNAL_ROOT_PATH)),
            )

            "terminal_runtime_local" -> clearDirectoryContents(File(context.applicationInfo.dataDir, "local"))
            "terminal_runtime_bootstrap" -> mergeOutcomes(
                clearDirectoryContents(File(context.filesDir, "proot")),
                clearDirectoryContents(File(context.filesDir, "libtalloc.so.2")),
                clearDirectoryContents(File(context.filesDir, "alpine.tar.gz")),
                clearDirectoryContents(File(context.filesDir, "ubuntu.tar.gz")),
                clearDirectoryContents(File(context.filesDir, "ubuntu.tar.gz.part")),
            )
            "terminal_runtime" -> mergeOutcomes(
                clearCategoryInternal(context, "terminal_runtime_local", olderThanDays),
                clearCategoryInternal(context, "terminal_runtime_bootstrap", olderThanDays),
            )

            "conversation_history" -> clearConversationHistory()
            else -> CleanupOutcome(success = false, failedPaths = listOf("unknown:$categoryId"))
        }
    }

    private fun clearWorkspaceInternalSubDir(
        context: Context,
        subDirName: String,
        cutoffMillis: Long?,
    ): CleanupOutcome {
        val root = AgentWorkspaceManager.internalRootDirectory(context)
        val dir = File(root, subDirName)
        return if (cutoffMillis != null) {
            clearDirectoryContentsByAge(dir, cutoffMillis)
        } else {
            clearDirectoryContents(dir)
        }
    }

    private fun clearConversationHistory(): CleanupOutcome {
        val context = appContext ?: return CleanupOutcome(success = false, failedPaths = listOf("context_unavailable"))
        val dbFile = resolvePrimaryDatabaseFile(context)
        if (!dbFile.exists()) {
            return CleanupOutcome(success = true)
        }

        return runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqliteDb ->
                sqliteDb.beginTransaction()
                try {
                    safeExecSql(sqliteDb, "DELETE FROM agent_conversation_entries")
                    safeExecSql(sqliteDb, "DELETE FROM conversations")
                    safeExecSql(sqliteDb, "DELETE FROM messages")
                    sqliteDb.setTransactionSuccessful()
                } finally {
                    sqliteDb.endTransaction()
                }
                safeExecSql(sqliteDb, "PRAGMA wal_checkpoint(TRUNCATE)")
                safeExecSql(sqliteDb, "VACUUM")
            }
            CleanupOutcome(success = true)
        }.onFailure {
            OmniLog.e(TAG, "Clear conversation history failed", it)
        }.getOrElse {
            CleanupOutcome(success = false, failedPaths = listOf(dbFile.absolutePath))
        }
    }

    private fun manualActionHintForCategory(
        context: Context,
        categoryId: String,
    ): String {
        return when (categoryId) {
            "conversation_history" -> text(
                context,
                zh = "如历史未释放，请重新进入页面执行“重新分析”",
                en = "If history size does not drop, open this page again and run Analyze again.",
            )
            "terminal_runtime_local", "terminal_runtime_bootstrap", "terminal_runtime" ->
                text(
                    context,
                    zh = "终端运行时被清理后，可在终端环境页重新初始化",
                    en = "After cleanup, terminal runtime can be re-initialized in Terminal Environment.",
                )
            else -> text(
                context,
                zh = "若清理失败，可稍后重试或重启应用后再次清理",
                en = "If cleanup fails, try again later or retry after restarting the app.",
            )
        }
    }

    private fun categorySkipHint(context: Context, required: Boolean): String {
        return if (required) {
            text(
                context,
                zh = "该分类当前不可清理",
                en = "This category is not cleanable right now.",
            )
        } else {
            text(
                context,
                zh = "该分类已跳过（可选项）",
                en = "Skipped this optional category.",
            )
        }
    }

    private fun localizeStrategyPreset(
        context: Context,
        preset: CleanupStrategyPreset,
    ): CleanupStrategyPreset {
        if (!AppLocaleManager.isEnglish(context)) {
            return preset
        }
        return when (preset.id) {
            "safe_quick" -> preset.copy(
                name = "Safe quick cleanup",
                description = "Prioritize low-risk cache and temporary artifacts.",
            )
            "balance_deep" -> preset.copy(
                name = "Balanced deep cleanup",
                description = "Free more space while keeping core models and user files.",
            )
            "free_1gb_priority" -> preset.copy(
                name = "Target free 1 GB",
                description = "Clean in high-yield order to approach 1 GB released.",
            )
            else -> preset
        }
    }

    private fun localizeCategoryEntry(
        context: Context,
        entry: CategoryEntry,
    ): CategoryEntry {
        if (!AppLocaleManager.isEnglish(context)) {
            return entry
        }
        return when (entry.id) {
            "app_binary" -> entry.copy(
                name = "App package",
                description = "App install files and native libraries.",
            )
            "cache" -> entry.copy(
                name = "Cache",
                description = "Temporary files and image cache, safe to clean.",
                cleanupHint = "Will be regenerated during normal usage.",
            )
            "conversation_history" -> entry.copy(
                name = "Conversation history",
                description = "Chat and tool execution history (estimated).",
                cleanupHint = "Deletes history records permanently and cannot be undone.",
            )
            "database_other" -> entry.copy(
                name = "Database (other)",
                description = "Indexes and system table data.",
            )
            "workspace_browser" -> entry.copy(
                name = "Workspace browser artifacts",
                description = "Screenshots, downloads, and intermediate browser outputs.",
                cleanupHint = "Deletes intermediate files from browser tools.",
            )
            "workspace_offloads" -> entry.copy(
                name = "Workspace offloads",
                description = "Offline tool outputs and temporary files.",
                cleanupHint = "Deletes offline outputs only and keeps core functionality intact.",
            )
            "workspace_attachments" -> entry.copy(
                name = "Workspace attachments",
                description = "Attachments used by historical tasks.",
                cleanupHint = "May affect reviewing attachments in past tasks.",
            )
            "workspace_shared" -> entry.copy(
                name = "Workspace shared files",
                description = "Cross-task shared workspace files.",
                cleanupHint = "May affect reuse of shared files in future tasks.",
            )
            "workspace_memory" -> entry.copy(
                name = "Workspace memory data",
                description = "Long/short-term memory and index data.",
            )
            "workspace_user_files" -> entry.copy(
                name = "Workspace user files",
                description = "Files explicitly saved by user into workspace.",
            )
            "terminal_runtime_local" -> entry.copy(
                name = "Terminal runtime (local)",
                description = "Alpine/Ubuntu terminal local runtime directory.",
                cleanupHint = "Deletes terminal local directory and requires re-initialization.",
            )
            "terminal_runtime_bootstrap" -> entry.copy(
                name = "Terminal runtime (bootstrap)",
                description = "proot/lib/rootfs bootstrap files.",
                cleanupHint = "Deletes terminal bootstrap files and requires re-initialization.",
            )
            "shared_drafts" -> entry.copy(
                name = "Shared drafts",
                description = "Draft cache imported from external sharing.",
                cleanupHint = "Deletes unsent draft attachments.",
            )
            "mcp_inbox" -> entry.copy(
                name = "MCP inbox",
                description = "Incoming directory for MCP file transfers.",
                cleanupHint = "Deletes files in MCP inbox.",
            )
            "legacy_workspace" -> entry.copy(
                name = "Legacy workspace data",
                description = "Old workspace directories that may remain after upgrades.",
                cleanupHint = "Recommended to clean after confirming they are no longer needed.",
            )
            "other_user_data" -> entry.copy(
                name = "Other data",
                description = "Data not matched by current category rules.",
            )
            else -> entry
        }
    }

    private fun text(context: Context, zh: String, en: String): String {
        return if (AppLocaleManager.isEnglish(context)) en else zh
    }

    private fun buildSnapshotBundle(
        context: Context,
        generatedAt: Long,
        totalBytes: Long,
        cleanableBytes: Long,
        persist: Boolean,
    ): SnapshotBundle {
        val history = loadSnapshotHistory(context).toMutableList()
        val previous = history.lastOrNull()
        val nextPoint = SnapshotPoint(
            generatedAt = generatedAt,
            totalBytes = totalBytes,
            cleanableBytes = cleanableBytes,
        )
        val finalHistory = if (persist) {
            history.add(nextPoint)
            val trimmed = history.takeLast(MAX_HISTORY_SIZE)
            saveSnapshotHistory(context, trimmed)
            trimmed
        } else {
            history.toList()
        }
        val outputHistory = finalHistory.takeLast(DEFAULT_HISTORY_OUTPUT_SIZE)
        val trend = mapOf(
            "hasPrevious" to (previous != null),
            "deltaTotalBytes" to if (previous == null) 0L else (totalBytes - previous.totalBytes),
            "deltaCleanableBytes" to if (previous == null) 0L else (cleanableBytes - previous.cleanableBytes),
            "previousGeneratedAt" to (previous?.generatedAt ?: 0L),
            "previousTotalBytes" to (previous?.totalBytes ?: 0L),
            "previousCleanableBytes" to (previous?.cleanableBytes ?: 0L),
        )
        return SnapshotBundle(
            history = outputHistory,
            trend = trend,
        )
    }

    private fun loadSnapshotHistory(context: Context): List<SnapshotPoint> {
        val prefs = context.getSharedPreferences(STORAGE_METRICS_PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(STORAGE_METRICS_HISTORY_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        SnapshotPoint(
                            generatedAt = item.optLong("generatedAt", 0L),
                            totalBytes = item.optLong("totalBytes", 0L),
                            cleanableBytes = item.optLong("cleanableBytes", 0L),
                        )
                    )
                }
            }.filter { it.generatedAt > 0L }
        }.getOrDefault(emptyList())
    }

    private fun saveSnapshotHistory(context: Context, history: List<SnapshotPoint>) {
        runCatching {
            val array = JSONArray()
            history.forEach { point ->
                array.put(
                    JSONObject().apply {
                        put("generatedAt", point.generatedAt)
                        put("totalBytes", point.totalBytes)
                        put("cleanableBytes", point.cleanableBytes)
                    }
                )
            }
            context.getSharedPreferences(STORAGE_METRICS_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(STORAGE_METRICS_HISTORY_KEY, array.toString())
                .apply()
        }.onFailure {
            OmniLog.e(TAG, "Save snapshot history failed", it)
        }
    }

    private fun estimateConversationHistoryBytes(): Long {
        val context = appContext ?: return 0L
        val dbFile = resolvePrimaryDatabaseFile(context)
        if (!dbFile.exists()) {
            return 0L
        }

        return runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqliteDb ->
                val entries = querySingleLong(
                    sqliteDb,
                    """
                        SELECT IFNULL(SUM(
                            LENGTH(payloadJson) +
                            LENGTH(summary) +
                            LENGTH(entryId) +
                            LENGTH(entryType) +
                            LENGTH(status)
                        ), 0)
                        FROM agent_conversation_entries
                    """.trimIndent()
                )
                val conversations = querySingleLong(
                    sqliteDb,
                    """
                        SELECT IFNULL(SUM(
                            LENGTH(title) +
                            LENGTH(summary) +
                            LENGTH(lastMessage) +
                            LENGTH(mode) +
                            LENGTH(contextSummary)
                        ), 0)
                        FROM conversations
                    """.trimIndent()
                )
                val messages = querySingleLong(
                    sqliteDb,
                    """
                        SELECT IFNULL(SUM(
                            LENGTH(messageId) +
                            LENGTH(content)
                        ), 0)
                        FROM messages
                    """.trimIndent()
                )
                (entries + conversations + messages).coerceAtLeast(0L)
            }
        }.onFailure {
            OmniLog.e(TAG, "Estimate conversation history bytes failed", it)
        }.getOrDefault(0L)
    }

    private fun resolveStoragePaths(context: Context): StoragePaths {
        val dataDir = File(context.applicationInfo.dataDir)
        val workspaceRoot = AgentWorkspaceManager.rootDirectory(context)
        val workspaceInternalRoot = AgentWorkspaceManager.internalRootDirectory(context)
        val appBinaryFiles = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs?.forEach { add(File(it)) }
        }

        val databaseNameCandidates = context.databaseList()
            .filter { it.startsWith(DATABASE_NAME_PREFIX) }
            .ifEmpty { listOf(DATABASE_PRIMARY_NAME) }

        val databaseFiles = buildList {
            databaseNameCandidates.forEach { name ->
                val base = context.getDatabasePath(name)
                add(base)
                add(File("${base.absolutePath}-wal"))
                add(File("${base.absolutePath}-shm"))
            }
        }

        return StoragePaths(
            dataDir = dataDir,
            workspaceRoot = workspaceRoot,
            workspaceInternalRoot = workspaceInternalRoot,
            workspaceLegacyInternalRoot = File(context.filesDir, "workspace"),
            workspaceLegacyExternalRoot = File(AgentWorkspaceManager.LEGACY_EXTERNAL_ROOT_PATH),
            cacheInternalDir = context.cacheDir,
            cacheExternalDir = context.externalCacheDir,
            sharedDraftsDir = File(context.filesDir, "shared_open_drafts"),
            mcpInboxDir = File(context.filesDir, "mcp_inbox"),
            terminalLocalRoot = File(dataDir, "local"),
            terminalProotFile = File(context.filesDir, "proot"),
            terminalLibFile = File(context.filesDir, "libtalloc.so.2"),
            terminalAlpineArchive = File(context.filesDir, "alpine.tar.gz"),
            terminalUbuntuArchive = File(context.filesDir, "ubuntu.tar.gz"),
            terminalUbuntuPartialArchive = File(context.filesDir, "ubuntu.tar.gz.part"),
            appBinaryFiles = appBinaryFiles,
            databaseFiles = databaseFiles,
        )
    }

    private fun resolvePrimaryDatabaseFile(context: Context): File {
        return context.getDatabasePath(DATABASE_PRIMARY_NAME)
    }

    private fun buildTopNativeLibBreakdown(
        context: Context,
        topLimit: Int,
    ): List<CategoryBreakdownEntry> {
        if (topLimit <= 0) {
            return emptyList()
        }
        val nativeLibRootPath = context.applicationInfo.nativeLibraryDir ?: return emptyList()
        val nativeLibRoot = File(nativeLibRootPath)
        if (!nativeLibRoot.exists() || !nativeLibRoot.isDirectory) {
            return emptyList()
        }

        return nativeLibRoot.walkTopDown()
            .maxDepth(8)
            .filter { file ->
                file.isFile && file.name.endsWith(".so", ignoreCase = true)
            }
            .map { file ->
                val relative = runCatching {
                    nativeLibRoot.toPath().relativize(file.toPath()).toString()
                }.getOrDefault(file.name)
                CategoryBreakdownEntry(
                    label = relative.replace('\\', '/'),
                    bytes = file.length().coerceAtLeast(0L),
                )
            }
            .sortedByDescending { it.bytes }
            .take(topLimit)
            .toList()
    }

    private fun safeExecSql(database: SQLiteDatabase, sql: String) {
        runCatching { database.execSQL(sql) }
            .onFailure { OmniLog.e(TAG, "Execute SQL failed: $sql", it) }
    }

    private fun querySingleLong(database: SQLiteDatabase, sql: String): Long {
        return runCatching {
            database.rawQuery(sql, null).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getLong(0)
                } else {
                    0L
                }
            }
        }.getOrDefault(0L)
    }

    private fun sumWorkspaceUserFiles(workspaceRoot: File): Long {
        if (!workspaceRoot.exists() || !workspaceRoot.isDirectory) {
            return 0L
        }
        return workspaceRoot
            .listFiles()
            .orEmpty()
            .filterNot { it.name == ".omnibot" }
            .sumOf { measurePathSize(it) }
    }

    private fun sumUniquePaths(paths: List<File>): Long {
        if (paths.isEmpty()) {
            return 0L
        }
        val visited = hashSetOf<String>()
        var total = 0L
        paths.forEach { path ->
            val key = canonicalPath(path)
            if (visited.add(key)) {
                total += measurePathSize(path)
            }
        }
        return total
    }

    private fun measurePathSize(path: File?): Long {
        if (path == null || !path.exists()) {
            return 0L
        }
        return runCatching {
            if (path.isFile) {
                path.length().coerceAtLeast(0L)
            } else {
                measureDirectorySize(path)
            }
        }.getOrDefault(0L)
    }

    private fun measureDirectorySize(root: File): Long {
        if (!root.exists()) {
            return 0L
        }
        if (root.isFile) {
            return root.length().coerceAtLeast(0L)
        }

        val stack = ArrayDeque<File>()
        val visited = hashSetOf<String>()
        var total = 0L
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val canonical = canonicalPath(current)
            if (!visited.add(canonical)) {
                continue
            }
            if (current.isFile) {
                total += current.length().coerceAtLeast(0L)
                continue
            }
            if (!current.isDirectory) {
                continue
            }
            current.listFiles()?.forEach { child ->
                if (child.isFile) {
                    total += child.length().coerceAtLeast(0L)
                } else {
                    stack.add(child)
                }
            }
        }
        return total
    }

    private fun clearDirectoryContents(directory: File?): CleanupOutcome {
        if (directory == null || !directory.exists()) {
            return CleanupOutcome(success = true)
        }
        if (!directory.isDirectory) {
            val deleted = deletePath(directory)
            return CleanupOutcome(
                success = deleted,
                failedPaths = if (deleted) emptyList() else listOf(directory.absolutePath),
                deletedItems = if (deleted) 1 else 0,
            )
        }

        val failedPaths = mutableListOf<String>()
        var deletedItems = 0
        directory.listFiles().orEmpty().forEach { child ->
            val deleted = deletePath(child)
            if (deleted) {
                deletedItems += 1
            } else {
                failedPaths.add(child.absolutePath)
            }
        }
        return CleanupOutcome(
            success = failedPaths.isEmpty(),
            failedPaths = failedPaths,
            deletedItems = deletedItems,
        )
    }

    private fun clearDirectoryContentsByAge(
        directory: File?,
        cutoffMillis: Long,
    ): CleanupOutcome {
        if (directory == null || !directory.exists()) {
            return CleanupOutcome(success = true)
        }
        if (directory.isFile) {
            if (directory.lastModified() <= cutoffMillis) {
                val deleted = deletePath(directory)
                return CleanupOutcome(
                    success = deleted,
                    failedPaths = if (deleted) emptyList() else listOf(directory.absolutePath),
                    deletedItems = if (deleted) 1 else 0,
                )
            }
            return CleanupOutcome(success = true, deletedItems = 0)
        }

        val failedPaths = mutableListOf<String>()
        var deletedItems = 0

        directory.walkBottomUp().forEach { path ->
            if (path == directory) return@forEach
            if (path.lastModified() > cutoffMillis) return@forEach
            if (path.isDirectory && path.listFiles()?.isNotEmpty() == true) return@forEach
            val deleted = deletePath(path)
            if (deleted) {
                deletedItems += 1
            } else {
                failedPaths.add(path.absolutePath)
            }
        }
        return CleanupOutcome(
            success = failedPaths.isEmpty(),
            failedPaths = failedPaths,
            deletedItems = deletedItems,
        )
    }

    private fun mergeOutcomes(vararg outcomes: CleanupOutcome): CleanupOutcome {
        var merged = CleanupOutcome(success = true)
        outcomes.forEach { outcome ->
            merged = merged.merge(outcome)
        }
        return merged
    }

    private fun clearUniqueDirectories(paths: List<File>): CleanupOutcome {
        if (paths.isEmpty()) {
            return CleanupOutcome(success = true)
        }
        val visited = hashSetOf<String>()
        var merged = CleanupOutcome(success = true)
        paths.forEach { path ->
            val key = canonicalPath(path)
            if (!visited.add(key)) {
                return@forEach
            }
            merged = merged.merge(clearDirectoryContents(path))
        }
        return merged
    }

    private fun deletePath(path: File?): Boolean {
        if (path == null || !path.exists()) {
            return true
        }
        val maxAttempts = 2
        var attempt = 0
        while (attempt < maxAttempts) {
            val deleted = runCatching {
                if (path.isDirectory) path.deleteRecursively() else path.delete()
            }.getOrDefault(false)
            if (deleted || !path.exists()) {
                return true
            }
            attempt += 1
            runCatching { Thread.sleep(60L * attempt) }
        }
        return false
    }

    private fun isUnderRoot(path: File?, root: File): Boolean {
        if (path == null) {
            return false
        }
        val target = canonicalPath(path)
        val rootPath = canonicalPath(root)
        return target == rootPath || target.startsWith("$rootPath${File.separator}")
    }

    private fun canonicalPath(file: File): String {
        return runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    }
}
