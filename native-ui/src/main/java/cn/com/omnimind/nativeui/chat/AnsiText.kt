package cn.com.omnimind.nativeui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

private val ansiStandardColors = mapOf(
    30 to Color(0xFF1F2937),
    31 to Color(0xFFE06C75),
    32 to Color(0xFF98C379),
    33 to Color(0xFFE5C07B),
    34 to Color(0xFF61AFEF),
    35 to Color(0xFFC678DD),
    36 to Color(0xFF56B6C2),
    37 to Color(0xFFE5E7EB),
    90 to Color(0xFF6B7280),
    91 to Color(0xFFF7768E),
    92 to Color(0xFF9ECE6A),
    93 to Color(0xFFE0AF68),
    94 to Color(0xFF7AA2F7),
    95 to Color(0xFFBB9AF7),
    96 to Color(0xFF7DCFFF),
    97 to Color(0xFFF9FAFB),
)

private val sgrPattern = Regex("\u001B\\[([0-9;]*)m")
private val unsupportedAnsiPattern = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

/**
 * Port of Flutter's `AnsiTextSpanBuilder`: SGR bold and the 16 standard
 * foreground colors become span styles; other escape sequences are dropped.
 * Text outside any SGR state keeps the caller's base style.
 */
fun ansiAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    var color: Color? = null
    var cursor = 0

    fun appendSegment(segment: String) {
        val clean = segment.replace(unsupportedAnsiPattern, "")
        if (clean.isEmpty()) return
        if (!bold && color == null) {
            append(clean)
        } else {
            withStyle(
                SpanStyle(
                    color = color ?: Color.Unspecified,
                    fontWeight = if (bold) FontWeight.Bold else null,
                ),
            ) { append(clean) }
        }
    }

    for (match in sgrPattern.findAll(text)) {
        if (match.range.first > cursor) appendSegment(text.substring(cursor, match.range.first))
        val codesText = match.groupValues[1]
        val codes = if (codesText.isEmpty()) listOf(0) else codesText.split(';').map { it.toIntOrNull() ?: 0 }
        for (code in codes) {
            when (code) {
                0 -> {
                    bold = false
                    color = null
                }
                1 -> bold = true
                22 -> bold = false
                39 -> color = null
                else -> ansiStandardColors[code]?.let { color = it }
            }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) appendSegment(text.substring(cursor))
}
