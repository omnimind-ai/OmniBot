package cn.com.omnimind.bot.activity

import android.app.Activity
import android.app.Application
import android.view.View
import androidx.core.view.WindowCompat
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.systemchannels.PlatformChannel
import io.flutter.embedding.engine.systemchannels.PlatformChannel.Brightness
import io.flutter.embedding.engine.systemchannels.PlatformChannel.PlatformMessageHandler
import io.flutter.embedding.engine.systemchannels.PlatformChannel.SystemChromeStyle
import io.flutter.embedding.engine.systemchannels.PlatformChannel.SystemUiMode
import io.flutter.embedding.engine.systemchannels.PlatformChannel.SystemUiOverlay
import io.flutter.plugin.common.BinaryMessenger.BinaryMessageHandler
import io.flutter.plugin.common.JSONMethodCodec
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.platform.PlatformPlugin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class RetainedPlatformChannelTest {
    @Test
    fun `style received without a window is replayed after edge to edge mode`() {
        val fixture = ChannelFixture()
        fixture.send("SystemChrome.setSystemUIOverlayStyle", style("Brightness.dark"))
        fixture.send("SystemChrome.setEnabledSystemUIMode", "SystemUiMode.edgeToEdge")

        val host = mock(PlatformMessageHandler::class.java)
        fixture.channel.setPlatformMessageHandler(host)
        // PlatformPlugin registers its handler before its constructor finishes.
        verifyNoInteractions(host)
        fixture.channel.replaySystemUi()

        val captured = ArgumentCaptor.forClass(SystemChromeStyle::class.java)
        val order = inOrder(host)
        order.verify(host).showSystemUiMode(SystemUiMode.EDGE_TO_EDGE)
        order.verify(host).setSystemUiOverlayStyle(captured.capture())
        order.verifyNoMoreInteractions()
        assertEquals(Brightness.DARK, captured.value.statusBarIconBrightness)
        assertEquals(Brightness.DARK, captured.value.systemNavigationBarIconBrightness)
    }

    @Test
    fun `detached updates replace the cached style without touching the old host`() {
        val fixture = ChannelFixture()
        val previous = mock(PlatformMessageHandler::class.java)
        fixture.channel.setPlatformMessageHandler(previous)
        fixture.send("SystemChrome.setSystemUIOverlayStyle", style("Brightness.dark"))
        clearInvocations(previous)
        fixture.channel.setPlatformMessageHandler(null)

        fixture.send("SystemChrome.setSystemUIOverlayStyle", style("Brightness.light"))
        fixture.send("SystemChrome.setEnabledSystemUIMode", "SystemUiMode.edgeToEdge")
        fixture.send("SystemChrome.restoreSystemUIOverlays")
        verifyNoInteractions(previous)

        val current = mock(PlatformMessageHandler::class.java)
        fixture.channel.setPlatformMessageHandler(current)
        fixture.channel.replaySystemUi()
        val captured = ArgumentCaptor.forClass(SystemChromeStyle::class.java)
        verify(current).showSystemUiMode(SystemUiMode.EDGE_TO_EDGE)
        verify(current).setSystemUiOverlayStyle(captured.capture())
        verifyNoMoreInteractions(current)
        assertEquals(Brightness.LIGHT, captured.value.statusBarIconBrightness)
        verifyNoInteractions(previous)
    }

    @Test
    fun `only the latest manual overlays or mode is replayed`() {
        val fixture = ChannelFixture()
        fixture.send("SystemChrome.setEnabledSystemUIMode", "SystemUiMode.edgeToEdge")
        fixture.send("SystemChrome.setEnabledSystemUIOverlays", listOf("SystemUiOverlay.top"))
        val first = mock(PlatformMessageHandler::class.java)
        fixture.channel.setPlatformMessageHandler(first)
        fixture.channel.replaySystemUi()
        verify(first).showSystemOverlays(listOf(SystemUiOverlay.TOP_OVERLAYS))
        verifyNoMoreInteractions(first)

        fixture.channel.setPlatformMessageHandler(null)
        fixture.send("SystemChrome.setEnabledSystemUIMode", "SystemUiMode.edgeToEdge")
        val second = mock(PlatformMessageHandler::class.java)
        fixture.channel.setPlatformMessageHandler(second)
        fixture.channel.replaySystemUi()
        verify(second).showSystemUiMode(SystemUiMode.EDGE_TO_EDGE)
        verifyNoMoreInteractions(second)
    }

    @Test
    fun `navigation and clipboard operations are never queued for a future window`() {
        val fixture = ChannelFixture()
        fixture.send("SystemNavigator.pop")
        fixture.send("Clipboard.setData", mapOf("text" to "discard while detached"))
        assertNull(fixture.send("Clipboard.getData", "text/plain"))
        assertFalse((fixture.send("Clipboard.hasStrings") as JSONObject).getBoolean("value"))

        val host = mock(PlatformMessageHandler::class.java)
        fixture.channel.setPlatformMessageHandler(host)
        fixture.channel.replaySystemUi()
        verifyNoInteractions(host)

        `when`(host.getClipboardData(PlatformChannel.ClipboardContentFormat.PLAIN_TEXT))
            .thenReturn("current clipboard")
        `when`(host.clipboardHasStrings()).thenReturn(true)
        fixture.send("SystemNavigator.pop")
        fixture.send("Clipboard.setData", mapOf("text" to "new clipboard"))
        assertEquals(
            "current clipboard",
            (fixture.send("Clipboard.getData", "text/plain") as JSONObject).getString("text")
        )
        assertTrue((fixture.send("Clipboard.hasStrings") as JSONObject).getBoolean("value"))
        verify(host).popSystemNavigator()
        verify(host).setClipboardData("new clipboard")
        verify(host).getClipboardData(PlatformChannel.ClipboardContentFormat.PLAIN_TEXT)
        verify(host).clipboardHasStrings()
        verifyNoMoreInteractions(host)
    }

    @Test
    fun `new platform plugin restores dark icons without another Dart style message`() {
        val fixture = ChannelFixture()
        val firstActivity = Robolectric.buildActivity(Activity::class.java).setup()
        val secondActivity = Robolectric.buildActivity(Activity::class.java).setup()
        var firstPlugin: PlatformPlugin? = null
        var secondPlugin: PlatformPlugin? = null
        try {
            firstPlugin = PlatformPlugin(firstActivity.get(), fixture.channel)
            fixture.send("SystemChrome.setEnabledSystemUIMode", "SystemUiMode.edgeToEdge")
            fixture.send("SystemChrome.setSystemUIOverlayStyle", style("Brightness.dark"))
            firstPlugin.destroy()
            firstPlugin = null

            val activity = secondActivity.get()
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            secondPlugin = PlatformPlugin(activity, fixture.channel)
            fixture.channel.replaySystemUi()
            // FlutterActivity applies default flags after plugin construction;
            // onPostResume then asks the plugin to restore its remembered style.
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            secondPlugin.updateSystemUiOverlays()

            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        } finally {
            secondPlugin?.destroy()
            firstPlugin?.destroy()
            secondActivity.pause().stop().destroy()
            firstActivity.pause().stop().destroy()
        }
    }

    private class ChannelFixture {
        private val executor = mock(DartExecutor::class.java)
        val channel = RetainedPlatformChannel(executor)
        private val input: BinaryMessageHandler

        init {
            val captured = ArgumentCaptor.forClass(BinaryMessageHandler::class.java)
            verify(executor).setMessageHandler(eq("flutter/platform"), captured.capture())
            input = captured.value
        }

        fun send(method: String, arguments: Any? = null): Any? {
            val encoded = JSONMethodCodec.INSTANCE.encodeMethodCall(MethodCall(method, arguments))
            encoded.flip()
            var replied = false
            var result: Any? = null
            input.onMessage(encoded) { envelope ->
                replied = true
                requireNotNull(envelope).flip()
                result = JSONMethodCodec.INSTANCE.decodeEnvelope(envelope)
            }
            assertTrue("The platform request must receive a reply even without a window: $method", replied)
            return result
        }
    }

    private fun style(brightness: String) = mapOf(
        "statusBarColor" to 0,
        "statusBarIconBrightness" to brightness,
        "systemStatusBarContrastEnforced" to false,
        "systemNavigationBarColor" to 0,
        "systemNavigationBarIconBrightness" to brightness,
        "systemNavigationBarDividerColor" to 0,
        "systemNavigationBarContrastEnforced" to false
    )
}
