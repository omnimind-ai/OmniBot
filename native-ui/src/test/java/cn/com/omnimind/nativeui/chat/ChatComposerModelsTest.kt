package cn.com.omnimind.nativeui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ports chat_composer_state_machine_test's primary-action cases and the context ring rules. */
class ChatComposerModelsTest {
    @Test
    fun `a running turn always offers cancel, even with a draft`() {
        assertEquals(ChatComposerPrimaryAction.Cancel, chatComposerPrimaryAction(true, "", false))
        assertEquals(ChatComposerPrimaryAction.Cancel, chatComposerPrimaryAction(true, "下一句", true))
    }

    @Test
    fun `text or attachments send, whitespace alone does not`() {
        assertEquals(ChatComposerPrimaryAction.Send, chatComposerPrimaryAction(false, "你好", false))
        assertEquals(ChatComposerPrimaryAction.Send, chatComposerPrimaryAction(false, "", true))
        assertEquals(ChatComposerPrimaryAction.Disabled, chatComposerPrimaryAction(false, "  \n", false))
    }

    @Test
    fun `attachment maps match the Dart payload`() {
        val image = ChatComposerAttachment("a", "s.png", "/tmp/s.png", size = 3L, mimeType = "image/png", isImage = true)
        val expected = mapOf("id" to "a", "name" to "s.png", "path" to "/tmp/s.png", "size" to 3L, "mimeType" to "image/png", "isImage" to true)
        for ((key, value) in expected) assertEquals(key, value, image.toMap()[key])
        assertEquals(expected.keys.toList(), image.toMap().keys.toList())
        val excluded = ChatComposerAttachment("b", "x.zip", "/tmp/x.zip", promptPath = " /w/x.zip ", sendToModel = false)
        assertEquals(
            listOf("id", "name", "path", "isImage", "promptPath", "sendToModel"),
            excluded.toMap().keys.toList(),
        )
        assertEquals("/w/x.zip", excluded.toMap()["promptPath"])
    }

    @Test
    fun `context ring hides without usage and escalates at 85 and 100 percent`() {
        assertNull(contextUsageRing(100, 0, 1))
        assertNull(contextUsageRing(0, 128_000, 0))
        assertEquals(ContextUsageRing(0f, ContextUsageLevel.Normal), contextUsageRing(0, 128_000, 5))
        assertEquals(ContextUsageLevel.Warning, contextUsageRing(108_800, 128_000, 5)!!.level)
        val full = contextUsageRing(200_000, 128_000, 5)!!
        assertEquals(ContextUsageLevel.Full, full.level)
        assertEquals(1f, full.progress)
    }

    @Test
    fun `permission choices round-trip their preference values`() {
        for (choice in ChatComposerPermission.entries) {
            assertEquals(choice, ChatComposerPermission.fromPreferenceValue(choice.preferenceValue))
        }
        assertNull(ChatComposerPermission.fromPreferenceValue("agent"))
    }
}
