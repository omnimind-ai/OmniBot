package cn.com.omnimind.bot.agent.tool.handlers

import java.io.Reader
import java.io.StringReader
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileContentSearchTest {
    @Test fun `first excerpt does not require reading the rest of a huge line`() = runBlocking {
        val reader = object : Reader() {
            var count = 0
            override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                check(count++ < 2) { "Read past sufficient match context" }
                buffer.fill('x', offset, offset + length)
                if (count == 1) "needle".toCharArray().copyInto(buffer, offset + 80)
                return length
            }
            override fun close() {}
        }
        assertEquals("x".repeat(40) + "needle" + "x".repeat(120), findFileContentSnippet(reader,"needle",true))
    }
    @Test fun `stop is observed between chunks before a newline exists`() = runBlocking {
        val task = async {
            val owner = currentCoroutineContext()
            val reader = object : Reader() {
                var reads = 0
                override fun read(buffer: CharArray, offset: Int, length: Int): Int {
                    check(++reads <= 2) { "Cancelled search kept reading" }
                    buffer.fill('x',offset,offset+length)
                    owner.cancel()
                    return length
                }
                override fun close() {}
            }
            findFileContentSnippet(reader,"absent",true)
        }
        try { task.await(); fail("Cancelled search succeeded") } catch (_: CancellationException) { }
        assertEquals("next", findFileContentSnippet(StringReader("next"), "next", true))
    }
    @Test fun `chunk boundaries line endings case and repeated matches preserve excerpts`() = runBlocking {
        repeat(40) { iteration ->
            val prefix = "x".repeat(8180 + iteration)
            val content = prefix + "Ab图片c" + "z".repeat(150) + "\r\nignored"
            assertEquals("x".repeat(40)+"Ab图片c"+"z".repeat(120), findFileContentSnippet(StringReader(content), "ab图片C", false))
            assertNull(findFileContentSnippet(StringReader(content), "ab图片C", true))
        }
        for (sep in listOf("\n","\r","\r\n")) {
            assertNull(findFileContentSnippet(StringReader("ab${sep}cd"),"abcd",true))
            assertEquals("abc",findFileContentSnippet(StringReader("abc${sep}tail"),"bc",true))
        }
        assertEquals("tail",findFileContentSnippet(StringReader("no\ntail"),"tail",true))
    }
    @Test fun `query longer than a block and short reader chunks are supported`() = runBlocking {
        val needle = "long".repeat(3000)
        val source = StringReader("p".repeat(70)+needle+"s".repeat(130))
        val reader = object : Reader() {
            override fun read(buffer: CharArray, offset: Int, length: Int) = source.read(buffer, offset, minOf(length, 257))
            override fun close() = source.close()
        }
        assertEquals("p".repeat(40)+needle+"s".repeat(120), findFileContentSnippet(reader,needle,true))
        assertNull(findFileContentSnippet(StringReader(""),"absent",true))
        assertNull(findFileContentSnippet(StringReader("a\nb"),"a\nb",true))
    }

}
