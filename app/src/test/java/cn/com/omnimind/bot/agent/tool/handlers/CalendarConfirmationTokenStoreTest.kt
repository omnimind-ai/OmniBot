package cn.com.omnimind.bot.agent.tool.handlers

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarConfirmationTokenStoreTest {
    @Test
    fun `model supplied confirmed flag cannot bypass local grant issuance`() {
        val store = LocalUserConfirmationTokenStore()
        val args = buildJsonObject {
            put("eventId", "event-1")
            put("confirmed", true)
        }

        assertTrue(
            CalendarMutationConfirmationPolicy.requiresExplicitUserConsent(
                "calendar_event_delete",
            ),
        )
        assertFalse(store.consume("model-invented-token", "calendar_event_delete", args))
    }

    @Test
    fun `grant can be consumed only once`() {
        val store = LocalUserConfirmationTokenStore()
        val args = buildJsonObject { put("eventId", "event-1") }
        val token = store.issueFromTrustedUserAction("calendar_event_delete", args)

        assertTrue(store.consume(token, "calendar_event_delete", args))
        assertFalse(store.consume(token, "calendar_event_delete", args))
    }

    @Test
    fun `argument change rejects and consumes grant`() {
        val store = LocalUserConfirmationTokenStore()
        val approvedArgs = buildJsonObject {
            put("eventId", "event-1")
            put("title", "Approved title")
        }
        val changedArgs = buildJsonObject {
            put("eventId", "event-1")
            put("title", "Changed title")
        }
        val token = store.issueFromTrustedUserAction("calendar_event_update", approvedArgs)

        assertFalse(store.consume(token, "calendar_event_update", changedArgs))
        assertFalse(store.consume(token, "calendar_event_update", approvedArgs))
    }

    @Test
    fun `expired grant fails closed`() {
        var now = 1_000L
        val store = LocalUserConfirmationTokenStore(
            ttlMillis = 50L,
            nowMillis = { now },
        )
        val args = buildJsonObject { put("writableOnly", true) }
        val token = store.issueFromTrustedUserAction("calendar_list", args)

        now += 50L

        assertFalse(store.consume(token, "calendar_list", args))
    }

    @Test
    fun `tool name is part of grant binding`() {
        val store = LocalUserConfirmationTokenStore()
        val args = buildJsonObject { put("calendarId", "calendar-1") }
        val token = store.issueFromTrustedUserAction("calendar_list", args)

        assertFalse(store.consume(token, "calendar_event_list", args))
    }
}
