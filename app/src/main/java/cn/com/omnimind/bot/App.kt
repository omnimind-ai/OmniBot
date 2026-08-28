package cn.com.omnimind.bot

import BaseApplication
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
import cn.com.omnimind.bot.mcp.McpServerManager
import cn.com.omnimind.bot.plugin.OmniPluginHost
import cn.com.omnimind.bot.plugin.official.OfficialOmniPluginProviders
import cn.com.omnimind.bot.quicklog.QuickLogWidgetUpdater
import cn.com.omnimind.bot.terminal.EmbeddedTerminalRuntime
import cn.com.omnimind.bot.update.AppUpdateManager
import cn.com.omnimind.bot.util.NestedBackgroundStateUtil
import cn.com.omnimind.bot.vlm.DebugOmniMindProviderBootstrap
import cn.com.omnimind.baselib.shizuku.ShizukuCapabilityManager
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
                val engineStart = System.currentTimeMillis()
                OmniLog.d("AppStartup", "Creating main engine from FlutterEngineGroup")

                cachedMainEngine = getFlutterEngineGroup().createAndRunDefaultEngine(instance)

                OmniLog.d(
                    "AppStartup",
                    "Main engine created, cost: ${System.currentTimeMillis() - engineStart}ms"
                )
            }
            return cachedMainEngine!!
        }

    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        val appStartTime = System.currentTimeMillis()
        OmniLog.d("AppStartup", "App onCreate start")
        super.onCreate()
        OmniLog.d(
            "AppStartup",
            "App super.onCreate cost: ${System.currentTimeMillis() - appStartTime}ms"
        )
        instance = this
        StartupThemeResolver.applyStoredApplicationNightMode(this)
        AppLocaleManager.applyAppLocale(this)
        com.rk.libcommons.application = this
        Res.application = this

        MMKV.initialize(this)
        CredentialEndpointSecurity.configureDebugLoopback(BuildConfig.DEBUG)
        AppSecretStore.initialize(this)
        ModelProviderConfigStore.initialize(this)
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
        AgentPromptSettingsStore.initializeAndCleanupLegacyFiles(this)
        LegacyLocalModelDataCleanup.start(this)
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
        runCatching {
            val workspaceManager = AgentWorkspaceManager(this)
            workspaceManager.ensureRuntimeDirectories()
            SkillIndexService(this, workspaceManager).seedBuiltinSkillsIfNeeded()
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
            ShizukuCapabilityManager.get(this)
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
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                EmbeddedTerminalRuntime.warmup(this@App)
            }
        }
        OmniLog.d(
            "AppStartup",
            "App onCreate total cost: ${System.currentTimeMillis() - appStartTime}ms"
        )
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
            if (
                OmniAccount.isConfigured() &&
                OmniAccount.repository().isSignedIn() &&
                OmniAccount.currentCloudServiceAccess().allowed
            ) {
                runCatching {
                    val settings = OmniAccount.repository().getAiSettings()
                    PlatformAiProvisioner.synchronize(settings)
                }
            }
        }
        OmniLog.d("AppStartup", "initSDKsAfterPrivacyConsent completed")
    }
}
