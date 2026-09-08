package cn.com.omnimind.bot.plugin.official.agentweb

import android.content.Context
import android.content.Intent
import android.net.Uri
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.runtime.AgentDispatchConfiguration
import cn.com.omnimind.bot.agent.runtime.AgentProviderCredentials
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.terminal.ReTerminalSessionBridge
import com.ai.assistance.operit.terminal.TerminalManager
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface AgentWebController {
    suspend fun open(
        service: AgentWebService,
        reasoningEffort: String? = null,
    ): AgentWebOperationResult

    suspend fun status(service: AgentWebService): AgentWebOperationResult

    suspend fun stop(service: AgentWebService): AgentWebOperationResult
}

/**
 * Owns the process lifecycle for vendor Web UIs without creating an ACP
 * session, turn, or presentation stream. A named ReTerminal process is the
 * lifecycle authority; this class only serializes transitions and opens the
 * validated loopback URL published by that process.
 */
internal class AgentWebRuntimeManager(
    private val gateway: AgentWebRuntimeGateway,
    private val configurationProvider: AgentWebConfigurationProvider,
    private val pollIntervalMs: Long = 500L,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : AgentWebController {
    private val serviceMutexes = AgentWebService.entries.associateWith { Mutex() }

    init {
        require(pollIntervalMs > 0L) { "Agent Web poll interval must be positive." }
    }

    override suspend fun open(
        service: AgentWebService,
        reasoningEffort: String?,
    ): AgentWebOperationResult = serviceMutexes.getValue(service).withLock {
        val provider = configurationProvider.providerCredentials()
            ?: return@withLock failure(
                service,
                AgentWebResultCode.PROVIDER_REQUIRED,
                "Configure the shared Dispatch Provider first.",
            )
        val model = configurationProvider.modelId()
            ?: return@withLock failure(
                service,
                AgentWebResultCode.MODEL_REQUIRED,
                "Select a shared Dispatch model first.",
            )
        val configuration = try {
            buildAgentWebLaunchConfiguration(
                service = service,
                provider = provider,
                model = model,
                reasoningEffort = reasoningEffort,
            )
        } catch (error: IllegalArgumentException) {
            return@withLock failure(
                service,
                AgentWebResultCode.UNSUPPORTED_PROVIDER,
                error.message ?: "The selected Provider is not supported by this Web runtime.",
            )
        }
        val fingerprint = configurationFingerprint(service, configuration.fingerprintSource)

        val existing = snapshotOrNull(service.sessionId)
        if (existing?.running == true) {
            val existingFingerprint = AgentWebTranscriptParser.findConfigurationFingerprint(
                existing.transcript,
            )
            if (existingFingerprint == fingerprint) {
                return@withLock awaitAndOpen(
                    service = service,
                    reused = true,
                )
            }
            if (!ensureStopped(service.sessionId)) {
                return@withLock failure(
                    service,
                    AgentWebResultCode.STOP_FAILED,
                    "Unable to stop the stale ${service.displayName} process.",
                    running = true,
                )
            }
        }

        val commandAvailable = try {
            gateway.isCommandAvailable(service.commandName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
        if (!commandAvailable) {
            return@withLock failure(
                service,
                AgentWebResultCode.RUNTIME_MISSING,
                "The ${service.displayName} runtime is not installed.",
            )
        }

        var launch = launchOrNull(service, configuration, fingerprint)
            ?: return@withLock failure(
                service,
                AgentWebResultCode.START_FAILED,
                "Unable to start ${service.displayName}.",
            )
        if (!launch.started && !launch.alreadyRunning) {
            return@withLock failure(
                service,
                AgentWebResultCode.START_FAILED,
                "Unable to start ${service.displayName}.",
            )
        }
        if (launch.alreadyRunning) {
            val runningFingerprint = snapshotOrNull(service.sessionId)?.let { snapshot ->
                AgentWebTranscriptParser.findConfigurationFingerprint(snapshot.transcript)
            }
            if (runningFingerprint != fingerprint) {
                if (!ensureStopped(service.sessionId)) {
                    return@withLock failure(
                        service,
                        AgentWebResultCode.STOP_FAILED,
                        "Unable to replace the stale ${service.displayName} process.",
                        running = true,
                    )
                }
                launch = launchOrNull(service, configuration, fingerprint)
                    ?: return@withLock failure(
                        service,
                        AgentWebResultCode.START_FAILED,
                        "Unable to restart ${service.displayName}.",
                    )
                if (!launch.started || launch.alreadyRunning) {
                    return@withLock failure(
                        service,
                        AgentWebResultCode.START_FAILED,
                        "Unable to restart ${service.displayName} with the current configuration.",
                        running = launch.alreadyRunning,
                    )
                }
            }
        }
        awaitAndOpen(
            service = service,
            reused = launch.alreadyRunning,
        )
    }

    override suspend fun status(service: AgentWebService): AgentWebOperationResult =
        serviceMutexes.getValue(service).withLock {
            val snapshot = snapshotOrNull(service.sessionId)
            if (snapshot?.running != true) {
                return@withLock AgentWebOperationResult(
                    success = true,
                    code = AgentWebResultCode.NOT_RUNNING,
                    service = service,
                    running = false,
                )
            }
            val ready = AgentWebTranscriptParser.findUrl(service.urlKind, snapshot.transcript) != null
            AgentWebOperationResult(
                success = true,
                code = if (ready) AgentWebResultCode.RUNNING else AgentWebResultCode.STARTING,
                service = service,
                running = true,
            )
        }

    override suspend fun stop(service: AgentWebService): AgentWebOperationResult =
        serviceMutexes.getValue(service).withLock {
            val wasRunning = snapshotOrNull(service.sessionId)?.running == true
            val stopped = try {
                gateway.stop(service.sessionId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return@withLock failure(
                    service,
                    AgentWebResultCode.STOP_FAILED,
                    "Unable to stop ${service.displayName}.",
                    running = wasRunning,
                )
            }
            if (!stopped && wasRunning && snapshotOrNull(service.sessionId)?.running == true) {
                failure(
                    service,
                    AgentWebResultCode.STOP_FAILED,
                    "Unable to stop ${service.displayName}.",
                    running = true,
                )
            } else {
                AgentWebOperationResult(
                    success = true,
                    code = if (stopped) {
                        AgentWebResultCode.STOPPED
                    } else {
                        AgentWebResultCode.NOT_RUNNING
                    },
                    service = service,
                    running = false,
                )
            }
        }

    private suspend fun awaitAndOpen(
        service: AgentWebService,
        reused: Boolean,
    ): AgentWebOperationResult {
        val attempts = (service.readinessTimeoutMs / pollIntervalMs)
            .coerceAtLeast(1L)
            .toInt()
        repeat(attempts) { attempt ->
            val snapshot = snapshotOrNull(service.sessionId)
            if (snapshot?.running != true) {
                stopQuietly(service.sessionId)
                return failure(
                    service,
                    AgentWebResultCode.START_FAILED,
                    "${service.displayName} exited before becoming ready.",
                )
            }
            val url = AgentWebTranscriptParser.findUrl(service.urlKind, snapshot.transcript)
            if (url != null) {
                val opened = try {
                    gateway.openBrowser(url)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                }
                return if (opened) {
                    AgentWebOperationResult(
                        success = true,
                        code = AgentWebResultCode.OPENED,
                        service = service,
                        running = true,
                        reused = reused,
                    )
                } else {
                    failure(
                        service,
                        AgentWebResultCode.BROWSER_UNAVAILABLE,
                        "No system browser is available.",
                        running = true,
                        reused = reused,
                    )
                }
            }
            if (attempt + 1 < attempts) wait(pollIntervalMs)
        }
        return if (ensureStopped(service.sessionId)) {
            failure(
                service,
                AgentWebResultCode.URL_TIMEOUT,
                "${service.displayName} did not publish a supported local URL in time.",
                reused = reused,
            )
        } else {
            failure(
                service,
                AgentWebResultCode.STOP_FAILED,
                "${service.displayName} timed out and could not be stopped.",
                running = true,
                reused = reused,
            )
        }
    }

    private fun failure(
        service: AgentWebService,
        code: AgentWebResultCode,
        error: String,
        running: Boolean = false,
        reused: Boolean = false,
    ) = AgentWebOperationResult(
        success = false,
        code = code,
        service = service,
        running = running,
        reused = reused,
        error = error,
    )

    private suspend fun launchOrNull(
        service: AgentWebService,
        configuration: AgentWebLaunchConfiguration,
        fingerprint: String,
    ): AgentWebRuntimeLaunch? = try {
        gateway.launch(
            sessionId = service.sessionId,
            command = buildManagedCommand(service, configuration.managedFiles, fingerprint),
            environment = configuration.environment,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    private fun buildManagedCommand(
        service: AgentWebService,
        managedFiles: Map<String, String>,
        fingerprint: String,
    ): String = buildString {
        append("set -eu; umask 077; ")
        managedFiles.entries.sortedBy { it.key }.forEach { (path, content) ->
            val parent = File(path).parent
                ?: throw IllegalArgumentException("Managed Agent Web file needs a parent directory.")
            val encoded = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
            append("mkdir -p ")
            append(shellQuote(parent))
            append("; printf '%s' ")
            append(shellQuote(encoded))
            append(" | base64 -d > ")
            append(shellQuote(path))
            append("; chmod 600 ")
            append(shellQuote(path))
            append("; ")
        }
        append("printf '%s\\n' ")
        append(shellQuote("$CONFIG_MARKER_PREFIX$fingerprint"))
        append("; exec ")
        append(service.command)
    }

    private suspend fun snapshotOrNull(sessionId: String): AgentWebRuntimeSnapshot? = try {
        gateway.snapshot(sessionId)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    private suspend fun stopQuietly(sessionId: String) {
        try {
            gateway.stop(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // A failed best-effort cleanup must not replace the launch error.
        }
    }

    private suspend fun ensureStopped(sessionId: String): Boolean {
        val stopped = try {
            gateway.stop(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
        if (stopped) return true
        val snapshot = try {
            gateway.snapshot(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return false
        }
        return snapshot?.running != true
    }

    private fun configurationFingerprint(
        service: AgentWebService,
        source: String,
    ): String = MessageDigest.getInstance("SHA-256")
        .digest("${service.id}\n${service.command}\n$source".toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private companion object {
        const val CONFIG_MARKER_PREFIX = "__OMNIBOT_AGENT_WEB_CONFIG__:"

        fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    }
}

internal class AndroidAgentWebConfigurationProvider : AgentWebConfigurationProvider {
    override fun providerCredentials(): AgentProviderCredentials? =
        AgentDispatchConfiguration.providerCredentials()

    override fun modelId(): String? = AgentDispatchConfiguration.modelId()
}

internal class AndroidAgentWebRuntimeGateway(context: Context) : AgentWebRuntimeGateway {
    private val appContext = context.applicationContext

    override suspend fun isCommandAvailable(commandName: String): Boolean {
        val result = TerminalManager.getInstance(appContext).executeHiddenCommand(
            command = "$MANAGED_NPM_PATH_PREFIX command -v ${shellQuote(commandName)}",
            executorKey = "agent-web-command-probe-$commandName",
            timeoutMs = 15_000L,
        )
        return result.isOk && result.exitCode == 0
    }

    override suspend fun snapshot(sessionId: String): AgentWebRuntimeSnapshot? {
        val session = ReTerminalSessionBridge.getSession(appContext, sessionId) ?: return null
        return AgentWebRuntimeSnapshot(
            running = session.isRunning,
            transcript = session.getTranscriptText(),
        )
    }

    override suspend fun launch(
        sessionId: String,
        command: String,
        environment: Map<String, String>,
    ): AgentWebRuntimeLaunch {
        val result = EmbeddedTerminalRuntime.launchBackgroundServiceSession(
            context = appContext,
            sessionId = sessionId,
            command = "$MANAGED_NPM_PATH_PREFIX $command",
            workingDirectory = AgentWorkspaceManager.SHELL_ROOT_PATH,
            environment = environment,
        )
        return AgentWebRuntimeLaunch(
            started = result.started,
            alreadyRunning = result.alreadyRunning,
        )
    }

    override suspend fun stop(sessionId: String): Boolean =
        ReTerminalSessionBridge.stopSession(appContext, sessionId)

    override suspend fun openBrowser(url: String): Boolean = withContext(Dispatchers.Main.immediate) {
        runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess
    }

    private companion object {
        const val MANAGED_NPM_PATH_PREFIX = "PATH=\"/root/.npm-global/bin:\$PATH\"; export PATH;"

        fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"
    }
}

/** Parses only service-appropriate URLs on the local loopback interface. */
internal object AgentWebTranscriptParser {
    private val ansiEscapeRegex = Regex("\\u001B\\[[0-?]*[ -/]*[@-~]")
    private val configurationRegex = Regex(
        "__OMNIBOT_AGENT_WEB_CONFIG__:([a-f0-9]{64})",
    )
    private val urlCandidateRegex = Regex(
        "http://(?:127\\.0\\.0\\.1|localhost|\\[::1])(?::[0-9]{1,5})(?:/[^\\s\\u001B<>\"']*)?",
        RegexOption.IGNORE_CASE,
    )
    private val tokenFragmentRegex = Regex("token=[A-Za-z0-9_-]{16,}")

    fun findConfigurationFingerprint(transcript: String): String? {
        val clean = ansiEscapeRegex.replace(transcript, "")
        return configurationRegex.findAll(clean).lastOrNull()?.groupValues?.getOrNull(1)
    }

    fun findUrl(kind: AgentWebUrlKind, transcript: String): String? {
        val clean = ansiEscapeRegex.replace(transcript, "")
        return clean.lineSequence()
            .filter { line -> isReadyLine(kind, line.trimStart()) }
            .flatMap { line -> urlCandidateRegex.findAll(line) }
            .map { match -> match.value.trimEnd('.', ',', ';', ')', ']', '}') }
            .filter { candidate -> isValid(kind, candidate) }
            .lastOrNull()
    }

    private fun isReadyLine(kind: AgentWebUrlKind, line: String): Boolean = when (kind) {
        AgentWebUrlKind.KIMI -> line.startsWith("Local:") || line.startsWith("Kimi server:")
        AgentWebUrlKind.DEEPSEEK_HARNESS -> line.startsWith("dsh web:")
    }

    private fun isValid(kind: AgentWebUrlKind, candidate: String): Boolean {
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        if (uri.rawUserInfo != null || uri.port !in 1..65535) return false
        val host = uri.host?.trim('[', ']')?.lowercase()
        if (host !in LOOPBACK_HOSTS) return false
        if (uri.rawPath !in setOf("", "/")) return false
        return when (kind) {
            AgentWebUrlKind.KIMI -> {
                val fragment = uri.rawFragment
                uri.rawQuery == null && fragment?.matches(tokenFragmentRegex) == true
            }
            AgentWebUrlKind.DEEPSEEK_HARNESS -> {
                val query = uri.rawQuery
                uri.rawFragment == null &&
                    (query == null || query.matches(tokenFragmentRegex))
            }
        }
    }

    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
}
