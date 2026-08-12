package cn.com.omnimind.bot.agent

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.distribution.AppEditionCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AgentRuntimeContextRepository(
    private val context: Context
) {
    data class AppQueryItem(
        val appName: String,
        val packageName: String
    )

    private val tag = "AgentRuntimeContextRepo"
    private val appsLoadMutex = Mutex()

    @Volatile
    private var appNameToPackageCache: Map<String, String>? = null

    suspend fun getAppNameToPackageMap(): Map<String, String> {
        check(AppEditionCapabilities.canQueryInstalledApps) {
            "Installed apps access is unavailable in this app edition."
        }
        appNameToPackageCache?.let { return it }
        return appsLoadMutex.withLock {
            appNameToPackageCache?.let { return@withLock it }
            val loaded = loadInstalledApps()
            appNameToPackageCache = loaded
            loaded
        }
    }

    suspend fun queryInstalledApps(
        query: String?,
        limit: Int
    ): List<AppQueryItem> {
        val apps = getAppNameToPackageMap()
        return AgentRuntimeContextQuery.filterApps(apps, query, limit)
    }

    private suspend fun loadInstalledApps(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
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
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            OmniLog.w(tag, "Installed apps query failed")
            emptyMap()
        }
    }
}

internal object AgentRuntimeContextQuery {
    fun filterApps(
        apps: Map<String, String>,
        query: String?,
        limit: Int
    ): List<AgentRuntimeContextRepository.AppQueryItem> {
        val safeLimit = limit.coerceIn(1, 100)
        val normalizedQuery = normalize(query)
        val base = apps.entries.map { (appName, packageName) ->
            AgentRuntimeContextRepository.AppQueryItem(
                appName = appName,
                packageName = packageName
            )
        }
        if (normalizedQuery.isBlank()) {
            return base.sortedBy { it.appName.lowercase() }.take(safeLimit)
        }
        return base.mapNotNull { item ->
            val appNameNorm = normalize(item.appName)
            val packageNorm = normalize(item.packageName)
            val score = when {
                appNameNorm == normalizedQuery || packageNorm == normalizedQuery -> 0
                appNameNorm.contains(normalizedQuery) -> 1
                packageNorm.contains(normalizedQuery) -> 2
                else -> return@mapNotNull null
            }
            item to score
        }.sortedWith(
            compareBy<Pair<AgentRuntimeContextRepository.AppQueryItem, Int>> { it.second }
                .thenBy { it.first.appName.lowercase() }
        ).map { it.first }
            .take(safeLimit)
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
}
