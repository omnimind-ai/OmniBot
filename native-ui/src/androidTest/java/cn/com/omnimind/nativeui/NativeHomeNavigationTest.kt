package cn.com.omnimind.nativeui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.com.omnimind.nativeui.settings.AgentsActions
import cn.com.omnimind.nativeui.settings.AgentItem
import cn.com.omnimind.nativeui.settings.AgentsScreen
import cn.com.omnimind.nativeui.settings.AlarmSettingsActions
import cn.com.omnimind.nativeui.settings.AlarmSettingsScreen
import cn.com.omnimind.nativeui.settings.AlarmSettingsState
import cn.com.omnimind.nativeui.settings.MiscSettingsActions
import cn.com.omnimind.nativeui.settings.MiscSettingsScreen
import cn.com.omnimind.nativeui.settings.MiscSettingsState
import cn.com.omnimind.nativeui.settings.OpenWithSettingsActions
import cn.com.omnimind.nativeui.settings.OpenWithSettingsScreen
import cn.com.omnimind.nativeui.settings.OpenWithSettingsState
import cn.com.omnimind.nativeui.settings.RemoteBridgeActions
import cn.com.omnimind.nativeui.settings.RemoteBridgeScreen
import cn.com.omnimind.nativeui.settings.RemoteBridgeState
import cn.com.omnimind.nativeui.settings.ScheduledTasksActions
import cn.com.omnimind.nativeui.settings.TerminalSettingsActions
import cn.com.omnimind.nativeui.settings.TerminalSettingsScreen
import cn.com.omnimind.nativeui.settings.TerminalSettingsState
import cn.com.omnimind.nativeui.settings.ScheduledTasksScreen
import cn.com.omnimind.nativeui.settings.ScheduledTasksState
import cn.com.omnimind.nativeui.settings.UsageStatisticsActions
import cn.com.omnimind.nativeui.settings.UsageStatisticsScreen
import cn.com.omnimind.nativeui.settings.UsageStatisticsState
import cn.com.omnimind.nativeui.settings.PluginMarketScreen
import cn.com.omnimind.nativeui.settings.PluginMarketState
import cn.com.omnimind.nativeui.settings.PluginMarketActions
import cn.com.omnimind.nativeui.settings.MemoryCenterActions
import cn.com.omnimind.nativeui.settings.MemoryCenterScreen
import cn.com.omnimind.nativeui.settings.MemoryCenterState
import cn.com.omnimind.nativeui.settings.SkillStoreActions
import cn.com.omnimind.nativeui.settings.SkillStoreScreen
import cn.com.omnimind.nativeui.settings.SkillStoreState
import cn.com.omnimind.nativeui.settings.AgentsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeHomeNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun settingsRestoresAcrossSavedStateAndReturnsToHome() {
        val restoration = StateRestorationTester(compose)
        val state = mutableStateOf(NativeHomeState(loading = false))
        restoration.setContent {
            NativeHomeApp(state.value, actions(setService = { state.value = state.value.copy(localServiceEnabled = it) }),
                about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {},
                mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {}, appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> })
        }
        capture("home-light")
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        capture("drawer-light")
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_settings_title)).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(label(R.string.omni_settings_title)).assertIsDisplayed()
        capture("settings-light")
        state.value = state.value.copy(theme = ThemePreference.Dark)
        capture("settings-dark")
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).assertIsDisplayed()
    }

    @Test fun drawerOpensTheSelectedConversationWithoutCreatingAnotherIdentity() {
        val opened = mutableListOf<LegacyDestination>()
        compose.setContent {
            NativeHomeApp(NativeHomeState(
                loading = false,
                conversations = listOf(ConversationSummary(42, "Saved thread", "", "agent", 1, false)),
            ), actions(open = { opened.add(it) }), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {},
                mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {}, appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {},
                miscellaneous = { _, _, _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithText("Saved thread").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LegacyDestination.Conversation(42, "agent")), opened)
    }

    @Test fun settingsOpensNativeMiscAndReturnsToSettings() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { onBack, onHomeSettings, _, _ ->
                    MiscSettingsScreen(MiscSettingsState(loaded = true), miscActions(), onHomeSettings, {}, {}, {}, onBack)
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_vibration_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_settings_title)).assertIsDisplayed()
    }

    @Test fun miscOpensNativeAlarmSettingsAndReturns() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {},
                miscellaneous = { onBack, _, onAlarm, _ ->
                    MiscSettingsScreen(MiscSettingsState(loaded = true), miscActions(), {}, onAlarm, {}, {}, onBack)
                },
                alarmSettings = { onBack ->
                    AlarmSettingsScreen(AlarmSettingsState(loaded = true), alarmActions(), {}, onBack)
                },
                openWith = {})
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_alarm_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_alarm_ringtone_source)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_vibration_title)).assertIsDisplayed()
    }

    @Test fun miscOpensNativeOpenWithAndReturns() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {},
                miscellaneous = { onBack, _, _, onOpenWith ->
                    MiscSettingsScreen(MiscSettingsState(loaded = true), miscActions(), {}, {}, onOpenWith, {}, onBack)
                },
                alarmSettings = {},
                openWith = { onBack ->
                    OpenWithSettingsScreen(OpenWithSettingsState(loaded = true), openWithActions(), onBack)
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_open_with_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_open_with_image)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_vibration_title)).assertIsDisplayed()
    }

    @Test fun settingsOpensNativeAgentsAndReturnsToSettings() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                agents = { onBack, _, _, _, _ ->
                    AgentsScreen(AgentsState(loaded = true), agentsActions(), {}, {}, onBack)
                }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_managed_section)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_settings_title)).assertIsDisplayed()
    }

    @Test fun agentsOpenTheNativeRemoteBridgeRoute() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                agents = { onBack, _, _, onRemoteBridge, _ ->
                    AgentsScreen(AgentsState(loaded = true), agentsActions(), {}, onRemoteBridge, onBack)
                },
                agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {},
                remoteBridge = { onBack ->
                    RemoteBridgeScreen(RemoteBridgeState(loaded = true), remoteBridgeActions(), {}, onBack)
                }, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_remote_bridge)).performClick()
        compose.onNodeWithText(label(R.string.omni_bridge_enable)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_managed_section)).assertIsDisplayed()
    }

    @Test fun agentsOpenTheNativeAgentConfigRoute() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                agents = { onBack, _, onAgentConfig, _, _ ->
                    AgentsScreen(
                        AgentsState(loaded = true, agents = listOf(AgentItem(
                            id = "custom-1", name = "My Agent", builtIn = false, enabled = true,
                            status = "online", installed = null, managedAdapter = false,
                        ))),
                        agentsActions(), onAgentConfig, {}, onBack,
                    )
                },
                agentConfig = { agentId, _ ->
                    top.yukonga.miuix.kmp.basic.Text("Native agent config $agentId")
                }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agent_configure)).performClick()
        compose.onNodeWithText("Native agent config custom-1").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_managed_section)).assertIsDisplayed()
    }

    @Test fun homeAgentButtonOpensTheNativeAgentsRoute() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, homePreferences = {}, miscellaneous = { _, _, _, _ -> }, pet = {},
                agents = { _, _, _, _, _ ->
                    top.yukonga.miuix.kmp.basic.Text("Native agents page")
                }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_agent_select)).performClick()
        compose.onNodeWithText("Native agents page").assertIsDisplayed()
    }

    @Test fun untargetedChatEntryLetsTheExistingChatOwnerChooseTheStartupThread() {
        val opened = mutableListOf<LegacyDestination>()
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(open = { opened.add(it) }),
                about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {},
                mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {}, appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> })
        }
        compose.onNodeWithText(label(R.string.omni_composer_hint)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LegacyDestination.Page.Chat), opened)
    }

    @Test fun homePetButtonOpensTheNativePetRoute() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, homePreferences = {},
                miscellaneous = { _, _, _, _ -> }, pet = {
                    top.yukonga.miuix.kmp.basic.Text("Native pet page")
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_pet)).performClick()
        compose.onNodeWithText("Native pet page").assertIsDisplayed()
    }

    @Test fun drawerOpensNativeScheduledTasksAndReturns() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> },
                scheduledTasks = { onBack ->
                    ScheduledTasksScreen(ScheduledTasksState(loaded = true), scheduledTasksActions(), onBack)
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_home_drawer_scheduled)).performClick()
        compose.onNodeWithText(label(R.string.omni_scheduled_empty_tasks)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).assertIsDisplayed()
    }

    @Test fun drawerOpensNativeSkillStoreAndReturns() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                skills = { onBack ->
                    SkillStoreScreen(SkillStoreState(loaded = true), skillStoreActions(), onBack)
                }, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, memory = {}, terminal = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_skill_store_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_skill_empty)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).assertIsDisplayed()
    }

    @Test fun drawerOpensNativeExecutionHistoryAndReturns() {
        compose.setContent {
            var usage by remember { mutableStateOf(UsageStatisticsState(loaded = true)) }
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                executionHistory = { onBack ->
                    UsageStatisticsScreen(usage, UsageStatisticsActions(setTab = { tab -> usage = usage.copy(tab = tab) }), onBack)
                }, skills = {}, memory = {}, terminal = { _, _ -> }, plugins = { _, _ -> }, pluginDetail = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_history)).performClick()
        compose.onNodeWithText(label(R.string.omni_usage_tab_token)).performClick()
        compose.onNodeWithText(label(R.string.omni_usage_token_empty)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).assertIsDisplayed()
    }


    @Test fun drawerOpensNativePluginMarketAndReturns() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                plugins = { onBack, _ ->
                    PluginMarketScreen(PluginMarketState(loaded = true), pluginMarketActions(), {}, onBack)
                }, pluginDetail = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_plugin_market_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_plugin_market_empty)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).assertIsDisplayed()
    }

    @Test fun pluginMarketOpensTheNativePluginDetailRoute() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, memory = {}, terminal = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                plugins = { onBack, onPlugin ->
                    PluginMarketScreen(
                        PluginMarketState(loaded = true, plugins = listOf(cn.com.omnimind.nativeui.settings.PluginItem(
                            id = "demo-plugin", name = "Demo Plugin", description = "Demo", publisher = "OmniMind",
                            kind = "runtime_bundle", downloadSizeBytes = 0, installed = false, enabled = false, compatible = true,
                        ))),
                        pluginMarketActions(), onPlugin, onBack,
                    )
                },
                pluginDetail = { pluginId, _ ->
                    top.yukonga.miuix.kmp.basic.Text("Native plugin detail $pluginId")
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_plugin_market_title)).performClick()
        compose.onNodeWithText("Demo Plugin").performClick()
        compose.onNodeWithText("Native plugin detail demo-plugin").assertIsDisplayed()
    }

    private fun pluginMarketActions() = PluginMarketActions(
        refresh = {}, setQuery = {}, dismissNotice = {},
    )

    @Test fun drawerOpensNativeMemoryCenterAndReturns() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                memory = { onBack ->
                    MemoryCenterScreen(MemoryCenterState(loaded = true), memoryCenterActions(), onBack)
                }, terminal = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_memory_center_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_memory_tab_local)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).assertIsDisplayed()
    }

    private fun memoryCenterActions() = MemoryCenterActions(
        setTab = {}, refreshLong = {}, enterSelection = {}, toggleSelection = {},
        exitSelection = {}, toggleSelectAll = {}, showDeleteSelection = {},
        deleteSelectionConfirmed = {}, openDetail = {}, closeDetail = {}, openEditor = {},
        closeEditor = {}, saveEditor = {}, showDeleteLong = {}, deleteLongConfirmed = {},
        dismissNotice = {},
    )

    @Test fun settingsOpensNativeTerminalSettingsAndReturns() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _, _, _ -> }, agentConfig = { _, _ -> }, alarmSettings = {}, openWith = {}, remoteBridge = {}, scheduledTasks = {}, executionHistory = {}, skills = {}, plugins = { _, _ -> }, pluginDetail = { _, _ -> }, memory = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _, _, _ -> },
                terminal = { _, onBack ->
                    TerminalSettingsScreen(TerminalSettingsState(loaded = true), terminalActions(), {}, {}, {}, onBack)
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_settings_alpine_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_terminal_distro_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_settings_title)).assertIsDisplayed()
    }

    private fun terminalActions() = TerminalSettingsActions(
        refresh = {}, switchDistribution = {}, cancelSwitch = {}, togglePackage = { _, _ -> },
        openTaskEditor = {}, closeTaskEditor = {}, saveTaskEditor = { _, _, _, _ -> },
        toggleTask = { _, _ -> }, runTask = {}, confirmDeleteTask = {}, deleteTaskConfirmed = {},
        confirmUnmount = {}, unmountConfirmed = {}, mountConfirmed = {}, dismissMountPicker = {},
        dismissNotice = {},
    )

    private fun miscActions() = MiscSettingsActions(
        refresh = {}, setStartup = {}, setRecentOnly = {}, setHideFromRecents = {},
        setVibration = {}, setIndependentSend = {}, setPredictiveBack = {},
        setPreventSleep = {}, setCompletionNotification = {}, setHabitualHand = {}, dismissNotice = {},
    )

    private fun agentsActions() = AgentsActions(
        refresh = {}, retry = {}, setQuery = {}, setFilter = {}, openEditor = {},
        editName = {}, editCommand = {}, editArguments = {}, editEnvironment = {}, editEnabled = {},
        dismissEditor = {}, saveEditor = {}, testAgent = {}, prepareAgent = {},
        dismissActionResult = {}, invokeWebAction = { _, _ -> }, consumeDestination = {}, dismissNotice = {},
    )

    private fun alarmActions() = AlarmSettingsActions(
        selectSource = {}, editRemoteUrl = {}, save = {}, dismissNotice = {},
    )

    private fun openWithActions() = OpenWithSettingsActions(
        setMode = { _, _ -> }, dismissNotice = {},
    )

    private fun remoteBridgeActions() = RemoteBridgeActions(
        setEnabled = {}, editUrl = {}, editToken = {}, editCwd = {}, toggleTokenVisible = {},
        test = {}, openPicker = {}, closePicker = {}, pickerOpen = {}, pickerUp = {},
        pickerHome = {}, pickerRefresh = {}, pickerSelect = {}, dismissNotice = {},
    )

    private fun scheduledTasksActions() = ScheduledTasksActions(
        setTab = {}, openEditor = {}, closeEditor = {}, confirmEdit = { _, _ -> },
        confirmDeleteTask = {}, deleteTaskConfirmed = {}, confirmDeleteAlarm = {},
        deleteAlarmConfirmed = {}, dismissNotice = {},
    )

    private fun skillStoreActions() = SkillStoreActions(
        refresh = {}, setQuery = {}, toggle = { _, _ -> }, installBuiltin = {},
        confirmDelete = {}, deleteConfirmed = {}, syncOfficial = {}, dismissNotice = {},
    )

    private fun actions(
        open: (LegacyDestination) -> Unit = {},
        setService: (Boolean) -> Unit = {},
    ) = NativeHomeActions(
        open = open,
        consumeDestination = {},
        setLocalServiceEnabled = setService,
        refreshLocalServiceToken = {},
        setArchived = { _, _ -> },
        setSectionExpanded = { _, _ -> },
        invokeWebAction = { _, _ -> },
        refresh = {},
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "native-home-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
