package cn.com.omnimind.bot.agent.runtime

import com.agentclientprotocol.model.ContentBlock
import com.agentclientprotocol.model.SessionUpdate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class XiaowanAcpConnectionTest {

    @Test
    fun `reasoning rounds preserve starts and reuse one ACP thought message`() = runBlocking {
        val updates = mutableListOf<SessionUpdate>()
        val bridge = XiaowanAcpEventBridge { updates += it }

        bridge.onThinkingStart()
        bridge.onThinkingUpdate("先分析")
        bridge.onThinkingStart()
        bridge.onThinkingUpdate("再调用工具")

        val thoughtUpdates = updates.filterIsInstance<SessionUpdate.AgentThoughtChunk>()
        assertEquals(4, thoughtUpdates.size)
        assertEquals(1, thoughtUpdates.map { it.messageId }.distinct().size)

        val contentUpdates = thoughtUpdates.filter {
            (it.content as ContentBlock.Text).text.isNotEmpty()
        }
        assertEquals(2, contentUpdates.size)
        assertNotEquals(contentUpdates[0].content, contentUpdates[1].content)
    }
}
