package dev.sm.messenger.chat

import dev.sm.messenger.RouteState
import dev.sm.messenger.routeState
import dev.sm.messenger.contacts.nearExchangeLost
import org.junit.Assert.*
import org.junit.Test

class UxStateTest {
    @Test fun failedWriteRetainsDraft() {
        assertEquals("текст", draftAfterSend("текст", "текст", false))
        assertEquals("", draftAfterSend("текст", "текст", true))
        assertEquals("новый текст", draftAfterSend("новый текст", "текст", true))
    }
    @Test fun historyMergeHasStableKeysAndOrder() {
        fun row(id: Long) = ChatLine(id, false, "$id", 0, 0, MessageState.Received)
        assertEquals(listOf(1L, 2L, 3L), mergeHistory(listOf(row(2), row(1)), listOf(row(2), row(3))).map { it.rowId })
    }
    @Test fun notificationNeedsAllThreePermissions() {
        for (runtime in listOf(false, true)) for (app in listOf(false, true)) for (channel in listOf(false, true)) {
            assertEquals(runtime && app && channel, notificationAccess(runtime, app, channel))
        }
    }
    @Test fun restorationDistinguishesReadFailureFromMissing() {
        assertEquals(RouteState.Loading, routeState(true, false))
        assertEquals(RouteState.ReadError, routeState(false, true))
        assertEquals(RouteState.Missing, routeState(false, false))
    }
    @Test fun failureIsNotWaitingOrDelivery() {
        val row = ChatLine(1, true, "text", 0, 0, MessageState.Failed)
        assertEquals("Не отправлено", deliveryDescription(row))
    }
}
