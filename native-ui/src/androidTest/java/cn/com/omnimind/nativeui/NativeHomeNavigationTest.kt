package cn.com.omnimind.nativeui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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
            NativeHomeApp(state.value, {}, { state.value = state.value.copy(localServiceEnabled = it) }, {})
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
            ), opened::add, {}, {})
        }
        compose.onNodeWithContentDescription(label(R.string.omni_open_drawer)).performClick()
        compose.onNodeWithText("Saved thread").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LegacyDestination.Conversation(42, "agent")), opened)
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null), "native-home-verification").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
