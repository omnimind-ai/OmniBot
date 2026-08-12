package cn.com.omnimind.bot.ui.channel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDataDeletionPolicyTest {
    @Test
    fun requiresExactConfirmationPhrase() {
        assertTrue(LocalDataDeletionPolicy.isConfirmed("删除本机数据"))
        assertTrue(LocalDataDeletionPolicy.isConfirmed("DELETE LOCAL DATA"))
        assertFalse(LocalDataDeletionPolicy.isConfirmed(null))
        assertFalse(LocalDataDeletionPolicy.isConfirmed(""))
        assertFalse(LocalDataDeletionPolicy.isConfirmed(" 删除本机数据"))
        assertFalse(LocalDataDeletionPolicy.isConfirmed("删除全部数据"))
        assertFalse(LocalDataDeletionPolicy.isConfirmed("delete local data"))
    }
}
