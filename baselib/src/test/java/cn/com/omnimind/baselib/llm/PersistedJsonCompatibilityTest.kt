package cn.com.omnimind.baselib.llm

import cn.com.omnimind.baselib.util.RuntimeLogEntry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistedJsonCompatibilityTest {
    private val gson = Gson()

    @Test
    fun providerProfiles_ignoreUnknownEntriesAndKeepRecognizedProfiles() {
        val raw =
            """
                [
                  {"unexpected":"value"},
                  {
                    "a":"custom-provider",
                    "b":"Custom Provider",
                    "c":"https://api.example.com/v1",
                    "d":"sk-test"
                  }
                ]
            """.trimIndent()

        val decoded = ModelProviderConfigStore.decodeProfilesJson(raw)

        assertEquals(1, decoded.size)
        assertEquals("custom-provider", decoded.single().id)
        assertEquals("Custom Provider", decoded.single().name)
        assertEquals("https://api.example.com/v1", decoded.single().baseUrl)
        assertEquals("sk-test", decoded.single().apiKey)
    }

    @Test
    fun providerProfiles_readReleaseObfuscatedFieldsAndWriteCanonicalFields() {
        val releaseRaw =
            """
                [
                  {
                    "a": "custom-provider",
                    "b": "Custom Provider",
                    "c": "https://api.example.com/v1",
                    "d": "sk-test",
                    "e": {
                      "HTTP-Referer": "https://example.com",
                      "X-Title": "OpenOmniBot"
                    },
                    "f": "custom",
                    "g": false,
                    "h": true,
                    "i": "openai_compatible",
                    "j": "responses"
                  }
                ]
            """.trimIndent()
        val profiles = ModelProviderConfigStore.decodeProfilesJson(releaseRaw)

        assertEquals(1, profiles.size)
        val profile = profiles.single()
        assertEquals("custom-provider", profile.id)
        assertEquals("Custom Provider", profile.name)
        assertEquals("https://api.example.com/v1", profile.baseUrl)
        assertEquals("sk-test", profile.apiKey)
        assertEquals(
            linkedMapOf(
                "HTTP-Referer" to "https://example.com",
                "X-Title" to "OpenOmniBot"
            ),
            profile.customHeaders
        )
        assertEquals("custom", profile.sourceType)
        assertFalse(profile.readOnly)
        assertTrue(profile.ready)
        assertNull(profile.statusText)
        assertEquals("openai_compatible", profile.protocolType)
        assertEquals("responses", profile.wireApi)
        val encoded = ModelProviderConfigStore.encodeProfilesJson(profiles)
        val encodedProfile = JsonParser.parseString(encoded)
            .asJsonArray
            .single()
            .asJsonObject
        assertTrue(encodedProfile.has("id"))
        assertTrue(encodedProfile.has("customHeaders"))
        assertTrue(encodedProfile.has("protocolType"))
        assertFalse(encodedProfile.has("a"))
        assertFalse(encodedProfile.has("e"))
        assertFalse(encodedProfile.has("i"))
    }

    @Test
    fun sceneBindings_readReleaseObfuscatedFieldsAndWriteCanonicalFields() {
        val type = object : TypeToken<Map<String, SceneModelBindingEntry>>() {}.type
        val bindings: Map<String, SceneModelBindingEntry> = gson.fromJson(
            """
                {
                  "scene.dispatch.model": {
                    "a": "scene.dispatch.model",
                    "b": "custom-provider",
                    "c": "gpt-test"
                  }
                }
            """.trimIndent(),
            type
        )

        val binding = bindings.getValue("scene.dispatch.model")
        assertEquals("scene.dispatch.model", binding.sceneId)
        assertEquals("custom-provider", binding.providerProfileId)
        assertEquals("gpt-test", binding.modelId)

        val encoded = JsonParser.parseString(gson.toJson(binding)).asJsonObject
        assertTrue(encoded.has("sceneId"))
        assertTrue(encoded.has("providerProfileId"))
        assertTrue(encoded.has("modelId"))
        assertFalse(encoded.has("a"))
    }

    @Test
    fun aiRequestLogs_readReleaseObfuscatedFields() {
        val type = object : TypeToken<List<AiRequestLogEntry>>() {}.type
        val entries: List<AiRequestLogEntry> = gson.fromJson(
            """
                [
                  {
                    "a": "request-1",
                    "b": 1234,
                    "c": "Chat",
                    "d": "gpt-test",
                    "e": "openai_compatible",
                    "f": "https://api.example.com/v1/chat/completions",
                    "g": "POST",
                    "h": true,
                    "i": 200,
                    "j": true,
                    "k": "{\"input\":\"hello\"}",
                    "l": "{\"output\":\"world\"}",
                    "m": null
                  }
                ]
            """.trimIndent(),
            type
        )

        val entry = entries.single()
        assertEquals("request-1", entry.id)
        assertEquals(1234L, entry.createdAt)
        assertEquals("Chat", entry.label)
        assertEquals("gpt-test", entry.model)
        assertEquals("openai_compatible", entry.protocolType)
        assertEquals("https://api.example.com/v1/chat/completions", entry.url)
        assertEquals("POST", entry.method)
        assertTrue(entry.stream)
        assertEquals(200, entry.statusCode)
        assertTrue(entry.success)
        assertEquals("""{"input":"hello"}""", entry.requestJson)
        assertEquals("""{"output":"world"}""", entry.responseJson)
        assertNull(entry.errorMessage)
    }

    @Test
    fun runtimeLogs_readReleaseObfuscatedFields() {
        val type = object : TypeToken<List<RuntimeLogEntry>>() {}.type
        val entries: List<RuntimeLogEntry> = gson.fromJson(
            """
                [
                  {
                    "a": "log-1",
                    "b": 5678,
                    "c": "ERROR",
                    "d": "Test",
                    "e": "failed",
                    "f": "stack",
                    "g": true
                  }
                ]
            """.trimIndent(),
            type
        )

        val entry = entries.single()
        assertEquals("log-1", entry.id)
        assertEquals(5678L, entry.createdAt)
        assertEquals("ERROR", entry.level)
        assertEquals("Test", entry.tag)
        assertEquals("failed", entry.message)
        assertEquals("stack", entry.stackTrace)
        assertTrue(entry.isCrash)
    }
}
