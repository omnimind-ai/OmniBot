package cn.com.omnimind.bot.ui.platformview

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import cn.com.omnimind.baselib.util.OmniLog
import cn.com.omnimind.bot.BuildConfig
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import com.ai.assistance.operit.terminal.TerminalManager
import com.rk.settings.Settings
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val terminalCardBackgroundColor = Color.parseColor("#0B1220")
private val terminalCardForegroundColor = Color.parseColor("#E5E7EB")
private val ansiStandardColors = mapOf(
    30 to Color.parseColor("#1F2937"),
    31 to Color.parseColor("#E06C75"),
    32 to Color.parseColor("#98C379"),
    33 to Color.parseColor("#E5C07B"),
    34 to Color.parseColor("#61AFEF"),
    35 to Color.parseColor("#C678DD"),
    36 to Color.parseColor("#56B6C2"),
    37 to Color.parseColor("#E5E7EB"),
    90 to Color.parseColor("#6B7280"),
    91 to Color.parseColor("#F7768E"),
    92 to Color.parseColor("#9ECE6A"),
    93 to Color.parseColor("#E0AF68"),
    94 to Color.parseColor("#7AA2F7"),
    95 to Color.parseColor("#BB9AF7"),
    96 to Color.parseColor("#7DCFFF"),
    97 to Color.parseColor("#F9FAFB")
)
private val sgrPattern = Regex("\u001B\\[([0-9;]*)m")
private val unsupportedAnsiPattern = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

class EmbeddedTerminalPlatformViewFactory : PlatformViewFactory(StandardMessageCodec.INSTANCE) {
    companion object {
        const val VIEW_TYPE = "cn.com.omnimind.bot/embedded_terminal_view"

        private val registeredEngineKeys = mutableSetOf<Int>()

        @Synchronized
        fun registerWith(flutterEngine: FlutterEngine) {
            val engineKey = System.identityHashCode(flutterEngine)
            if (!registeredEngineKeys.add(engineKey)) {
                return
            }
            flutterEngine
                .platformViewsController
                .registry
                .registerViewFactory(VIEW_TYPE, EmbeddedTerminalPlatformViewFactory())
        }
    }

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val params = args as? Map<*, *>
        val sessionId = params?.get("sessionId")?.toString()?.trim().orEmpty()
        val transcript = params?.get("transcript")?.toString().orEmpty()
        return EmbeddedTerminalPlatformView(
            hostContext = context,
            sessionId = sessionId,
            transcript = transcript
        )
    }
}

private class EmbeddedTerminalPlatformView(
    hostContext: Context,
    private val sessionId: String,
    private val transcript: String
) : PlatformView {
    private val terminalManager = TerminalManager.getInstance(hostContext.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cardPaddingPx = hostContext.dpToPx(12)
    private val container = FrameLayout(hostContext).apply {
        setBackgroundColor(terminalCardBackgroundColor)
    }
    private val terminalView = TerminalView(hostContext, null).apply {
        setBackgroundColor(terminalCardBackgroundColor)
        setTerminalViewClient(NoOpTerminalViewClient())
        setTextSize(Settings.terminal_font_size)
        setTypeface(Typeface.MONOSPACE)
    }
    private val transcriptView = TextView(hostContext).apply {
        typeface = Typeface.MONOSPACE
        textSize = Settings.terminal_font_size.toFloat()
        setTextColor(terminalCardForegroundColor)
        setBackgroundColor(terminalCardBackgroundColor)
        setTextIsSelectable(true)
        setPadding(cardPaddingPx, cardPaddingPx, cardPaddingPx, cardPaddingPx)
        setLineSpacing(0f, 1.2f)
        text = buildAnsiStyledText(transcript)
    }
    private val transcriptContainer = ScrollView(hostContext).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        setBackgroundColor(terminalCardBackgroundColor)
        addView(
            HorizontalScrollView(hostContext).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                setBackgroundColor(terminalCardBackgroundColor)
                addView(
                    transcriptView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    init {
        showTranscript(transcript)

        scope.launch {
            terminalManager.terminalState.collectLatest { state ->
                val session = state.sessions.find { it.id == sessionId }
                val liveSession = terminalManager.getTerminalSession(sessionId)
                if (session != null && liveSession != null) {
                    attachLiveSession(liveSession)
                } else {
                    showTranscript(session?.transcript ?: transcript)
                }
            }
        }
    }

    private fun attachLiveSession(session: TerminalSession) {
        if (terminalView.parent == null) {
            container.removeAllViews()
            container.addView(
                terminalView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        terminalView.attachSession(session)
        applyTerminalPalette(terminalView)
        terminalView.onScreenUpdated()
    }

    private fun showTranscript(text: String) {
        transcriptView.text = buildAnsiStyledText(text)
        if (transcriptContainer.parent == null) {
            container.removeAllViews()
            container.addView(
                transcriptContainer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    override fun getView(): View = container

    override fun dispose() {
        scope.cancel()
    }
}

private fun applyTerminalPalette(terminalView: TerminalView) {
    terminalView.mEmulator?.mColors?.mCurrentColors?.apply {
        set(256, terminalCardForegroundColor)
        set(257, terminalCardBackgroundColor)
        set(258, terminalCardForegroundColor)
    }
}

private fun buildAnsiStyledText(text: String): CharSequence {
    if (text.isEmpty()) {
        return text
    }

    val builder = SpannableStringBuilder()
    var isBold = false
    var foregroundColor: Int? = null
    var cursor = 0

    for (match in sgrPattern.findAll(text)) {
        val start = match.range.first
        if (start > cursor) {
            appendAnsiSegment(
                builder = builder,
                value = stripUnsupportedAnsi(text.substring(cursor, start)),
                isBold = isBold,
                foregroundColor = foregroundColor
            )
        }

        val codesText = match.groups[1]?.value.orEmpty()
        val codes = if (codesText.isEmpty()) {
            listOf(0)
        } else {
            codesText.split(';').map { code -> code.toIntOrNull() ?: 0 }
        }
        for (code in codes) {
            when (code) {
                0 -> {
                    isBold = false
                    foregroundColor = null
                }
                1 -> isBold = true
                22 -> isBold = false
                39 -> foregroundColor = null
                else -> {
                    foregroundColor = ansiStandardColors[code] ?: foregroundColor
                }
            }
        }
        cursor = match.range.last + 1
    }

    if (cursor < text.length) {
        appendAnsiSegment(
            builder = builder,
            value = stripUnsupportedAnsi(text.substring(cursor)),
            isBold = isBold,
            foregroundColor = foregroundColor
        )
    }

    if (builder.isEmpty()) {
        return stripUnsupportedAnsi(text)
    }
    return builder
}

private fun appendAnsiSegment(
    builder: SpannableStringBuilder,
    value: String,
    isBold: Boolean,
    foregroundColor: Int?
) {
    if (value.isEmpty()) {
        return
    }
    val start = builder.length
    builder.append(value)
    val end = builder.length
    builder.setSpan(
        ForegroundColorSpan(foregroundColor ?: terminalCardForegroundColor),
        start,
        end,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    if (isBold) {
        builder.setSpan(
            StyleSpan(Typeface.BOLD),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
}

private fun stripUnsupportedAnsi(value: String): String {
    return value.replace(unsupportedAnsiPattern, "")
}

private fun Context.dpToPx(value: Int): Int {
    return (value * resources.displayMetrics.density).roundToInt()
}

private class NoOpTerminalViewClient : TerminalViewClient {
    override fun onScale(scale: Float): Float = 1.0f

    override fun onSingleTapUp(e: MotionEvent) = Unit

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = false

    override fun getInputMode(): Int = 0

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = false

    override fun readAltKey(): Boolean = false

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        if (BuildConfig.DEBUG) OmniLog.e(tag, message)
        else OmniLog.e(tag, "Terminal view error messageChars=${message.length}")
    }

    override fun logWarn(tag: String, message: String) {
        if (BuildConfig.DEBUG) OmniLog.w(tag, message)
        else OmniLog.w(tag, "Terminal view warning messageChars=${message.length}")
    }

    override fun logInfo(tag: String, message: String) {
        if (BuildConfig.DEBUG) OmniLog.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        if (BuildConfig.DEBUG) OmniLog.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        if (BuildConfig.DEBUG) OmniLog.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        if (BuildConfig.DEBUG) OmniLog.e(tag, message, e)
        else OmniLog.e(tag, "Terminal view exception type=${e.javaClass.simpleName}")
    }

    override fun logStackTrace(tag: String, e: Exception) {
        if (BuildConfig.DEBUG) OmniLog.e(tag, e.message.orEmpty(), e)
        else OmniLog.e(tag, "Terminal view exception type=${e.javaClass.simpleName}")
    }
}
