package dev.sm.messenger.relay

import org.junit.Assert.*
import org.junit.Test

class RelayFrameSenderTest {
    @Test fun nodeReceivesDueRetryAfterBluetoothDidNotDeliver() {
        val cached = byteArrayOf(4, 5, 6)
        val writes = ArrayList<ByteArray>()
        val marks = ArrayList<ByteArray>()
        val sender = RelayFrameSender({ writes.add(it) }, { marks.add(it) })
        sender.deliver(listOf(RelayOutgoing(cached, byteArrayOf(7))))
        assertArrayEquals(cached, writes.single())
        assertArrayEquals(byteArrayOf(7), marks.single())
    }

    @Test fun acknowledgementsAreSentButDoNotMarkUserMessagesOnNode() {
        var sent = 0; var marked = 0
        RelayFrameSender({ sent++ }, { marked++ }).deliver(listOf(RelayOutgoing(byteArrayOf(1), null)))
        assertEquals(1, sent); assertEquals(0, marked)
    }

    @Test fun failedSendDoesNotPretendAcceptance() {
        var marked = false
        val sender = RelayFrameSender({ throw RelayException(RelayFailure.Timeout, "test") }, { marked = true })
        try {
            sender.deliver(listOf(RelayOutgoing(byteArrayOf(1), byteArrayOf(2))))
            fail("ошибка отправки потеряна")
        } catch (_: RelayException) {
            assertFalse(marked)
        }
    }

    @Test fun emptyDueListDoesNotBypassCommonRetrySchedule() {
        var sent = false
        RelayFrameSender({ sent = true }, { fail("не отправлено") }).deliver(emptyList())
        assertFalse(sent)
    }
}
