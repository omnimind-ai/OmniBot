package cn.com.omnimind.bot.agent

/**
 * One timing policy for the Conversation -> ACP Session -> Turn lifecycle.
 *
 * The Provider watchdog only detects a dead Provider transport. ACP owns the
 * Agent turn lifecycle; the host must not infer turn completion from silence.
 */
internal object AgentTurnTimingPolicy {
    const val PROVIDER_STREAM_IDLE_TIMEOUT_MS = 90_000L
}
