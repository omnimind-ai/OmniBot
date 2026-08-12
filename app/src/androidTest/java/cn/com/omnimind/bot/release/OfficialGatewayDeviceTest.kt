package cn.com.omnimind.bot.release

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.com.omnimind.baselib.account.OmniAccount
import cn.com.omnimind.baselib.account.AiAccessMode
import cn.com.omnimind.bot.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Release-candidate live gate. It runs only from the debug instrumentation APK
 * and deliberately never logs or returns the encrypted account credential.
 */
@RunWith(AndroidJUnit4::class)
class OfficialGatewayDeviceTest {
    @Before
    fun initializeAccountClient() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OmniAccount.initialize(
            context = context,
            baseUrl = BuildConfig.BASE_URL,
            platformGatewayUrl = BuildConfig.AI_GATEWAY_URL,
        )
    }

    @Test
    fun signedInDeviceReceivesCompleteOfficialCatalogAndQuota() = runBlocking {
        val repository = OmniAccount.repository()
        assertTrue("device debug session is not signed in", repository.isSignedIn())

        var settings = repository.getAiSettings()
        if (settings.effectiveMode != AiAccessMode.PLATFORM) {
            settings = repository.updateAiSettings(AiAccessMode.PLATFORM)
        }
        assertEquals("platform", settings.effectiveMode.name.lowercase())
        assertTrue("weekly platform quota is not enabled", settings.platform.weeklyLimit > 0)

        val catalog = repository.getPlatformModelCatalog()
        assertTrue("official catalog metadata is missing", catalog.hasOfficialCatalog)
        assertNotNull("text default is missing", catalog.defaults.text)
        assertNotNull("vision default is missing", catalog.defaults.vision)
        assertNotNull("image default is missing", catalog.defaults.image)
        assertNotNull("TTS default is missing", catalog.defaults.tts)
        assertNotNull("STT default is missing", catalog.defaults.stt)
        assertNotNull("TTS voice alias is missing", catalog.defaults.ttsVoice)
        assertTrue(catalog.capabilities.text.isNotEmpty())
        assertTrue(catalog.capabilities.vision.isNotEmpty())
        assertTrue(catalog.capabilities.image.isNotEmpty())
        assertTrue(catalog.capabilities.tts.isNotEmpty())
        assertTrue(catalog.capabilities.stt.isNotEmpty())
        assertTrue(catalog.capabilities.ttsVoices?.isNotEmpty() == true)
    }
}
