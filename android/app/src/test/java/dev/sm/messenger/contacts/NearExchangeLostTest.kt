package dev.sm.messenger.contacts
import org.junit.Assert.*
import org.junit.Test
class NearExchangeLostTest {
    @Test fun incomingRequestAndStartedSessionCountWithoutTarget() {
        assertTrue(nearExchangeLost("peer", null, "peer", null, emptySet()))
        assertTrue(nearExchangeLost("peer", null, null, null, setOf("peer")))
        assertTrue(nearExchangeLost("peer", null, null, "peer", emptySet()))
        assertFalse(nearExchangeLost("other", null, "peer", "peer", setOf("peer")))
    }
}
