package cn.com.omnimind.bot.agent.projection

/**
 * JSON encode/decode matching Dart's `dart:convert` output byte for byte.
 *
 * Projected cards persist JSON strings (`rawResultJson`, `resultPreviewJson`,
 * …) that the Dart owner produced with `jsonEncode`; the Kotlin owner must
 * write identical strings so history comparisons and dedupe stay stable.
 * Dart escapes only `"`, `\` and control characters (and lone surrogates),
 * never `/` or other non-ASCII text. Integers decode as Int (Long when they
 * overflow), numbers with a fraction or exponent as Double.
 */
internal object DartJson {
    fun encode(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    fun decode(text: String): Any? = Parser(text).parseDocument()

    /** Dart `JsonEncoder.withIndent('  ').convert(value)`. */
    fun encodeIndented(value: Any?, indent: String = "  "): String =
        StringBuilder().also { writeIndented(it, value, indent, 0) }.toString()

    private fun writeIndented(out: StringBuilder, value: Any?, indent: String, depth: Int) {
        when (value) {
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    out.append("{}")
                    return
                }
                out.append("{\n")
                var first = true
                for ((key, nested) in value) {
                    if (!first) out.append(",\n")
                    first = false
                    repeat(depth + 1) { out.append(indent) }
                    writeString(out, key.toString())
                    out.append(": ")
                    writeIndented(out, nested, indent, depth + 1)
                }
                out.append('\n')
                repeat(depth) { out.append(indent) }
                out.append('}')
            }
            is Iterable<*> -> {
                val items = value.toList()
                if (items.isEmpty()) {
                    out.append("[]")
                    return
                }
                out.append("[\n")
                items.forEachIndexed { index, nested ->
                    if (index > 0) out.append(",\n")
                    repeat(depth + 1) { out.append(indent) }
                    writeIndented(out, nested, indent, depth + 1)
                }
                out.append('\n')
                repeat(depth) { out.append(indent) }
                out.append(']')
            }
            is Array<*> -> writeIndented(out, value.asList(), indent, depth)
            else -> write(out, value)
        }
    }

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value)
            is Double -> writeDouble(out, value)
            is Float -> writeDouble(out, value.toDouble())
            is Number -> out.append(value)
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, nested) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key.toString())
                    out.append(':')
                    write(out, nested)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (nested in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, nested)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.asList())
            else -> throw IllegalArgumentException("Converting object to an encodable object failed: $value")
        }
    }

    private fun writeDouble(out: StringBuilder, value: Double) {
        require(value.isFinite()) { "Converting object did not return an encodable object: $value" }
        out.append(dartDoubleToString(value))
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        var index = 0
        while (index < value.length) {
            val char = value[index]
            when {
                char == '"' -> out.append("\\\"")
                char == '\\' -> out.append("\\\\")
                char == '\b' -> out.append("\\b")
                char == '\t' -> out.append("\\t")
                char == '\n' -> out.append("\\n")
                char == '\u000C' -> out.append("\\f")
                char == '\r' -> out.append("\\r")
                char < ' ' -> appendUnicodeEscape(out, char)
                Character.isHighSurrogate(char) -> {
                    if (index + 1 < value.length && Character.isLowSurrogate(value[index + 1])) {
                        out.append(char).append(value[index + 1])
                        index += 1
                    } else {
                        appendUnicodeEscape(out, char)
                    }
                }
                Character.isLowSurrogate(char) -> appendUnicodeEscape(out, char)
                else -> out.append(char)
            }
            index += 1
        }
        out.append('"')
    }

    private fun appendUnicodeEscape(out: StringBuilder, char: Char) {
        out.append("\\u")
        val hex = Integer.toHexString(char.code)
        repeat(4 - hex.length) { out.append('0') }
        out.append(hex)
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (index != text.length) fail("Unexpected character")
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (index >= text.length) fail("Unexpected end of input")
            return when (val char = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (char == '-' || char.isDigit()) parseNumber() else fail("Unexpected character")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index += 1
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index += 1
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("Expected string key")
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    '}' -> {
                        index += 1
                        return result
                    }
                    else -> fail("Expected ',' or '}'")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            index += 1
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index += 1
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index += 1
                    ']' -> {
                        index += 1
                        return result
                    }
                    else -> fail("Expected ',' or ']'")
                }
            }
        }

        private fun parseString(): String {
            index += 1
            val out = StringBuilder()
            while (true) {
                if (index >= text.length) fail("Unterminated string")
                val char = text[index++]
                when {
                    char == '"' -> return out.toString()
                    char == '\\' -> {
                        if (index >= text.length) fail("Unterminated escape")
                        when (val escape = text[index++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) fail("Bad unicode escape")
                                val code = text.substring(index, index + 4).toIntOrNull(16)
                                    ?: fail("Bad unicode escape")
                                out.append(code.toChar())
                                index += 4
                            }
                            else -> fail("Bad escape '$escape'")
                        }
                    }
                    char < ' ' -> fail("Control character in string")
                    else -> out.append(char)
                }
            }
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek() == '-') index += 1
            if (peek() == '0') {
                index += 1
            } else if (peek()?.isDigit() == true) {
                while (peek()?.isDigit() == true) index += 1
            } else {
                fail("Bad number")
            }
            var isDouble = false
            if (peek() == '.') {
                isDouble = true
                index += 1
                if (peek()?.isDigit() != true) fail("Bad number")
                while (peek()?.isDigit() == true) index += 1
            }
            if (peek() == 'e' || peek() == 'E') {
                isDouble = true
                index += 1
                if (peek() == '+' || peek() == '-') index += 1
                if (peek()?.isDigit() != true) fail("Bad number")
                while (peek()?.isDigit() == true) index += 1
            }
            val literal = text.substring(start, index)
            if (isDouble) return literal.toDouble()
            literal.toIntOrNull()?.let { return it }
            literal.toLongOrNull()?.let { return it }
            return literal.toDouble()
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!text.startsWith(word, index)) fail("Unexpected character")
            index += word.length
            return value
        }

        private fun expect(char: Char) {
            if (peek() != char) fail("Expected '$char'")
            index += 1
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun skipWhitespace() {
            while (index < text.length && text[index] in " \t\n\r") index += 1
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("FormatException: $message at offset $index")
    }
}

/**
 * Dart `double.toString()`: integral values keep `.0`; magnitudes at or above
 * 1e21 and below 1e-6 use exponent form, as in JavaScript/Dart.
 */
internal fun dartDoubleToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    if (value == 0.0) return if (1.0 / value < 0) "-0.0" else "0.0"
    val magnitude = kotlin.math.abs(value)
    if (magnitude >= 1e21 || magnitude < 1e-6) {
        // Shortest round-trip digits in exponent form, e.g. 1e+21, 1.5e-7.
        val bd = java.math.BigDecimal(value.toString()).stripTrailingZeros()
        val unscaled = bd.unscaledValue().abs().toString()
        val exponent = unscaled.length - 1 - bd.scale()
        val mantissa = if (unscaled.length == 1) unscaled else unscaled[0] + "." + unscaled.substring(1)
        val sign = if (value < 0) "-" else ""
        val expSign = if (exponent >= 0) "+" else "-"
        return "$sign${mantissa}e$expSign${kotlin.math.abs(exponent)}"
    }
    val plain = java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    return if (plain.contains('.')) plain else "$plain.0"
}
