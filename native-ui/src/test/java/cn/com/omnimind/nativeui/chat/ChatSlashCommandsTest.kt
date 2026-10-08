package cn.com.omnimind.nativeui.chat

import cn.com.omnimind.nativeui.chat.ChatSlashSubmit.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports agent_slash_commands_test and the panel card rules (5d-1c). */
class ChatSlashCommandsTest {
    private val agent = ChatSlashContext(
        agent = true,
        advertisedCommands = listOf(ChatAdvertisedCommand("compact", "压缩上下文"), ChatAdvertisedCommand("/review")),
        models = listOf("deepseek-chat", "deepseek-reasoner", "gpt-5"),
        selectedModel = "deepseek-reasoner",
        planMode = "plan",
    )
    private val chat = ChatSlashContext(agent = false, selectedEffort = "high")

    private fun ChatSlashContext.submit(text: String) = resolveSubmit(text, initPrompt = "INIT")

    @Test
    fun `advertised commands are ordinary prompts, unknown slash text is refused`() {
        assertEquals(ChatSlashSubmit.Send("/compact keep tools"), agent.submit("/compact keep tools"))
        assertEquals(ChatSlashSubmit.Send("/review"), agent.submit("/REVIEW".lowercase()))
        for (command in listOf("/pause", "/resume", "/chat", "/normal")) {
            assertEquals(ChatSlashSubmit.Notice(Reason.Unsupported), agent.submit(command))
        }
        assertEquals(ChatSlashSubmit.Notice(Reason.ReviewUnavailable), agent.copy(advertisedCommands = emptyList()).submit("/review"))
        assertEquals(ChatSlashSubmit.Send("你好"), agent.submit(" 你好 "))
    }

    @Test
    fun `model, init and plan intents`() {
        assertEquals(ChatSlashSubmit.FillText("/model "), agent.submit("/model"))
        assertEquals(ChatSlashSubmit.SelectModel("gpt-5"), agent.submit("/model  gpt-5 "))
        assertEquals(ChatSlashSubmit.Send("INIT", display = "/init"), agent.submit("/init"))
        assertEquals(ChatSlashSubmit.TogglePlan, agent.submit("/plan"))
        assertEquals(ChatSlashSubmit.StartPlan("inspect the diff"), agent.submit("/plan inspect the diff"))
        assertEquals(ChatSlashSubmit.Notice(Reason.PlanUnavailable), agent.copy(planMode = null).submit("/plan"))
    }

    @Test
    fun `configuration changes are refused while a turn runs`() {
        // 5d-1c fix: the Flutter /model path reconnected the shared ACP
        // runtime with no running-turn check, ending every live turn.
        val busy = agent.copy(configLocked = true)
        assertEquals(ChatSlashSubmit.Notice(Reason.Busy), busy.submit("/model gpt-5"))
        assertEquals(ChatSlashSubmit.Notice(Reason.Busy), busy.submit("/plan"))
        assertEquals(ChatSlashSubmit.Notice(Reason.Busy), busy.submit("/plan go"))
        // Opening the picker and sending prompts stay available.
        assertEquals(ChatSlashSubmit.FillText("/model "), busy.submit("/model"))
    }

    @Test
    fun `pure chat handles effort and refuses the Flutter-only commands`() {
        assertEquals(ChatSlashSubmit.SetEffort("none"), chat.submit("/effort no"))
        assertEquals(ChatSlashSubmit.SetEffort("medium"), chat.submit("/effort MED"))
        assertEquals(ChatSlashSubmit.Notice(Reason.InvalidEffort), chat.submit("/effort ultra"))
        assertEquals(ChatSlashSubmit.FillText("/effort "), chat.submit("/effort"))
        for (command in listOf("/record", "/openclaw", "手动录制")) {
            assertEquals(ChatSlashSubmit.Notice(Reason.OpenInChat), chat.submit(command))
        }
        assertEquals(ChatSlashSubmit.Compact, chat.submit("/compact"))
        // Another conversation's reply does not block it; the page checks its own turn.
        assertEquals(ChatSlashSubmit.Compact, chat.copy(configLocked = true).submit("/compact"))
        // Any other slash text is an ordinary chat message.
        assertEquals(ChatSlashSubmit.Send("/what"), chat.submit("/what"))
    }

    @Test
    fun `root panel lists built-ins then advertised commands without duplicates`() {
        val titles = agent.entries("/").map { it.title }
        assertEquals(listOf("/model", "/review", "/init", "/plan", "/compact"), titles)
        assertEquals(listOf("/plan"), agent.entries("/p").map { it.title })
        assertTrue(agent.entries("hello").isEmpty())
        val compact = agent.entries("/c").single()
        assertEquals("/compact ", compact.fillText)
        assertEquals("压缩上下文", compact.detail)
        assertEquals(listOf("/model", "/init"), agent.copy(planMode = null, advertisedCommands = emptyList()).entries("/").map { it.title })
    }

    @Test
    fun `model route filters by substring and puts the selected model first`() {
        assertEquals(listOf("deepseek-reasoner", "deepseek-chat", "gpt-5"), agent.entries("/model").map { it.title })
        val filtered = agent.entries("/model chat")
        assertEquals(listOf("deepseek-chat"), filtered.map { it.title })
        assertEquals("/model deepseek-chat", filtered.single().submitText)
        assertEquals(ChatSlashEntry.Kind.ModelsEmpty, agent.entries("/model zzz").single().kind)
    }

    @Test
    fun `effort route marks the stored effort`() {
        val rows = chat.entries("/effort")
        assertEquals(CHAT_EFFORT_OPTIONS, rows.map { it.title })
        assertEquals("high", rows.single { it.selected }.title)
        assertEquals(listOf("/record", "/compact", "/effort"), chat.entries("/").map { it.title })
        assertEquals(listOf("/compact"), chat.entries("/c").map { it.title })
        assertNull(normalizeChatEffort("ultra"))
    }
}
