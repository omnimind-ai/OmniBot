package cn.com.omnimind.nativeui.chat

import android.content.Context
import android.graphics.Color as AndroidColor
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * Markdown as the Flutter chat renders it: CommonMark, tables,
 * strikethrough, links, inline `$…$` and block `$$…$$` math.
 *
 * Markwon's LaTeX plugin only knows `$$` delimiters, so single-dollar math
 * is normalized first (outside code). Links go to [onOpenLink] instead of an
 * implicit browser intent so the host decides how `omnibot://` links open.
 */
@Composable
fun ChatMarkdownText(
    markdown: String,
    textColor: Color,
    linkColor: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier,
    textSizeSp: Float = 15f,
    onOpenLink: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val currentOnOpenLink = rememberUpdatedState(onOpenLink)
    val markwon = remember(context, linkColor, codeBackground, textSizeSp) {
        chatMarkwon(context, linkColor.toArgb(), codeBackground.toArgb(), textSizeSp) { currentOnOpenLink.value(it) }
    }
    val normalized = remember(markdown) { normalizeChatMarkdownMath(markdown) }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextView(viewContext).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextIsSelectable(false)
                highlightColor = AndroidColor.TRANSPARENT
            }
        },
        update = { view ->
            view.setTextColor(textColor.toArgb())
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
            view.setLineSpacing(0f, 1.4f)
            markwon.setMarkdown(view, normalized)
        },
    )
}

private fun chatMarkwon(
    context: Context,
    linkColor: Int,
    codeBackground: Int,
    textSizeSp: Float,
    onOpenLink: (String) -> Unit,
): Markwon {
    val mathTextSize = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        textSizeSp,
        context.resources.displayMetrics,
    )
    return Markwon.builder(context)
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(JLatexMathPlugin.create(mathTextSize) { builder -> builder.inlinesEnabled(true) })
        .usePlugin(TablePlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(linkColor)
                    .codeBackgroundColor(codeBackground)
                    .codeBlockBackgroundColor(codeBackground)
            }

            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { _, link -> onOpenLink(link) }
            }
        })
        .build()
}

private val codeFence = Regex("(?m)^ {0,3}(```|~~~)")
private val inlineMath = Regex("(?<!\\\\)(?<!\\$)\\$([^$\\n]+?)\\$(?!\\$)")

/**
 * Rewrites Flutter's inline `$…$` math to Markwon's `$$…$$`, leaving fenced
 * code and inline code spans untouched. Block `$$` math passes through.
 */
fun normalizeChatMarkdownMath(markdown: String): String {
    if (!markdown.contains('$')) return markdown
    val out = StringBuilder(markdown.length + 16)
    var inFence = false
    var fenceMarker = ""
    for ((index, line) in markdown.split('\n').withIndex()) {
        if (index > 0) out.append('\n')
        val fence = codeFence.find(line)
        if (fence != null && fence.range.first <= 3) {
            val marker = fence.groupValues[1]
            if (!inFence) {
                inFence = true
                fenceMarker = marker
            } else if (marker == fenceMarker) {
                inFence = false
            }
            out.append(line)
            continue
        }
        if (inFence || line.trim().startsWith("$$")) {
            out.append(line)
            continue
        }
        out.append(normalizeInlineMath(line))
    }
    return out.toString()
}

private fun normalizeInlineMath(line: String): String {
    // Split on backtick code spans; only even segments are prose.
    val parts = line.split('`')
    if (parts.size % 2 == 0) return line // unbalanced: leave untouched
    return parts.mapIndexed { index, part ->
        if (index % 2 == 1) part else inlineMath.replace(part) { "\$\$${it.groupValues[1]}\$\$" }
    }.joinToString("`")
}
