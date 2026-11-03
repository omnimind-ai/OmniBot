package cn.com.omnimind.baselib.privileged

import java.util.concurrent.ConcurrentHashMap

/** Shell 资源同时属于 Conversation 与 ACP Session，后端只在创建时选择一次。 */
internal class PrivilegedSessionRegistry {
    data class Binding(val ownerId: String, val acpSessionId: String?, val status: PrivilegedStatus)
    private val bindings = ConcurrentHashMap<String, Binding>()

    fun register(sessionId: String, binding: Binding) {
        check(bindings.putIfAbsent(sessionId, binding) == null) { "session_exists" }
    }

    fun selection(sessionId: String, ownerId: String, acpSessionId: String?): PrivilegedStatus? =
        bindings[sessionId]?.takeIf { it.ownerId == ownerId && it.acpSessionId == acpSessionId }?.status

    fun owns(sessionId: String, binding: Binding): Boolean = bindings[sessionId] == binding
    fun remove(sessionId: String, binding: Binding) { bindings.remove(sessionId, binding) }
    fun remove(sessionId: String) { bindings.remove(sessionId) }
    fun removeProvider(provider: PrivilegedProvider) { bindings.entries.removeAll { it.value.status.provider == provider } }
    fun forSession(acpSessionId: String): Map<String, Binding> = bindings.filterValues { it.acpSessionId == acpSessionId }

}
