package cn.com.omnimind.baselib.llm

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SceneTitleConfigStore {
    const val SCENE_ID = "scene.conversation.title"
    private const val KEY = "scene_conversation_title_enabled"
    private val settings = TitleGenerationSettings(
        read = { MMKV.defaultMMKV()?.decodeBool(KEY, true) ?: true },
        write = { MMKV.defaultMMKV()?.encode(KEY, it) },
    )

    fun isEnabled(): Boolean = settings.isEnabled()
    suspend fun save(enabled: Boolean): Boolean = settings.save(enabled)
    suspend fun snapshot(): Long? = settings.snapshot()
    suspend fun commitIfEnabled(expectedRevision: Long, commit: suspend () -> Unit) =
        settings.commitIfEnabled(expectedRevision, commit)
}

/** Disabling invalidates in-flight results, including after a subsequent re-enable. */
internal class TitleGenerationSettings(
    private val read: () -> Boolean,
    private val write: (Boolean) -> Unit,
) {
    private val mutex = Mutex()
    private var revision = 0L

    fun isEnabled(): Boolean = read()

    suspend fun save(enabled: Boolean): Boolean = mutex.withLock {
        write(enabled)
        revision++
        enabled
    }

    suspend fun snapshot(): Long? = mutex.withLock { revision.takeIf { isEnabled() } }

    suspend fun commitIfEnabled(expectedRevision: Long, commit: suspend () -> Unit) = mutex.withLock {
        if (isEnabled() && revision == expectedRevision) commit()
    }
}
