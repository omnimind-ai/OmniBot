package cn.com.omnimind.bot.agent.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * The host-side owner of an ACP Agent->Client request.
 *
 * ACP request ids are only meaningful on the transport that created them. A
 * session id is normally enough to route a reply, but request-scoped
 * elicitation and extension requests may not carry one. Keeping this small
 * index at the shared host boundary makes the request lifecycle explicit:
 * register when the request is emitted, route by that owner, and remove it
 * when the request is answered or the transport is closed.
 */
internal data class AcpServerRequestOwner(
    val agentId: String,
    val sessionId: String?,
)

internal class AcpServerRequestOwnerRegistry {
    private val owners = ConcurrentHashMap<String, AcpServerRequestOwner>()

    fun register(requestId: Any?, agentId: String, sessionId: String?) {
        val key = requestId.keyOrNull() ?: return
        val normalizedAgentId = agentId.trim()
        if (normalizedAgentId.isEmpty()) return
        owners[key] = AcpServerRequestOwner(
            agentId = normalizedAgentId,
            sessionId = sessionId?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    fun ownerFor(requestId: Any?): AcpServerRequestOwner? =
        requestId.keyOrNull()?.let(owners::get)

    fun remove(requestId: Any?) {
        requestId.keyOrNull()?.let(owners::remove)
    }

    fun removeForSession(sessionId: String) {
        val normalized = sessionId.trim()
        if (normalized.isEmpty()) return
        owners.entries.removeIf { it.value.sessionId == normalized }
    }

    fun removeForAgent(agentId: String) {
        val normalized = agentId.trim()
        if (normalized.isEmpty()) return
        owners.entries.removeIf { it.value.agentId == normalized }
    }

    private fun Any?.keyOrNull(): String? = when (this) {
        null -> null
        is String -> trim().takeIf(String::isNotEmpty)
        else -> toString().trim().takeIf(String::isNotEmpty)
    }
}

internal enum class AcpServerRequestRuntime {
    LOCAL,
    REMOTE,
}

internal sealed interface AcpServerRequestRoute {
    data class Local(val agentId: String) : AcpServerRequestRoute
    data object Remote : AcpServerRequestRoute
}

/**
 * Resolve the response transport once, from request identity.
 *
 * The pending request owner outranks UI/session metadata because it was
 * captured at the exact point the ACP request crossed the process boundary.
 * The selected runtime is only a compatibility fallback for old clients that
 * cannot provide any identity at all.
 */
internal fun resolveAcpServerRequestRoute(
    remoteEnabled: Boolean,
    requestedAgentId: String?,
    sessionAgentId: String?,
    conversationAgentId: String?,
    pendingRequestAgentId: String?,
    selectedRuntime: AcpServerRequestRuntime,
    localCodexSessionOwned: Boolean = false,
): AcpServerRequestRoute {
    val pendingOwner = pendingRequestAgentId.normalizedId()
    val explicitOwner = listOf(requestedAgentId, sessionAgentId, conversationAgentId)
        .firstNotNullOfOrNull(String?::normalizedId)
    if (pendingOwner != null) {
        require(explicitOwner == null || explicitOwner == pendingOwner) {
            "ACP server request owner does not match response identity."
        }
        return AcpServerRequestRoute.Local(pendingOwner)
    }
    if (explicitOwner != null) {
        return if (
            remoteEnabled &&
            explicitOwner == AcpAgentProfileStore.CODEX_AGENT_ID &&
            selectedRuntime == AcpServerRequestRuntime.REMOTE &&
            !localCodexSessionOwned
        ) {
            AcpServerRequestRoute.Remote
        } else {
            AcpServerRequestRoute.Local(explicitOwner)
        }
    }
    return if (selectedRuntime == AcpServerRequestRuntime.LOCAL) {
        // The caller fills this legacy case with the selected local profile.
        AcpServerRequestRoute.Local("")
    } else {
        AcpServerRequestRoute.Remote
    }
}

private fun String?.normalizedId(): String? =
    this?.trim()?.takeIf(String::isNotEmpty)
