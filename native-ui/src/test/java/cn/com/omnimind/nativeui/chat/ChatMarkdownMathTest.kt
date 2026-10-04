package cn.com.omnimind.nativeui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownMathTest {
    @Test
    fun `inline single-dollar math becomes markwon inline math`() {
        assertEquals("面积 \$\$\\pi r^2\$\$ 平方米", normalizeChatMarkdownMath("面积 \$\\pi r^2\$ 平方米"))
    }

    @Test
    fun `block math, escaped dollars and prices stay intact`() {
        val block = "\$\$\nx = 1\n\$\$"
        assertEquals(block, normalizeChatMarkdownMath(block))
        assertEquals("\\\$5 and \\\$6", normalizeChatMarkdownMath("\\\$5 and \\\$6"))
        assertEquals("cost \$5", normalizeChatMarkdownMath("cost \$5"))
    }

    @Test
    fun `code spans and fences are not rewritten`() {
        assertEquals("run `echo \$HOME\$` now", normalizeChatMarkdownMath("run `echo \$HOME\$` now"))
        val fenced = "```sh\necho \$a\$\n```\nthen \$b\$"
        assertEquals("```sh\necho \$a\$\n```\nthen \$\$b\$\$", normalizeChatMarkdownMath(fenced))
    }
}
