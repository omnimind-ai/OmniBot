package cn.com.omnimind.bot.ui.channel

internal object LocalDataDeletionPolicy {
    private val CONFIRMATION_PHRASES = setOf("删除本机数据", "DELETE LOCAL DATA")

    fun isConfirmed(value: String?): Boolean = value in CONFIRMATION_PHRASES
}
