package cn.com.omnimind.bot.agent

import android.app.Application
import android.app.Service
import android.content.Intent
import android.media.AudioFocusRequest as PlatformAudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowMediaPlayer

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    manifest = Config.NONE,
    shadows = [AgentMusicPlaybackServiceTest.ForegroundAudioManager::class],
)
class AgentMusicPlaybackServiceTest {
    @After
    fun tearDown() {
        ForegroundAudioManager.onRequest = null
    }

    @Test
    fun `play and resume establish foreground service before acquiring focus`() {
        val controller = Robolectric.buildService(AgentMusicPlaybackService::class.java).create()
        val service = controller.get()
        val shadow = shadowOf(service)
        var requests = 0
        ForegroundAudioManager.onRequest = {
            assertTrue(shadow.isLastForegroundNotificationAttached)
            assertFalse(shadow.isForegroundStopped)
            requests++
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(60_000, 0) }
        try {
            service.onStartCommand(playIntent(service), 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(AgentMusicPlaybackStateStore.STATE_PLAYING, AgentMusicPlaybackStateStore.snapshot().state)
            assertEquals(1, requests)

            shadowOf(service.getSystemService(AudioManager::class.java))
                .lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            service.onStartCommand(Intent(service, AgentMusicPlaybackService::class.java).apply {
                action = "cn.com.omnimind.bot.agent.music.RESUME"
            }, 0, 2)
            assertEquals(2, requests)
            assertEquals(AgentMusicPlaybackStateStore.STATE_PLAYING, AgentMusicPlaybackStateStore.snapshot().state)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `denied audio focus reports error and removes foreground notification`() {
        val controller = Robolectric.buildService(AgentMusicPlaybackService::class.java).create()
        val service = controller.get()
        val shadow = shadowOf(service)
        var requested = false
        ForegroundAudioManager.onRequest = {
            assertTrue(shadow.isLastForegroundNotificationAttached)
            requested = true
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        try {
            service.onStartCommand(playIntent(service), 0, 1)
            assertTrue(requested)
            assertEquals(AgentMusicPlaybackStateStore.STATE_ERROR, AgentMusicPlaybackStateStore.snapshot().state)
            assertTrue(shadow.isForegroundStopped)
            assertTrue(shadow.isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `rejected foreground start does not request audio focus`() {
        val controller = Robolectric.buildService(AgentMusicPlaybackService::class.java).create()
        val service = controller.get()
        shadowOf(service).setThrowInStartForeground(SecurityException("Foreground start denied"))
        var requested = false
        ForegroundAudioManager.onRequest = {
            requested = true
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        try {
            service.onStartCommand(playIntent(service), 0, 1)
            assertFalse(requested)
            assertEquals(AgentMusicPlaybackStateStore.STATE_ERROR, AgentMusicPlaybackStateStore.snapshot().state)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    private fun playIntent(service: Service) = Intent(service, AgentMusicPlaybackService::class.java).apply {
        action = "cn.com.omnimind.bot.agent.music.PLAY"
        putExtra("extra_source", "url")
        putExtra("extra_playback_target", "https://example.com/audio.mp3")
        putExtra("extra_title", "Test audio")
    }

    @Implements(AudioManager::class)
    class ForegroundAudioManager : ShadowAudioManager() {
        companion object {
            var onRequest: ((PlatformAudioFocusRequest) -> Int)? = null
        }

        @Implementation
        override fun requestAudioFocus(request: PlatformAudioFocusRequest): Int {
            super.requestAudioFocus(request)
            return onRequest?.invoke(request) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
    }
}
