package cn.com.omnimind.bot.omniflow

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedOmniFlowRuntime(
    val manifest: OmniFlowRuntimeManifest,
    val androidPythonSourceRoot: File,
    val shellRoot: String,
    val source: String,
) {
    fun command(entrypoint: String): String = "cd ${shellQuote(shellRoot)} && exec sh ${shellQuote(entrypoint)}"
}

class OmniFlowRuntimeProvider {
    private val prepareMutex = Mutex()
    @Volatile private var prepared: PreparedOmniFlowRuntime? = null

    suspend fun install(context: Context, platform: OmniFlowPlatform): PreparedOmniFlowRuntime =
        prepareMutex.withLock { prepared ?: prepareFresh(context, platform, false) }

    suspend fun update(context: Context, platform: OmniFlowPlatform): PreparedOmniFlowRuntime =
        prepareMutex.withLock {
            prepared = null
            prepareFresh(context, platform, true)
        }

    suspend fun prepare(context: Context, platform: OmniFlowPlatform): PreparedOmniFlowRuntime =
        install(context, platform)

    suspend fun preparePackaged(context: Context, platform: OmniFlowPlatform): PreparedOmniFlowRuntime =
        prepareMutex.withLock {
            prepared = null
            prepareFresh(context, platform, false, packagedOnly = true)
        }

    suspend fun reclaim(context: Context, platform: OmniFlowPlatform) = prepareMutex.withLock {
        prepared = null
        platform.reclaimRuntimeSkill(context.applicationContext)
    }

    private suspend fun prepareFresh(
        context: Context,
        platform: OmniFlowPlatform,
        refresh: Boolean,
        packagedOnly: Boolean = false,
    ): PreparedOmniFlowRuntime {
        val appContext = context.applicationContext
        val location = if (packagedOnly) platform.resolvePackagedRuntimeSkill(appContext)
            else platform.resolveRuntimeSkill(appContext, refresh)
        // Validate the public contract before committing a staged update. No Python starts here.
        val manifest = withContext(Dispatchers.IO) {
            runtimeFile(location.androidRoot, "host.json").inputStream()
                .use(::parseOmniFlowRuntimeManifest).also {
                    require(runtimeFile(location.androidRoot, it.entrypoint).isFile &&
                        runtimeFile(location.androidRoot, it.prepareEntrypoint).isFile) {
                        "runtime_entrypoint_missing"
                    }
                }
        }
        val ready = platform.bootstrapRuntimeSkill(appContext, location)
        return PreparedOmniFlowRuntime(
            manifest,
            runtimeFile(ready.androidRoot, manifest.sourceRoot),
            ready.shellRoot,
            ready.source,
        ).also { prepared = it }
    }

    companion object { const val SKILL_ID = "omniflow-gui-runtime" }
}
