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
    /**
     * JSON-RPC request ids are scoped to a transport. Two local ACP
     * processes are therefore allowed to use the same id at the same time.
     * Keep all owners instead of silently overwriting the first one.
     */
    private val owners = ConcurrentHashMap<String, MutableSet<AcpServerRequestOwner>>()

    fun register(requestId: Any?, agentId: String, sessionId: String?) {
        val key = requestId.keyOrNull() ?: return
        val normalizedAgentId = agentId.trim()
        if (normalizedAgentId.isEmpty()) return
        val owner = AcpServerRequestOwner(
            agentId = normalizedAgentId,
            sessionId = sessionId?.trim()?.takeIf(String::isNotEmpty),
        )
        owners.compute(key) { _, current ->
            (current ?: ConcurrentHashMap.newKeySet()).apply { add(owner) }
        }
    }

    fun ownerFor(requestId: Any?): AcpServerRequestOwner? =
        ownersFor(requestId).singleOrNull()

    /**
     * Resolve only when the identity supplied by the client leaves one
     * transport owner. An ambiguous id is an error, never a reason to guess
     * from the selected Agent.
     */
    fun resolve(
        requestId: Any?,
        agentId: String? = null,
        sessionId: String? = null,
    ): AcpServerRequestOwner? {
        val candidates = ownersFor(requestId)
        if (candidates.isEmpty()) return null
        val normalizedAgentId = agentId.normalizedOrNull()
        val normalizedSessionId = sessionId.normalizedOrNull()
        val narrowed = candidates.filter { owner ->
            (normalizedAgentId == null || owner.agentId == normalizedAgentId) &&
                (normalizedSessionId == null ||
                    owner.sessionId == null ||
                    owner.sessionId == normalizedSessionId)
        }
        return when {
            narrowed.size == 1 -> narrowed.single()
            narrowed.isEmpty() -> throw IllegalArgumentException(
                "ACP server request identity does not match its owner."
            )
            else -> throw IllegalArgumentException(
                "ACP server request id is ambiguous; provide agentId or sessionId."
            )
        }
    }

    fun ownersFor(requestId: Any?): List<AcpServerRequestOwner> =
        requestId.keyOrNull()?.let { key ->
            owners[key]?.toList().orEmpty()
        }.orEmpty()

    /**
     * Remove one owner after a response. A bare removal is safe only when the
     * id is unique; otherwise it deliberately does nothing so another
     * transport's pending request cannot be lost.
     */
    fun remove(
        requestId: Any?,
        agentId: String? = null,
        sessionId: String? = null,
    ) {
        val key = requestId.keyOrNull() ?: return
        val current = owners[key] ?: return
        val normalizedAgentId = agentId.normalizedOrNull()
        val normalizedSessionId = sessionId.normalizedOrNull()
        val target = synchronized(current) {
            val candidates = current.filter { owner ->
                (normalizedAgentId == null || owner.agentId == normalizedAgentId) &&
                    (normalizedSessionId == null ||
                        owner.sessionId == null ||
                        owner.sessionId == normalizedSessionId)
            }
            if (candidates.size == 1 ||
                candidates.isEmpty() && current.size == 1
            ) {
                candidates.singleOrNull() ?: current.singleOrNull()
            } else {
                null
            }
        }
        if (target != null) {
            owners.computeIfPresent(key) { _, value ->
                synchronized(value) {
                    value.remove(target)
                    value.takeIf { it.isNotEmpty() }
                }
            }
        }
    }

    fun removeForSession(sessionId: String) {
        val normalized = sessionId.trim()
        if (normalized.isEmpty()) return
        owners.entries.removeIf { (_, value) ->
            synchronized(value) {
                value.removeIf { it.sessionId == normalized }
                value.isEmpty()
            }
        }
    }

    fun removeForAgent(agentId: String) {
        val normalized = agentId.trim()
        if (normalized.isEmpty()) return
        owners.entries.removeIf { (_, value) ->
            synchronized(value) {
                value.removeIf { it.agentId == normalized }
                value.isEmpty()
            }
        }
    }

    private fun Any?.keyOrNull(): String? = when (this) {
        null -> null
        is String -> trim().takeIf(String::isNotEmpty)
        else -> toString().trim().takeIf(String::isNotEmpty)
    }

    private fun String?.normalizedOrNull(): String? =
        this?.trim()?.takeIf(String::isNotEmpty)
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
