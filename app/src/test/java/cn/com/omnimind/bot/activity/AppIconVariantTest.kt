package cn.com.omnimind.bot.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppIconVariantTest {
    @Test
    fun `the six storage values are stable and Sparkle is the default`() {
        val variants = AppIconVariant.entries
        assertEquals(listOf("sparkle", "dot", "cursor", "wave", "open", "classic"), variants.map { it.storageValue })
        assertEquals(listOf(AppIconVariant.SPARKLE), variants.filter { it.defaultEnabledInManifest })
        variants.forEach { assertEquals(it, AppIconVariant.fromStorageValue(it.storageValue)) }
        assertEquals(AppIconVariant.WAVE, AppIconVariant.fromStorageValue(" WAVE "))
        assertEquals(AppIconVariant.SPARKLE, AppIconVariant.fromStorageValue(null))
        assertEquals(AppIconVariant.SPARKLE, AppIconVariant.fromStorageValue("unknown"))
        assertNull(AppIconVariant.parseStorageValue("unknown"))
    }
}
