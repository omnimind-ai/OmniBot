package cn.com.omnimind.bot.activity

import cn.com.omnimind.bot.util.TaskCompletionNavigator
import cn.com.omnimind.nativeui.LegacyDestination
import cn.com.omnimind.nativeui.conversationModeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeEntryRoutesTest {
    /** The producers' own route builder round-trips into a native destination. */
    @Test fun completionNotificationRoutesOpenTheConversation() {
        assertEquals(
            LegacyDestination.OpenConversation(42, "agent"),
            NativeEntryRoutes.destinationFor(TaskCompletionNavigator.buildChatRoute(42, "normal")),
        )
        assertEquals(
            LegacyDestination.OpenConversation(7, "subagent"),
            NativeEntryRoutes.destinationFor(TaskCompletionNavigator.buildChatRoute(7, "subagent")),
        )
        assertEquals(
            LegacyDestination.OpenConversation(9, "chat_only"),
            NativeEntryRoutes.destinationFor("/home/chat?mode=chat_only&conversationId=9"),
        )
    }

    @Test fun otherRoutesStayWithFlutter() {
        assertNull(NativeEntryRoutes.destinationFor(TaskCompletionNavigator.buildChatRoute(null, "agent")))
        assertNull(NativeEntryRoutes.destinationFor("/memory/memory_center_page"))
        assertNull(NativeEntryRoutes.destinationFor("/home/chat?conversationId=abc"))
        assertNull(NativeEntryRoutes.destinationFor("/home/chat_history?conversationId=3"))
        assertNull(NativeEntryRoutes.destinationFor(null))
    }

    /** Notifications normalize `normal` to `agent`; stored rows may use either. */
    @Test fun modeKeysMatchAcrossSpellings() {
        assertEquals(conversationModeKey("normal"), conversationModeKey("agent"))
        assertEquals(conversationModeKey("chat"), conversationModeKey("chat_only"))
        assertEquals("subagent", conversationModeKey("SubAgent"))
    }
}
