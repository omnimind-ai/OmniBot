package cn.com.omnimind.bot.omniflow

import android.content.Context
import android.os.SystemClock
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.baselib.llm.ChatCompletionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object OmniFlowPythonRuntime {
    private const val TAG = "[OmniFlowPythonRuntime]"
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prepareMutex = Mutex()
    private val warmupLock = Any()

    @Volatile
    private var client: OmniFlowPythonClient? = null

    @Volatile
    private var ready: Boolean = false

    @Volatile
    private var activeManifest: OmniFlowRuntimeManifest? = null

    @Volatile
    private var platform: OmniFlowPlatform? = null

    @Volatile
    private var runtimeProvider: OmniFlowRuntimeProvider = OmniFlowRuntimeProvider()

    @Volatile
    private var warmupDeferred: Deferred<OmniFlowRuntimeManifest>? = null

    fun configure(
        value: OmniFlowPlatform,
        provider: OmniFlowRuntimeProvider,
    ) {
        platform = value
        runtimeProvider = provider
    }

    suspend fun shutdown() {
        // Warmup owns prepareMutex. Cancel it before waiting for the lock so
        // disabling a plugin cannot wait for a stalled dependency installation.
        synchronized(warmupLock) {
            warmupDeferred?.cancel()
            warmupDeferred = null
        }
        prepareMutex.withLock {
            val activeClient = client
            client = null
            activeManifest = null
            ready = false
            activeClient?.close()
        }
    }

    suspend fun developerOverrideStatus(context: Context): OmniFlowDeveloperOverrideStatus {
        val prepared = prepareRuntime(context.applicationContext)
        val store = overrideStore(context)
        store.rebaseIfPresent(prepared.androidPythonSourceRoot, prepared.manifest.version)
        return store.status(prepared.manifest.version)
    }

    suspend fun readDeveloperOverride(context: Context, path: String): Map<String, Any?> {
        val prepared = prepareRuntime(context.applicationContext)
        val normalized = normalizedPythonPath(path)
        val store = overrideStore(context)
        store.rebaseIfPresent(prepared.androidPythonSourceRoot, prepared.manifest.version)
        val content = if (store.status(prepared.manifest.version).enabled) {
            runCatching { store.read(normalized) }.getOrElse {
                File(prepared.androidPythonSourceRoot, normalized).readText()
            }
        } else {
            File(prepared.androidPythonSourceRoot, normalized).readText()
        }
        return mapOf(
            "path" to normalized,
            "content" to content,
            "sha256" to sha256Text(content),
            "override_enabled" to store.status(prepared.manifest.version).enabled,
        )
    }

    suspend fun applyDeveloperOverride(
        context: Context,
        path: String,
        content: String,
    ): Map<String, Any?> = prepareMutex.withLock {
        val appContext = context.applicationContext
        closeClientLocked()
        val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
        val prepared = runtimeProvider.prepare(appContext, host)
        val store = overrideStore(appContext)
        val normalized = normalizedPythonPath(path)
        val wasModified = normalized in store.status(prepared.manifest.version).modifiedFiles
        val previous = runCatching {
            if (wasModified) {
                store.read(normalized)
            } else {
                File(prepared.androidPythonSourceRoot, normalized).readText()
            }
        }.getOrNull()
        store.apply(
            basePythonRoot = prepared.androidPythonSourceRoot,
            runtimeVersion = prepared.manifest.version,
            relativePath = normalized,
            content = content,
        )
        try {
            validateOverrideFile(appContext, normalized)
            ensureReadyLocked(appContext, prepared, developerOverride = true)
        } catch (error: Throwable) {
            store.restore(normalized, previous, keepModified = wasModified)
            closeClientLocked()
            runCatching {
                ensureReadyLocked(
                    appContext,
                    prepared,
                    developerOverride = store.status(prepared.manifest.version).enabled,
                )
            }
            throw error
        }
        mapOf(
            "success" to true,
            "path" to normalized,
            "sha256" to sha256Text(content),
            "runtime_version" to prepared.manifest.version,
            "reloaded" to true,
        )
    }

    suspend fun clearDeveloperOverride(context: Context): Map<String, Any?> =
        prepareMutex.withLock {
            val appContext = context.applicationContext
            closeClientLocked()
            val cleared = overrideStore(appContext).clear()
            val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
            val prepared = runtimeProvider.prepare(appContext, host)
            ensureReadyLocked(appContext, prepared, developerOverride = false)
            mapOf(
                "success" to true,
                "cleared" to cleared,
                "runtime_version" to prepared.manifest.version,
                "reloaded" to true,
            )
        }

    suspend fun reloadDeveloperOverride(context: Context): Map<String, Any?> =
        prepareMutex.withLock {
            val appContext = context.applicationContext
            closeClientLocked()
            val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
            val prepared = runtimeProvider.prepare(appContext, host)
            val store = overrideStore(appContext)
            store.rebaseIfPresent(prepared.androidPythonSourceRoot, prepared.manifest.version)
            val enabled = store.status(prepared.manifest.version).enabled
            ensureReadyLocked(appContext, prepared, developerOverride = enabled)
            mapOf(
                "success" to true,
                "runtime_version" to prepared.manifest.version,
                "override_enabled" to enabled,
                "reloaded" to true,
            )
        }

    fun start(context: Context) {
        if (ready) return
        warmupJob(context.applicationContext)
    }

    suspend fun prepareAndStart(context: Context): OmniFlowRuntimeManifest =
        ensureReady(context.applicationContext)

    suspend fun call(
        context: Context,
        operation: String,
        payload: Map<String, Any?> = emptyMap(),
        hostCall: OmniFlowPythonHostCall? = null,
    ): Map<String, Any?> {
        awaitReady(context.applicationContext)
        return requireNotNull(client) { "omniflow_python_client_unavailable" }
            .call(operation, payload, hostCall)
    }

    suspend fun toolDefinition(context: Context, name: String): RuntimeTool =
        prepareRuntime(context).manifest.tools.firstOrNull { it.name == name }
            ?: error("runtime_tool_not_declared:$name")

    internal suspend fun completeJson(request: ChatCompletionRequest): String =
        requireNotNull(platform) { "omniflow_platform_not_configured" }.completeJson(request)

    private suspend fun ensureReady(context: Context): OmniFlowRuntimeManifest {
        if (ready && client != null) {
            return requireNotNull(activeManifest) { "omniflow_runtime_manifest_unavailable" }
        }
        return prepareMutex.withLock {
            if (ready && client != null) {
                return requireNotNull(activeManifest) { "omniflow_runtime_manifest_unavailable" }
            }
            val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
            val preparedRuntime = runtimeProvider.prepare(context, host)
            try {
                ensureReadyLocked(
                    context,
                    preparedRuntime,
                    developerOverride = developerOverrideShellPath(context, preparedRuntime) != null,
                )
            } catch (error: Throwable) {
                if (!isOmniFlowRuntimeCompatibilityFailure(error) ||
                    !host.allowsPackagedRuntimeFallback()
                ) {
                    throw error
                }
                OmniLog.w(TAG, "runtime_incompatible; retrying packaged runtime: ${error.message}")
                val packagedRuntime = runtimeProvider.preparePackaged(context, host)
                ensureReadyLocked(
                    context,
                    packagedRuntime,
                    developerOverride = false,
                )
            }
        }
    }

    private suspend fun ensureReadyLocked(
        context: Context,
        preparedRuntime: PreparedOmniFlowRuntime,
        developerOverride: Boolean,
    ): OmniFlowRuntimeManifest {
        val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
        host.prepareEnvironment(context, preparedRuntime.command(preparedRuntime.manifest.prepareEntrypoint))
        val candidate = OmniFlowPythonClient(
            processStarter = { command, environment ->
                host.startProcess(context, command, environment)
            },
            bridgeCommand = preparedRuntime.command(preparedRuntime.manifest.entrypoint),
            environment = if (developerOverride) mapOf(
                "OMNIFLOW_SOURCE_OVERRIDE" to OmniFlowDeveloperOverrideStore.SHELL_ROOT,
            ) else emptyMap(),
        )
        try {
            val initialization = candidate.initialize()
            val metadata = initialization["_meta"] as? Map<*, *>
            val runtime = metadata?.get("omniflow/runtime") as? Map<*, *> ?: emptyMap<Any, Any>()
            if (initialization["protocolVersion"] != preparedRuntime.manifest.protocol) {
                OmniLog.w(
                    TAG,
                    "protocol_diff expected=${preparedRuntime.manifest.protocol} " +
                        "actual=${initialization["protocolVersion"]}",
                )
            }
            if (runtime["omnitransfer_ready"] != true) {
                OmniLog.w(
                    TAG,
                    "omnitransfer_degraded backend=${runtime["omnitransfer_backend"]}; " +
                        "failed mappings will fall back to the online VLM",
                )
            }
            client = candidate
            activeManifest = preparedRuntime.manifest
            ready = true
            return preparedRuntime.manifest
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            client = null
            activeManifest = null
            ready = false
            throw error
        }
    }

    private fun warmupJob(context: Context): Deferred<OmniFlowRuntimeManifest> =
        synchronized(warmupLock) {
            warmupDeferred?.let { existing ->
                if (!existing.isCancelled) return@synchronized existing
            }
            val startedAt = SystemClock.elapsedRealtime()
            runtimeScope.async {
                OmniLog.i(TAG, "warmup_start")
                runCatching { ensureReady(context) }
                    .onSuccess { manifest ->
                        OmniLog.i(
                            TAG,
                            "warmup_ready durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                                "protocol=${manifest.protocol}",
                        )
                    }
                    .onFailure { error ->
                        ready = false
                        if (error !is CancellationException) {
                            OmniLog.w(
                                TAG,
                                "warmup_failed durationMs=${SystemClock.elapsedRealtime() - startedAt} " +
                                    "error=${error.message}",
                            )
                        }
                    }
                    .getOrThrow()
            }.also { created ->
                warmupDeferred = created
                created.invokeOnCompletion { error ->
                    if (error != null) {
                        synchronized(warmupLock) {
                            if (warmupDeferred === created) warmupDeferred = null
                        }
                    }
                }
            }
        }

    private suspend fun awaitReady(context: Context) {
        if (ready && client != null) return
        warmupJob(context).await()
    }

    private suspend fun prepareRuntime(context: Context): PreparedOmniFlowRuntime {
        val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
        return runtimeProvider.prepare(context.applicationContext, host)
    }

    private fun overrideStore(context: Context): OmniFlowDeveloperOverrideStore =
        OmniFlowDeveloperOverrideStore(developerOverrideRoot(context.applicationContext))

    private fun developerOverrideShellPath(
        context: Context,
        prepared: PreparedOmniFlowRuntime,
    ): String? {
        val store = overrideStore(context)
        store.rebaseIfPresent(prepared.androidPythonSourceRoot, prepared.manifest.version)
        return OmniFlowDeveloperOverrideStore.SHELL_ROOT.takeIf {
            store.status(prepared.manifest.version).enabled
        }
    }

    private suspend fun validateOverrideFile(context: Context, relativePath: String) {
        val host = requireNotNull(platform) { "omniflow_platform_not_configured" }
        val shellPath = "${OmniFlowDeveloperOverrideStore.SHELL_ROOT}/$relativePath"
        val process = host.startProcess(
            context,
            "python3 -m py_compile '$shellPath'",
            emptyMap(),
        )
        val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
        val error = withContext(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
        require(exitCode == 0) {
            error.trim().ifBlank { "omniflow_override_syntax_invalid" }
        }
    }

    private suspend fun closeClientLocked() {
        synchronized(warmupLock) {
            warmupDeferred?.cancel()
            warmupDeferred = null
        }
        val activeClient = client
        client = null
        activeManifest = null
        ready = false
        activeClient?.close()
    }
}

internal fun isOmniFlowRuntimeCompatibilityFailure(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return message.startsWith("unsupported_omniflow_protocol:") ||
        message.startsWith("omniflow_bridge_contract_mismatch:") ||
        message.startsWith("omniflow_runtime_version_mismatch:") ||
        message.startsWith("omniflow_commit_mismatch:") ||
        message.startsWith("omniflow_source_mismatch:") ||
        message.startsWith("omnitransfer_commit_mismatch:") ||
        message.startsWith("omnitransfer_source_mismatch:") ||
        message.startsWith("omniflow_capabilities_mismatch:")
}

internal fun declaredOmniTransferRuntimeStatus(value: Any?): Boolean {
    require(value is Boolean) { "omnitransfer_runtime_status_missing" }
    return value
}
