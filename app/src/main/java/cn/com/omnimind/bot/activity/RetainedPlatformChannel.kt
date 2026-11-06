package cn.com.omnimind.bot.activity

import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.systemchannels.PlatformChannel

/**
 * Keeps Flutter's system-bar declarations for the lifetime of the cached engine.
 * PlatformPlugin belongs to an Activity and loses its cache when that host exits;
 * Dart may still consider the same style delivered and omit sending it again.
 */
class RetainedPlatformChannel(executor: DartExecutor) : PlatformChannel(executor) {
    private var host: PlatformMessageHandler? = null
    private var style: SystemChromeStyle? = null
    private var mode: SystemUiMode? = null
    private var overlays: List<SystemUiOverlay>? = null

    init {
        // Keep accepting declarations while no Activity is attached. The relay
        // releases the old host and never queues window actions such as Back.
        super.setPlatformMessageHandler(object : PlatformMessageHandler {
            override fun setSystemUiOverlayStyle(value: SystemChromeStyle) {
                style = value
                host?.setSystemUiOverlayStyle(value)
            }

            override fun showSystemUiMode(value: SystemUiMode) {
                mode = value
                overlays = null
                host?.showSystemUiMode(value)
            }

            override fun showSystemOverlays(value: List<SystemUiOverlay>) {
                overlays = value.toList()
                mode = null
                host?.showSystemOverlays(value)
            }

            override fun restoreSystemUiOverlays() { host?.restoreSystemUiOverlays() }
            override fun setSystemUiChangeListener() { host?.setSystemUiChangeListener() }
            override fun playSystemSound(value: SoundType) { host?.playSystemSound(value) }
            override fun vibrateHapticFeedback(value: HapticFeedbackType) { host?.vibrateHapticFeedback(value) }
            override fun setPreferredOrientations(value: Int) { host?.setPreferredOrientations(value) }
            override fun setApplicationSwitcherDescription(value: AppSwitcherDescription) {
                host?.setApplicationSwitcherDescription(value)
            }
            override fun setFrameworkHandlesBack(value: Boolean) { host?.setFrameworkHandlesBack(value) }
            override fun popSystemNavigator() { host?.popSystemNavigator() }
            override fun getClipboardData(format: ClipboardContentFormat?): CharSequence? = host?.getClipboardData(format)
            override fun setClipboardData(text: String) { host?.setClipboardData(text) }
            override fun clipboardHasStrings(): Boolean = host?.clipboardHasStrings() ?: false
            override fun share(text: String) { host?.share(text) }
        })
    }

    override fun setPlatformMessageHandler(handler: PlatformMessageHandler?) {
        host = handler
    }

    /** Call after PlatformPlugin's constructor has finished registering its host. */
    fun replaySystemUi() {
        val target = host ?: return
        // Applying a mode can reset window flags, so the style must follow it.
        mode?.let(target::showSystemUiMode)
        overlays?.let(target::showSystemOverlays)
        style?.let(target::setSystemUiOverlayStyle)
    }
}
