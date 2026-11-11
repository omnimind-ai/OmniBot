package cn.com.omnimind.baselib.llm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TitleGenerationSettingsTest {
    @Test fun `disable discards in flight results even when reenabled and persists preference`() = runBlocking {
        var persisted = true
        val settings = TitleGenerationSettings({ persisted }, { persisted = it })
        val revision = settings.snapshot()!!
        settings.save(false)
        assertFalse(persisted)
        assertNull(settings.snapshot())
        var commits = 0
        settings.commitIfEnabled(revision) { commits++ }
        settings.save(true)
        settings.commitIfEnabled(revision) { commits++ }
        assertEquals(0, commits)
        settings.commitIfEnabled(settings.snapshot()!!) { commits++ }
        assertEquals(1, commits)
        settings.save(false)
        assertFalse(TitleGenerationSettings({ persisted }, { persisted = it }).isEnabled())
    }

    @Test fun `setting change and commit have one serialization boundary`() = runBlocking {
        var enabled = true
        val settings = TitleGenerationSettings({ enabled }, { enabled = it })
        val revision = settings.snapshot()!!
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var committed = false
        val commit = async {
            settings.commitIfEnabled(revision) {
                started.complete(Unit)
                release.await()
                committed = true
            }
        }
        started.await()
        val disable = async { settings.save(false) }
        assertTrue(enabled)
        release.complete(Unit)
        commit.await()
        disable.await()
        assertTrue(committed)
        assertFalse(enabled)
    }
}
