package cn.com.omnimind.baselib.util

import com.google.gson.GsonBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeLogJournalTest {
    @Test
    fun `unicode and JSON escapes fit the field budget without splitting surrogate pairs`() {
        val original = "\uD800start\uDC00" + "中🌊\u0000\"\\\u2028".repeat(10_000)
        val bounded = boundedRuntimeLogText(original, RuntimeLogLimits.MESSAGE_BYTES)
        val encoded = GsonBuilder().disableHtmlEscaping().create().toJson(bounded)

        assertTrue(bounded.startsWith("\uFFFDstart\uFFFD"))
        assertTrue(bounded.endsWith(RuntimeLogLimits.OMITTED))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= RuntimeLogLimits.MESSAGE_BYTES + 2)
        assertEquals(bounded, bounded.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        assertTrue("bounded content must retain some of the original", bounded.contains("中🌊"))
    }

    @Test
    fun `large messages keep the newest logs within storage and channel budgets`() {
        val storage = FakeStorage()
        val journal = RuntimeLogJournal(storage)
        val text = "中🌊\n\"".repeat(5000)
        repeat(80) { index ->
            journal.append(
                RuntimeLogEntry(
                    id = "log-$index", createdAt = index.toLong(), message = text, stackTrace = text,
                ),
            )
        }

        val logs = journal.listRecent(200)
        assertEquals("log-79", logs.first().id)
        assertTrue(logs.size in 1 until 80)
        assertTrue(storage.values.getValue(RuntimeLogJournal.KEY).toByteArray(Charsets.UTF_8).size <= RuntimeLogLimits.MAX_BYTES)
        assertTrue(3 + logs.sumOf { RuntimeLogJournal.channelEntryBytes(it) } <= RuntimeLogLimits.MAX_BYTES)
        logs.forEach {
            assertTrue(it.message.endsWith(RuntimeLogLimits.OMITTED))
            assertTrue(it.stackTrace!!.endsWith(RuntimeLogLimits.OMITTED))
        }
    }

    @Test
    fun `JNI string upper bound keeps persisted emoji logs readable across encodings`() {
        val storage = FakeStorage()
        val journal = RuntimeLogJournal(storage)
        val emoji = "🌊".repeat(10_000)
        repeat(80) { index ->
            journal.append(RuntimeLogEntry(id = "emoji-$index", message = emoji, stackTrace = emoji))
        }
        val encoded = storage.values.getValue(RuntimeLogJournal.KEY)
        val storageUpperBound = RuntimeLogJournal.storageStringByteUpperBound(encoded)
        assertTrue("cover the larger JNI-compatible encoding", storageUpperBound > encoded.toByteArray(Charsets.UTF_8).size)
        assertTrue(storageUpperBound <= RuntimeLogLimits.MAX_BYTES)
        assertTrue("exercise the near-full storage boundary", storageUpperBound > RuntimeLogLimits.MAX_BYTES - 24 * 1024)
        assertEquals("emoji-79", journal.listRecent().first().id)
        journal.append(RuntimeLogEntry(id = "after", message = "still writable"))
        assertEquals("after", journal.listRecent().first().id)
    }

    @Test
    fun `small logs retain 200 entries and respect the requested limit`() {
        val journal = RuntimeLogJournal(FakeStorage())
        repeat(205) { journal.append(RuntimeLogEntry(id = "log-$it", message = "small")) }

        assertEquals(200, journal.listRecent().size)
        assertEquals("log-204", journal.listRecent().first().id)
        assertEquals("log-5", journal.listRecent().last().id)
        assertEquals(3, journal.listRecent(3).size)
    }

    @Test
    fun `oversized legacy logs are never decoded and remain until explicit clear`() {
        val storage = FakeStorage().apply {
            values[RuntimeLogJournal.LEGACY_KEY] = "untouched legacy data"
            sizes[RuntimeLogJournal.LEGACY_KEY] = 8 * 1024 * 1024
        }
        val journal = RuntimeLogJournal(storage)

        val notice = journal.listRecent().single()
        assertEquals("WARN", notice.level)
        assertTrue(notice.message.contains("原始数据仍保留"))
        assertTrue(storage.readKeys.isEmpty())

        journal.append(RuntimeLogEntry(id = "new", message = "new log"))
        assertEquals("new", journal.listRecent().first().id)
        assertEquals("untouched legacy data", storage.values[RuntimeLogJournal.LEGACY_KEY])
        assertFalse(storage.readKeys.contains(RuntimeLogJournal.LEGACY_KEY))

        journal.clear()
        assertTrue(storage.values.isEmpty())
    }

    @Test
    fun `small release-obfuscated legacy logs migrate only after successful write`() {
        val legacy = """[{"a":"old","b":5678,"c":"ERROR","d":"Test","e":"failed","f":"stack","g":true}]"""
        val storage = FakeStorage().apply {
            values[RuntimeLogJournal.LEGACY_KEY] = legacy
            failWrites = true
        }
        val journal = RuntimeLogJournal(storage)
        val old = journal.listRecent().single()
        assertEquals("old", old.id)
        assertEquals(5678L, old.createdAt)
        assertEquals("failed", old.message)
        assertTrue(old.isCrash)

        try {
            journal.append(RuntimeLogEntry(id = "new", message = "new"))
            fail("failed persistence must not report success")
        } catch (_: IllegalStateException) {
            assertEquals(legacy, storage.values[RuntimeLogJournal.LEGACY_KEY])
            assertFalse(storage.values.containsKey(RuntimeLogJournal.KEY))
        }

        storage.failWrites = false
        journal.append(RuntimeLogEntry(id = "new", message = "new"))
        assertFalse(storage.values.containsKey(RuntimeLogJournal.LEGACY_KEY))
        assertNotNull(storage.values[RuntimeLogJournal.KEY])
        assertEquals(listOf("new", "old"), journal.listRecent().map { it.id })
    }

    @Test
    fun `malformed legacy data is retained and reported without preventing new logs`() {
        val storage = FakeStorage().apply { values[RuntimeLogJournal.LEGACY_KEY] = "[bad-json" }
        val journal = RuntimeLogJournal(storage)
        assertTrue(journal.listRecent().single().message.contains("格式异常"))
        journal.append(RuntimeLogEntry(id = "new"))
        assertEquals("[bad-json", storage.values[RuntimeLogJournal.LEGACY_KEY])
        assertEquals("new", journal.listRecent().first().id)
    }

    @Test
    fun `invalid JSON after the retained records cannot retire the legacy copy`() {
        val valid = (0..200).joinToString(",", "[", "]") { """{"a":"log-$it","e":"ok"}""" }
        for (malformed in listOf(valid + "trailing", valid.dropLast(1) + ",invalid]")) {
            val storage = FakeStorage().apply { values[RuntimeLogJournal.LEGACY_KEY] = malformed }
            val journal = RuntimeLogJournal(storage)
            assertTrue(journal.listRecent().single().message.contains("格式异常"))
            journal.append(RuntimeLogEntry(id = "new"))
            assertEquals(malformed, storage.values[RuntimeLogJournal.LEGACY_KEY])
            assertEquals("new", journal.listRecent().first().id)
        }
    }

    @Test
    fun `unexpected oversized current storage cannot be decoded or silently overwritten`() {
        val storage = FakeStorage().apply {
            values[RuntimeLogJournal.KEY] = "retained"
            sizes[RuntimeLogJournal.KEY] = RuntimeLogLimits.MAX_BYTES + 1
        }
        val journal = RuntimeLogJournal(storage)
        assertTrue(journal.listRecent().single().message.contains("体积过大"))
        try {
            journal.append(RuntimeLogEntry(id = "new"))
            fail("unsafe current data must remain intact")
        } catch (_: IllegalStateException) {
            assertEquals("retained", storage.values[RuntimeLogJournal.KEY])
            assertTrue(storage.readKeys.isEmpty())
        }
    }

    private class FakeStorage : RuntimeLogStorage {
        val values = mutableMapOf<String, String>()
        val sizes = mutableMapOf<String, Int>()
        val readKeys = mutableListOf<String>()
        var failWrites = false
        override fun contains(key: String) = values.containsKey(key)
        override fun size(key: String) = sizes[key] ?: values[key]?.let { RuntimeLogJournal.storageStringByteUpperBound(it) } ?: 0
        override fun read(key: String): String? {
            readKeys += key
            return values[key]
        }
        override fun write(key: String, value: String): Boolean {
            if (failWrites) return false
            values[key] = value
            return true
        }
        override fun remove(key: String) {
            values.remove(key)
            sizes.remove(key)
        }
    }
}
