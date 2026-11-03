package cn.com.omnimind.bot

import BaseApplication
import android.os.SystemClock
import cn.com.omnimind.baselib.account.AccountCredentialStorageException
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.database.DatabaseHelper
import cn.com.omnimind.baselib.i18n.AppLocaleManager
import cn.com.omnimind.baselib.llm.ModelProviderConfigStore
import cn.com.omnimind.baselib.llm.PlatformAiProvisioner
import cn.com.omnimind.baselib.util.AppSecretStore
import cn.com.omnimind.baselib.util.CredentialEndpointSecurity
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.agent.AgentPromptSettingsStore
import cn.com.omnimind.bot.agent.AgentConversationHistoryRepository
import cn.com.omnimind.bot.agent.AgentRuntimeFeatureFlags
import cn.com.omnimind.bot.agent.AgentWorkspaceManager
import cn.com.omnimind.bot.agent.SkillIndexService
import cn.com.omnimind.bot.agent.WorkspaceMemoryRollupScheduler
import cn.com.omnimind.bot.agent.WorkspaceScheduledTaskScheduler
import cn.com.omnimind.bot.activity.StartupThemeResolver
import cn.com.omnimind.bot.cleanup.LegacyLocalModelDataCleanup
import cn.com.omnimind.bot.cleanup.RetiredAgentInstallationCleanup
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.official.OfficialOmniPluginProviders
import cn.com.omnimind.bot.quicklog.QuickLogWidgetUpdater
import cn.com.omnimind.bot.update.AppUpdateManager
import cn.com.omnimind.bot.util.NestedBackgroundStateUtil
import cn.com.omnimind.bot.vlm.DebugOmniMindProviderBootstrap
import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import com.rk.resources.Res
import com.tencent.mmkv.MMKV
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : BaseApplication() {
    companion object {
        lateinit var instance: App

        private var flutterEngineGroup: FlutterEngineGroup? = null
        private var cachedMainEngine: FlutterEngine? = null

        fun getFlutterEngineGroup(): FlutterEngineGroup {
            if (flutterEngineGroup == null) {
                flutterEngineGroup = FlutterEngineGroup(instance)
                OmniLog.d("AppStartup", "FlutterEngineGroup created")
            }
            return flutterEngineGroup!!
        }

        fun getCachedMainEngine(): FlutterEngine {
            if (cachedMainEngine == null) {
                val engineStart = SystemClock.elapsedRealtime()
                OmniLog.d("AppStartup", "Creating main engine from FlutterEngineGroup")

                cachedMainEngine = getFlutterEngineGroup().createAndRunDefaultEngine(instance)

                OmniLog.d(
                    "AppStartup",
                    "Main engine created, cost: ${SystemClock.elapsedRealtime() - engineStart}ms"
                )
            }
            return cachedMainEngine!!
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        val appStartTime = SystemClock.elapsedRealtime()
        OmniLog.d("AppStartup", "App onCreate start")
        super.onCreate()
        OmniLog.d(
            "AppStartup",
            "App super.onCreate cost: ${SystemClock.elapsedRealtime() - appStartTime}ms"
        )
        instance = this
        measureStartupStep("Startup theme and locale") {
            StartupThemeResolver.applyStoredApplicationNightMode(this)
            AppLocaleManager.applyAppLocale(this)
        }
        com.rk.libcommons.application = this
        Res.application = this

        measureStartupStep("Credentials and providers") {
            MMKV.initialize(this)
            CredentialEndpointSecurity.configureDebugLoopback(BuildConfig.DEBUG)
            AppSecretStore.prepare(this)
            ModelProviderConfigStore.initialize(this)
            cn.com.omnimind.baselib.llm.chatgpt.ChatGptOAuthManager.initialize(this)
            DebugOmniMindProviderBootstrap.install(this)
            OfficialOmniPluginProviders.register()
            OmniAccount.initialize(
                context = this,
                baseUrl = BuildConfig.BASE_URL,
                platformGatewayUrl = BuildConfig.AI_GATEWAY_URL,
                allowInsecureLoopback = BuildConfig.DEBUG,
                cloudServiceAccessProvider = {
                    AppUpdateManager.getCloudServiceAccessState(this)
                },
            )
        }
        AgentPromptSettingsStore.initializeAndCleanupLegacyFiles(this)
        LegacyLocalModelDataCleanup.start(this)
        RetiredAgentInstallationCleanup.start(this)
        setupUncaughtExceptionHandler()

        DatabaseHelper.init(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                AgentConversationHistoryRepository(this@App).purgeLegacyStreamEvents()
            }.onSuccess { deleted ->
                if (deleted > 0) {
                    OmniLog.i(
                        "AppStartup",
                        "Removed $deleted legacy ACP stream events from history",
                    )
                }
            }.onFailure { error ->
                OmniLog.w(
                    "AppStartup",
                    "Legacy ACP stream-event cleanup failed: ${error.message}",
                )
            }
        }

        NestedBackgroundStateUtil.init(this)
        cn.com.omnimind.baselib.llm.ModelSceneRegistry.init(this)
        measureStartupStep("Workspace and built-in skills") {
            runCatching {
                val workspaceManager = AgentWorkspaceManager(this)
                // Seeding resolves skillsRoot() before reading the registry, which
                // already initializes the workspace. Avoid a duplicate asset scan.
                SkillIndexService(this, workspaceManager).seedBuiltinSkillsIfNeeded()
            }.onFailure { error ->
                OmniLog.w("AppStartup", "Workspace preparation failed", error)
            }
        }
        // Seed built-in skills before restoring enabled runtime-bundle plugins.
        // Both paths materialize files under the same skills directory; starting
        // plugin recovery first can race the seeder and leave the plugin disabled.
        if (AgentRuntimeFeatureFlags.ENABLE_PLUGIN_RUNTIME) {
            initializeOfficialPlugins()
        }
        runCatching {
            WorkspaceMemoryRollupScheduler(this).ensureScheduledIfEnabled()
        }
        runCatching {
            WorkspaceScheduledTaskScheduler(this).rescheduleAllEnabled()
        }
        runCatching {
            QuickLogWidgetUpdater.updateAll(this)
        }
        runCatching {
            PrivilegedCapabilityManager.get(this).initialize()
        }

        initSDKsAfterPrivacyConsent()
        // Restore the persisted local MCP service after the application process
        // is recreated.  The server still binds on its IO scope, so startup is
        // not blocked, while the settings channel can synchronously finish the
        // same restore if the first state query wins the race.
        if (AgentRuntimeFeatureFlags.ENABLE_LOCAL_MCP_SERVER) {
            McpServerManager.restoreIfEnabled(this)
        } else {
            // Stop a previously persisted server so the clean baseline has no
            // local MCP listener or background MCP work at all.
            McpServerManager.stopServer()
        }
        // Terminal entry points prepare their own runtime before creating a
        // session. Avoid competing with the first frame (or a background-only
        // process start) to install/check an environment that may not be used.
        OmniLog.d(
            "AppStartup",
            "App onCreate total cost: ${SystemClock.elapsedRealtime() - appStartTime}ms"
        )
    }

    private inline fun <T> measureStartupStep(name: String, operation: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return operation()
        } finally {
            OmniLog.i("AppStartup", "$name cost: ${SystemClock.elapsedRealtime() - start}ms")
        }
    }

    private fun setupUncaughtExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                OmniLog.storeCrashLog(
                    tag = "UncaughtException",
                    message = "Thread: ${thread.name}",
                    throwable = throwable,
                )
            } catch (_: Throwable) {
                // Preserve the original crash path even if crash-log persistence fails.
            } finally {
                if (defaultHandler != null) {
                    defaultHandler.uncaughtException(thread, throwable)
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
    }

    private fun initializeOfficialPlugins() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                OmniPluginHost.get(this@App).list()
            }.onFailure { error ->
                OmniLog.w(
                    "AppStartup",
                    "Official plugin initialization failed: ${error.message}",
                )
            }
        }
    }

    fun initSDKsAfterPrivacyConsent() {
        OmniLog.d("AppStartup", "initSDKsAfterPrivacyConsent start")
        AppUpdateManager.schedulePeriodicChecks(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                AppUpdateManager.checkNow(this@App, force = true)
            }.onFailure {
                OmniLog.w("AppStartup", "Cloud-service version policy check failed: ${it.message}")
            }
            val signedIn = runCatching {
                OmniAccount.isConfigured() && OmniAccount.repository().isSignedIn()
            }.onFailure { error ->
                if (error is AccountCredentialStorageException) {
                    OmniLog.w(
                        "AppStartup",
                        "Secure account storage is temporarily unavailable; deferring AI sync",
                    )
                } else {
                    OmniLog.w("AppStartup", "Account session state check failed: ${error.message}")
                }
            }.getOrDefault(false)
            if (signedIn && OmniAccount.currentCloudServiceAccess().allowed) {
                runCatching {
                    val settings = OmniAccount.repository().getAiSettings()
                    PlatformAiProvisioner.synchronize(settings)
                }
            }
        }
        OmniLog.d("AppStartup", "initSDKsAfterPrivacyConsent completed")
    }
}
