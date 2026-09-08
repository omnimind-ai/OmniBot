package cn.com.omnimind.bot.agent

// Copyright (c) 2025 Mario Zechner. MIT; docs/third-party/pi-compaction-LICENSE.txt.
/** Error-pattern port of pi-mono utils/overflow.ts, MIT. See docs/third-party/compaction.md. */
internal object AgentContextOverflow {
    private val overflow = listOf(
        "prompt is too long", "request_too_large", "input is too long for requested model",
        "exceeds the context window",
        "exceeds (?:the )?(?:model'?s )?maximum context length(?: of [\\d,]+ tokens?|\\s*\\([\\d,]+\\))",
        "input token count.*exceeds the maximum", "maximum prompt length is \\d+",
        "reduce the length of the messages", "maximum context length is \\d+ tokens",
        "exceeds (?:the )?maximum allowed input length of [\\d,]+ tokens?",
        "input \\(\\d+ tokens\\) is longer than the model'?s context length \\(\\d+ tokens\\)",
        "exceeds the limit of \\d+", "exceeds the available context size",
        "greater than the context length", "context window exceeds limit", "exceeded model token limit",
        "too large for model with \\d+ maximum context length",
        "prompt has [\\d,]+ tokens?, but the configured context size is [\\d,]+ tokens?",
        "model_context_window_exceeded", "prompt too long; exceeded (?:max )?context length",
        "range of input length should be", "context[_ ]length[_ ]exceeded",
        "too many tokens", "token limit exceeded", "^4(?:00|13)\\s*(?:status code)?\\s*\\(no body\\)",
        // Captured OpenOmniBot provider regressions; avoid generic 'maximum length'.
        "prompt exceeds max length", "input length [\\d,]+ exceeds the maximum length [\\d,]+",
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    private val nonOverflow = listOf(
        "^(Throttling error|Service unavailable):", "rate limit", "too many requests",
    ).map { Regex(it, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }

    fun isOverflow(error: AgentStreamRequestException): Boolean {
        // HTTP failures from auth, rate limiting and the server are not prompt recovery.
        if (error.statusCode != null && error.statusCode !in setOf(400, 413, 422)) return false
        val diagnostic = error.reason + "\n" + error.responseBody.orEmpty()
        return nonOverflow.none { it.containsMatchIn(diagnostic) } &&
            overflow.any { it.containsMatchIn(diagnostic) }
    }
}
