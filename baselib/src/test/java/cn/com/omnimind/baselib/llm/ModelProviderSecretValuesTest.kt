package cn.com.omnimind.baselib.llm

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProviderSecretValuesTest {
    @Test
    fun identicalRawValuesSkipEditorAndCommit() {
        val preferences = RecordingSecretPreferences(
            mapOf(API_KEY to "existing", HEADERS to "{\"X-Test\":\"value\"}"),
        )

        assertFalse(write(preferences, "existing", "{\"X-Test\":\"value\"}"))

        assertEquals(0, preferences.editorCount)
        assertEquals(0, preferences.commitCount)
    }

    @Test
    fun absentEmptyCredentialsSkipEditorAndCommit() {
        val preferences = RecordingSecretPreferences()

        assertFalse(write(preferences, null, null))

        assertEquals(0, preferences.editorCount)
        assertEquals(0, preferences.commitCount)
    }

    @Test
    fun changedCredentialsAreCommittedTogether() {
        val preferences = RecordingSecretPreferences(
            mapOf(API_KEY to "old", HEADERS to "{\"X-Old\":\"value\"}"),
        )

        assertTrue(write(preferences, "new", "{\"X-New\":\"value\"}"))

        assertEquals(
            mapOf(API_KEY to "new", HEADERS to "{\"X-New\":\"value\"}"),
            preferences.values,
        )
        assertEquals(1, preferences.editorCount)
        assertEquals(1, preferences.commitCount)
    }

    @Test
    fun emptyTargetRemovesPresentEmptyAndNullValues() {
        val preferences = RecordingSecretPreferences(mapOf(API_KEY to "", HEADERS to null))

        assertTrue(write(preferences, null, null))

        assertTrue(preferences.values.isEmpty())
        assertEquals(1, preferences.commitCount)
    }

    @Test
    fun malformedHeadersAreRemovedEvenWhenTheyWouldDecodeAsEmpty() {
        val preferences = RecordingSecretPreferences(mapOf(HEADERS to "{broken"))

        assertTrue(write(preferences, null, null))

        assertFalse(preferences.values.containsKey(HEADERS))
        assertEquals(1, preferences.commitCount)
    }

    @Test
    fun semanticallyEqualHeadersAreRewrittenWhenSerializationDiffers() {
        val preferences = RecordingSecretPreferences(
            mapOf(HEADERS to "{ \"X-Test\" : \"value\" }"),
        )

        assertTrue(write(preferences, null, "{\"X-Test\":\"value\"}"))

        assertEquals("{\"X-Test\":\"value\"}", preferences.values[HEADERS])
        assertEquals(1, preferences.commitCount)
    }

    @Test
    fun failedCommitIsNotReportedAsSuccessfulWrite() {
        val preferences = RecordingSecretPreferences(mapOf(API_KEY to "existing"))
        preferences.commitSucceeds = false

        assertThrows(IllegalStateException::class.java) {
            write(preferences, "new", null)
        }

        assertEquals("existing", preferences.values[API_KEY])
        assertEquals(1, preferences.commitCount)
    }

    @Test
    fun comparisonFailurePropagatesToFailClosedOwner() {
        val preferences = RecordingSecretPreferences(mapOf(API_KEY to "existing"))
        preferences.readFailure = IllegalStateException("simulated encrypted read failure")

        assertThrows(IllegalStateException::class.java) {
            write(preferences, "existing", null)
        }

        assertEquals(0, preferences.editorCount)
        assertEquals(0, preferences.commitCount)
    }

    private fun write(
        preferences: RecordingSecretPreferences,
        apiKey: String?,
        headersJson: String?,
    ): Boolean = writeProviderSecretValuesIfChanged(
        preferences.preferences,
        API_KEY,
        HEADERS,
        apiKey,
        headersJson,
    )

    private companion object {
        const val API_KEY = "api_key"
        const val HEADERS = "headers"
    }
}

/** Only the preference operations at the encrypted write boundary are supported. */
private class RecordingSecretPreferences(initial: Map<String, String?> = emptyMap()) {
    val values = initial.toMutableMap()
    var editorCount = 0
    var commitCount = 0
    var commitSucceeds = true
    var readFailure: RuntimeException? = null

    val preferences: SharedPreferences = proxy(SharedPreferences::class.java) { name, args ->
        when (name) {
            "contains" -> {
                readFailure?.let { throw it }
                values.containsKey(args[0] as String)
            }
            "getString" -> {
                readFailure?.let { throw it }
                values[args[0] as String] ?: args[1]
            }
            "edit" -> editor()
            else -> error("Unexpected preference operation: $name")
        }
    }

    private fun editor(): SharedPreferences.Editor {
        editorCount++
        val updates = mutableMapOf<String, String>()
        val removals = mutableSetOf<String>()
        lateinit var editor: SharedPreferences.Editor
        editor = proxy(SharedPreferences.Editor::class.java) { name, args ->
            when (name) {
                "putString" -> {
                    updates[args[0] as String] = args[1] as String
                    editor
                }
                "remove" -> {
                    removals.add(args[0] as String)
                    editor
                }
                "commit" -> {
                    commitCount++
                    if (commitSucceeds) {
                        removals.forEach(values::remove)
                        values.putAll(updates)
                    }
                    commitSucceeds
                }
                else -> error("Unexpected editor operation: $name")
            }
        }
        return editor
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            call(method.name, args ?: emptyArray())
        } as T
}
