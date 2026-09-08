package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentRuntimeContextRepository(
    private val context: Context
) {
    data class AppQueryItem(
        val appName: String,
        val packageName: String
    )

    private val tag = "AgentRuntimeContextRepo"
    suspend fun getAppNameToPackageMap(): Map<String, String> = loadInstalledApps()

    suspend fun queryInstalledApps(
        query: String?,
        limit: Int
    ): List<AppQueryItem> {
        val apps = getAppNameToPackageMap()
        return AgentRuntimeContextQuery.filterApps(apps, query, limit)
    }

    private suspend fun loadInstalledApps(): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(0)
            val normalized = linkedMapOf<String, String>()
            apps.forEach { appInfo ->
                val packageName = appInfo.packageName.trim()
                if (packageName.isBlank()) return@forEach
                val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return@forEach
                if (launchIntent.`package` != null && launchIntent.`package` != packageName) {
                    return@forEach
                }
                val appName = pm.getApplicationLabel(appInfo).toString().trim()
                if (appName.isBlank()) return@forEach
                if (!normalized.containsKey(appName)) {
                    normalized[appName] = packageName
                }
            }
            normalized
        }.onFailure {
            OmniLog.w(tag, "loadInstalledApps failed: ${it.message}")
        }.getOrDefault(emptyMap())
    }
}

internal object AgentRuntimeContextQuery {
    fun filterApps(
        apps: Map<String, String>,
        query: String?,
        limit: Int
    ): List<AgentRuntimeContextRepository.AppQueryItem> {
        val safeLimit = limit.takeIf { it > 0 } ?: Int.MAX_VALUE
        val base = apps.entries.map { (appName, packageName) ->
            AgentRuntimeContextRepository.AppQueryItem(
                appName = appName,
                packageName = packageName
            )
        }
        val normalizedFullQuery = normalize(query)
        val queryTerms = when {
            normalizedFullQuery.isBlank() -> emptyList()
            base.any { matchScore(it, normalizedFullQuery) != null } ->
                listOf(normalizedFullQuery)
            else -> splitQueryTerms(query)
        }
        if (queryTerms.isEmpty()) {
            return base.sortedBy { it.appName.lowercase() }.take(safeLimit)
        }

        val seenPackages = mutableSetOf<String>()
        return queryTerms.asSequence().flatMap { term ->
            base.asSequence().mapNotNull { item ->
                val score = matchScore(item, term) ?: return@mapNotNull null
                item to score
            }
                .sortedWith(
                    compareBy<Pair<AgentRuntimeContextRepository.AppQueryItem, Int>> { it.second }
                        .thenBy { it.first.appName.lowercase() }
                )
                .map { it.first }
        }.filter { seenPackages.add(it.packageName) }
            .take(safeLimit)
            .toList()
    }

    private fun matchScore(
        item: AgentRuntimeContextRepository.AppQueryItem,
        term: String
    ): Int? {
        val appNameNorm = normalize(item.appName)
        val packageNorm = normalize(item.packageName)
        return when {
            appNameNorm == term || packageNorm == term -> 0
            appNameNorm.contains(term) -> 1
            packageNorm.contains(term) -> 2
            else -> null
        }
    }

    private fun splitQueryTerms(query: String?): List<String> {
        return query.orEmpty()
            .split(QUERY_TERM_SEPARATOR)
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun normalize(value: String?): String {
        return value.orEmpty()
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace("“", "")
            .replace("”", "")
            .replace("\"", "")
            .replace("'", "")
            .replace("。", "")
            .replace("，", "")
            .replace(",", "")
            .replace("！", "")
            .replace("!", "")
            .replace("？", "")
            .replace("?", "")
    }

    private val QUERY_TERM_SEPARATOR = Regex("[\\s,，、;；|]+")
}
