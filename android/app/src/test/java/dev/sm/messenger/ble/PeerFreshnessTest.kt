package dev.sm.messenger.ble

import org.junit.Assert.*
import org.junit.Test

class PeerFreshnessTest {
    @Test fun expiredPeerIsNotNearbyEvenWithoutPruningScreen() {
        assertTrue(freshPeer(10, 10, 10_000))
        assertTrue(freshPeer(10, 10_009, 10_000))
        assertFalse(freshPeer(10, 10_010, 10_000))
        assertFalse(freshPeer(10, 99_000, 10_000))
    }
    @Test fun invalidTimesDoNotMakeAContactNearby() {
        assertFalse(freshPeer(11, 10, 10_000))
        assertFalse(freshPeer(-1, Long.MAX_VALUE, 10_000))
        assertFalse(freshPeer(0, 0, 0))
        assertFalse(freshPeer(0, Long.MAX_VALUE, 10_000))
    }
}
