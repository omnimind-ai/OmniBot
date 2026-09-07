package cn.com.omnimind.bot.agent

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryTextNormalizationTest {
    @Test fun `index identity and search tokens do not change with device language`() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("tr-TR"), Locale.CHINA)) {
                Locale.setDefault(locale)
                assertEquals("fileid记忆", normalizeMemoryText(" FILE ID 记忆 "))
                assertEquals(listOf("file", "id", "记忆"), tokenizeMemoryText("FILE ID 记忆"))
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun `normalization preserves existing whitespace and punctuation behavior`() {
        assertEquals("path:/work/file.md", normalizeMemoryText("PATH: /work/File.md\n"))
        assertEquals(listOf("work", "file", "md"), tokenizeMemoryText("/work/File.md"))
    }
}
