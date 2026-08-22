package cn.com.omnimind.bot.omniflow

import android.content.Context
import cn.com.omnimind.baselib.util.OmniLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class PreparedOmniFlowRuntime(
    val manifest: OmniFlowRuntimeManifest,
    val androidPythonSourceRoot: File,
    val shellPythonSourcePath: String,
    val shellSitePackagesPath: String,
    val shellOmniTransferRoot: String,
    val shellOmniTransferCheckpointPath: String,
    val source: String,
)

class OmniFlowRuntimeProvider {
    private val prepareMutex = Mutex()

    @Volatile
    private var prepared: PreparedOmniFlowRuntime? = null

    suspend fun install(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime = prepareMutex.withLock {
        // Installing the plugin only needs to make its skill bundle and
        // manifest available.  Python/bootstrap repair is deliberately left
        // to the first actual OmniFlow tool call (or the background warmup).
        // Keeping this phase lightweight prevents plugin restoration from
        // blocking ACP's first response on apk index/network work.
        prepared?.let { return@withLock it }
        prepareFresh(
            context.applicationContext,
            platform,
            refresh = false,
            ensurePython = false,
        )
    }

    suspend fun update(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime = prepareMutex.withLock {
        prepared = null
        prepareFresh(context.applicationContext, platform, refresh = true)
    }

    suspend fun prepare(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime {
        prepared?.let { return it }
        return prepareMutex.withLock {
            prepared?.let {
                platform.ensurePython(context.applicationContext, it.manifest.pythonVersion)
                return@withLock it
            }
            prepareFresh(context.applicationContext, platform, refresh = false)
        }
    }

    suspend fun preparePackaged(
        context: Context,
        platform: OmniFlowPlatform,
    ): PreparedOmniFlowRuntime = prepareMutex.withLock {
        prepared = null
        prepareFresh(
            appContext = context.applicationContext,
            platform = platform,
            refresh = false,
            packagedOnly = true,
        )
    }

    suspend fun reclaim(
        context: Context,
        platform: OmniFlowPlatform,
    ) = prepareMutex.withLock {
        prepared = null
        platform.reclaimRuntimeSkill(context.applicationContext)
    }

    private suspend fun prepareFresh(
        appContext: Context,
        platform: OmniFlowPlatform,
        refresh: Boolean,
        packagedOnly: Boolean = false,
        ensurePython: Boolean = true,
    ): PreparedOmniFlowRuntime {
        val startedAt = System.currentTimeMillis()
        log("prepare_start refresh=$refresh")
        var location = if (packagedOnly) {
            platform.resolvePackagedRuntimeSkill(appContext)
        } else {
            platform.resolveRuntimeSkill(appContext, refresh = refresh)
        }
        log(
            "prepare_skill_resolved durationMs=${System.currentTimeMillis() - startedAt} " +
                "source=${location.source}",
        )
        val manifest = withContext(Dispatchers.IO) {
            val manifestFile = File(location.androidRoot, MANIFEST_PATH)
            require(manifestFile.isFile) { "omniflow_skill_manifest_missing" }
            manifestFile.inputStream().use(::parseOmniFlowRuntimeManifest)
        }
        if (ensurePython) {
            platform.ensurePython(appContext, manifest.pythonVersion)
            log(
                "prepare_python_ready durationMs=${System.currentTimeMillis() - startedAt} " +
                    "python=${manifest.pythonVersion}",
            )
        } else {
            log(
                "prepare_python_deferred durationMs=${System.currentTimeMillis() - startedAt} " +
                    "python=${manifest.pythonVersion}",
            )
        }
        location = platform.bootstrapRuntimeSkill(appContext, location)
        log(
            "prepare_bootstrap_ready durationMs=${System.currentTimeMillis() - startedAt}",
        )
        val runtime = withContext(Dispatchers.IO) {
            requireRuntimeFiles(location.androidRoot, manifest)
            alignPythonStoreWithRuntime(appContext, manifest)
            PreparedOmniFlowRuntime(
                manifest = manifest,
                androidPythonSourceRoot = File(
                    location.androidRoot,
                    "scripts/runtime/python",
                ),
                shellPythonSourcePath = "${location.shellRoot}/scripts/runtime/python",
                shellSitePackagesPath =
                    "${location.shellRoot}/vendor/site-packages",
                shellOmniTransferRoot =
                    "${location.shellRoot}/scripts/runtime/.runtime/omnitransfer",
                shellOmniTransferCheckpointPath =
                    "${location.shellRoot}/scripts/runtime/.runtime/omnitransfer/" +
                        "src/omnitransfer/${manifest.omniTransferCheckpoint}",
                source = location.source,
            )
        }
        return runtime.also {
            prepared = it
            log(
                "prepare_ready durationMs=${System.currentTimeMillis() - startedAt} " +
                    "runtime=${manifest.version}",
            )
        }
    }

    private fun log(message: String) {
        runCatching { OmniLog.i(TAG, message) }
    }

    private fun requireRuntimeFiles(
        skillRoot: File,
        manifest: OmniFlowRuntimeManifest,
    ) {
        val required = requiredOmniFlowRuntimePaths(manifest)
        val missing = required.filterNot { File(skillRoot, it).isFile }
        require(missing.isEmpty()) {
            "omniflow_skill_runtime_incomplete:" + missing.joinToString(",")
        }
    }

    internal fun requiredOmniFlowRuntimePaths(
        manifest: OmniFlowRuntimeManifest,
    ): List<String> = listOf(
            "scripts/runtime/python/omniflow/bridge.py",
            "scripts/runtime/python/omniflow/runlog.py",
            "vendor/site-packages/json_repair/__init__.py",
            "scripts/runtime/python/schemas/oob/oob_canonical_actions.v1.json",
            "scripts/runtime/python/schemas/oob/omniflow_canonical_run_log.v1.json",
            "scripts/runtime/python/schemas/oob/omniflow_function.v2.json",
            "scripts/runtime/python/schemas/oob/omniflow_checker_rule.v1.json",
            "scripts/runtime/python/schemas/oob/omniflow_android_bridge.v2.json",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/runtime.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/numpy_v9_matcher.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/page_embedding.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/unified_alignment.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/visual_descriptor.py",
            "scripts/runtime/.runtime/omnitransfer/src/omnitransfer/${manifest.omniTransferCheckpoint}",
        )

    private fun alignPythonStoreWithRuntime(
        context: Context,
        manifest: OmniFlowRuntimeManifest,
    ) {
        val storeDirectory = File(omniFlowInternalRoot(context), "omniflow").apply { mkdirs() }
        alignOmniFlowStoreWithRuntime(
            storeDirectory = storeDirectory,
            runtimeFingerprint = manifest.runtimeFingerprint(),
        )
    }

    private fun OmniFlowRuntimeManifest.runtimeFingerprint(): String = listOf(
        version,
        protocol,
        bridgeContractSha256,
        omniFlowCommit,
        omniFlowSourceSha256,
        omniTransferCommit,
        omniTransferSourceSha256,
        omniTransferCheckpoint,
        numpyVersion,
        jsonRepairVersion,
    ).joinToString(":")

    companion object {
        private const val TAG = "[OmniFlowRuntimeProvider]"
        const val SKILL_ID = "omniflow-gui-runtime"
        private const val MANIFEST_PATH = "scripts/runtime/runtime.properties"
    }
}

fun alignOmniFlowStoreWithRuntime(
    storeDirectory: File,
    runtimeFingerprint: String,
) {
    storeDirectory.mkdirs()
    val marker = File(storeDirectory, ".runtime_fingerprint")
    if (marker.takeIf(File::isFile)?.readText()?.trim() == runtimeFingerprint) return
    File(storeDirectory, "omniflow.json.tmp").delete()
    marker.writeText(runtimeFingerprint)
}
