package cn.com.omnimind.nativeui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.com.omnimind.nativeui.settings.AgentsActions
import cn.com.omnimind.nativeui.settings.AgentItem
import cn.com.omnimind.nativeui.settings.AgentsScreen
import cn.com.omnimind.nativeui.settings.AgentsState
import cn.com.omnimind.nativeui.settings.MiscSettingsActions
import cn.com.omnimind.nativeui.settings.MiscSettingsScreen
import cn.com.omnimind.nativeui.settings.MiscSettingsState
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
                mcpTools = {}, agents = { _, _, _ -> }, agentConfig = { _, _ -> }, permissions = {}, appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _ -> })
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
                mcpTools = {}, agents = { _, _, _ -> }, agentConfig = { _, _ -> }, permissions = {}, appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {},
                miscellaneous = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithText("Saved thread").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LegacyDestination.Conversation(42, "agent")), opened)
    }

    @Test fun settingsOpensNativeMiscAndReturnsToSettings() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _ -> }, agentConfig = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { onBack, onHomeSettings ->
                    MiscSettingsScreen(MiscSettingsState(loaded = true), miscActions(), onHomeSettings, {}, onBack)
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_misc_vibration_title)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_settings_title)).assertIsDisplayed()
    }

    @Test fun settingsOpensNativeAgentsAndReturnsToSettings() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _ -> },
                agents = { onBack, _, _ ->
                    AgentsScreen(AgentsState(loaded = true), agentsActions(), {}, {}, onBack)
                }, agentConfig = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithContentDescription(label(R.string.omni_settings_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_title)).performClick()
        compose.onNodeWithText(label(R.string.omni_agents_managed_section)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.omni_back)).performClick()
        compose.onNodeWithText(label(R.string.omni_settings_title)).assertIsDisplayed()
    }

    @Test fun agentsOpenTheNativeAgentConfigRoute() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _ -> },
                agents = { onBack, _, onAgentConfig ->
                    AgentsScreen(
                        AgentsState(loaded = true, agents = listOf(AgentItem(
                            id = "custom-1", name = "My Agent", builtIn = false, enabled = true,
                            status = "online", installed = null, managedAdapter = false,
                        ))),
                        agentsActions(), {}, onAgentConfig, onBack,
                    )
                },
                agentConfig = { agentId, _ ->
                    top.yukonga.miuix.kmp.basic.Text("Native agent config $agentId")
                })
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
                appearance = { _, _ -> }, background = { _, _ -> }, homePreferences = {}, miscellaneous = { _, _ -> }, pet = {},
                agents = { _, _, _ ->
                    top.yukonga.miuix.kmp.basic.Text("Native agents page")
                }, agentConfig = { _, _ -> })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_agent_select)).performClick()
        compose.onNodeWithText("Native agents page").assertIsDisplayed()
    }

    @Test fun untargetedChatEntryLetsTheExistingChatOwnerChooseTheStartupThread() {
        val opened = mutableListOf<LegacyDestination>()
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(open = { opened.add(it) }),
                about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {},
                mcpTools = {}, agents = { _, _, _ -> }, agentConfig = { _, _ -> }, permissions = {}, appearance = { _, _ -> }, background = { _, _ -> }, pet = {}, homePreferences = {}, miscellaneous = { _, _ -> })
        }
        compose.onNodeWithText(label(R.string.omni_composer_hint)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(LegacyDestination.Page.Chat), opened)
    }

    @Test fun homePetButtonOpensTheNativePetRoute() {
        compose.setContent {
            NativeHomeApp(NativeHomeState(loading = false), actions(), about = { _, _, _ -> }, storage = {}, requestLogs = {}, runtimeLogs = {}, workspaceMemory = { _, _ -> }, sceneModels = { _, _, _ -> }, modelProviders = {}, mcpTools = {}, agents = { _, _, _ -> }, agentConfig = { _, _ -> }, permissions = {},
                appearance = { _, _ -> }, background = { _, _ -> }, homePreferences = {},
                miscellaneous = { _, _ -> }, pet = {
                    top.yukonga.miuix.kmp.basic.Text("Native pet page")
                })
        }
        compose.onNodeWithContentDescription(label(R.string.omni_pet)).performClick()
        compose.onNodeWithText("Native pet page").assertIsDisplayed()
    }

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
