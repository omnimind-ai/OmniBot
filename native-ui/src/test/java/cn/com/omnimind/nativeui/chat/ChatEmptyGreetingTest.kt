package cn.com.omnimind.nativeui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The greeting's prompt and keyword rules, against chat_empty_greeting.dart (5e-4). */
class ChatEmptyGreetingTest {
    private val prompts = listOf(
        ChatQuickPrompt("a", "整理", "整理文件"),
        ChatQuickPrompt("b", "", "无标题"),
        ChatQuickPrompt("c", "写作", "帮我写"),
        ChatQuickPrompt("d", "搜索", "帮我查"),
    )

    @Test
    fun `pinned prompts win, in pinned order, at most two`() {
        assertEquals(listOf("d", "a"), selectGreetingPrompts(prompts, listOf("d", " a ", "c")).map { it.id })
        // A pinned id without a titled prompt is skipped, not replaced.
        assertEquals(listOf("c"), selectGreetingPrompts(prompts, listOf("b", "c")).map { it.id })
    }

    @Test
    fun `untitled prompts are never offered and a larger set yields a random pair`() {
        assertEquals(listOf("a", "c"), selectGreetingPrompts(prompts.take(3), emptyList()).map { it.id })
        val pair = selectGreetingPrompts(prompts, emptyList(), Random(7))
        assertEquals(2, pair.size)
        assertTrue(pair.none { it.id == "b" })
    }

    @Test
    fun `the keyword always changes`() {
        val random = Random(1)
        var current = 0
        repeat(50) {
            val next = nextGreetingWord(current, 8, random)
            assertNotEquals(current, next)
            assertTrue(next in 0 until 8)
            current = next
        }
        assertEquals(0, nextGreetingWord(0, 1))
    }
}
