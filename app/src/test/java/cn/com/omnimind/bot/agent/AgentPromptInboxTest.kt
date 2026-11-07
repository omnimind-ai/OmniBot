package cn.com.omnimind.bot.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AgentPromptInboxTest {
    @Test fun `input is ordered and duplicate request emits only one user item`() {
        val inbox = AgentPromptInbox("turn-1")
        val echoes = mutableListOf<String>()
        val first = AgentPromptInbox.Entry("one", "first correction")
        val second = AgentPromptInbox.Entry("two", "second correction")
        assertEquals(AgentPromptInbox.Admission.ACCEPTED, inbox.offer(first, "turn-1") { echoes += it.text })
        assertEquals(AgentPromptInbox.Admission.DUPLICATE, inbox.offer(first, "turn-1") { fail("duplicate echo") })
        assertEquals(AgentPromptInbox.Admission.ACCEPTED, inbox.offer(second, "turn-1") { echoes += it.text })
        assertEquals(listOf(first, second), inbox.drain())
        assertEquals(listOf(first.text, second.text), echoes)
        assertTrue(inbox.drain().isEmpty())
        assertEquals(listOf(first, second), inbox.acceptedEntries())
    }

    @Test fun `finished prompt cannot consume input intended for a later turn`() {
        val inbox = AgentPromptInbox("old")
        val entry = AgentPromptInbox.Entry("request", "correct this")
        assertEquals(AgentPromptInbox.Admission.PROMPT_REQUIRED, inbox.offer(entry, "new"))
        assertTrue(inbox.drainOrSeal().isEmpty())
        assertEquals(AgentPromptInbox.Admission.PROMPT_REQUIRED, inbox.offer(entry, "old"))
        assertTrue(inbox.acceptedEntries().isEmpty())
    }

    @Test fun `final model boundary continues when an input was admitted`() {
        val inbox = AgentPromptInbox("turn")
        val first = AgentPromptInbox.Entry("first", "more work")
        inbox.offer(first, "turn")
        assertEquals(listOf(first), inbox.drainOrSeal())
        assertEquals(AgentPromptInbox.Admission.ACCEPTED,
            inbox.offer(AgentPromptInbox.Entry("second", "and this"), "turn"))
        assertEquals(1, inbox.drainOrSeal().size)
        assertTrue(inbox.drainOrSeal().isEmpty())
        assertEquals(AgentPromptInbox.Admission.PROMPT_REQUIRED,
            inbox.offer(AgentPromptInbox.Entry("third", "next"), "turn"))
    }

    @Test fun `cancel preserves accepted history while dropping unconsumed execution input`() {
        val inbox = AgentPromptInbox("turn")
        val entry = AgentPromptInbox.Entry("request", "remember my correction")
        inbox.offer(entry, "turn")
        inbox.close()
        assertTrue(inbox.drain().isEmpty())
        assertEquals(listOf(entry), inbox.acceptedEntries())
        assertEquals(AgentPromptInbox.Admission.DUPLICATE, inbox.offer(entry, "turn"))
        assertEquals(AgentPromptInbox.Admission.PROMPT_REQUIRED,
            inbox.offer(AgentPromptInbox.Entry("new", "new work"), "turn"))
    }

    @Test fun `conflicting retry cannot replace committed input`() {
        val inbox = AgentPromptInbox("turn")
        inbox.offer(AgentPromptInbox.Entry("request", "original"), "turn")
        assertThrows(IllegalArgumentException::class.java) {
            inbox.offer(AgentPromptInbox.Entry("request", "changed"), "turn")
        }
        assertEquals("original", inbox.drain().single().text)
    }

    @Test fun `failed history publication does not acknowledge or consume input`() {
        val inbox = AgentPromptInbox("turn") { error("Prompt channel closed") }
        assertThrows(IllegalStateException::class.java) {
            inbox.offer(AgentPromptInbox.Entry("request", "correction"), "turn")
        }
        assertTrue(inbox.acceptedEntries().isEmpty())
        assertTrue(inbox.drain().isEmpty())
    }

    @Test fun `steering and final response race never acknowledges a lost input`() {
        val workers = Executors.newFixedThreadPool(2)
        try {
            repeat(200) { iteration ->
                val inbox = AgentPromptInbox("turn-$iteration")
                val entry = AgentPromptInbox.Entry("request", "follow up")
                val start = CountDownLatch(1)
                val offered = workers.submit<AgentPromptInbox.Admission> {
                    start.await()
                    inbox.offer(entry, "turn-$iteration")
                }
                val terminal = workers.submit<List<AgentPromptInbox.Entry>> {
                    start.await()
                    inbox.drainOrSeal()
                }
                start.countDown()
                val admission = offered.get(2, TimeUnit.SECONDS)
                val consumed = terminal.get(2, TimeUnit.SECONDS)
                if (admission == AgentPromptInbox.Admission.ACCEPTED) {
                    assertEquals(listOf(entry), consumed)
                } else {
                    assertEquals(AgentPromptInbox.Admission.PROMPT_REQUIRED, admission)
                    assertTrue(consumed.isEmpty())
                    assertTrue(inbox.acceptedEntries().isEmpty())
                }
            }
        } finally {
            workers.shutdownNow()
        }
    }
}
