package dev.sm.messenger.ble

import org.junit.Assert.*
import org.junit.Test

class PeerLeaseTest {
    @Test fun provisionalSlotExpiresEvenWithRejectedTraffic() {
        val lease = PeerLease(0)
        repeat(100) { assertEquals(0L, lease.touched()) }
        assertFalse(lease.expired(4999, 5000)); assertTrue(lease.expired(5000, 5000))
    }
    @Test fun onlyAcceptedAeadAndProtocolRefreshesLease() {
        val lease = PeerLease(0); lease.accepted(4000)
        assertFalse(lease.expired(5000, 5000)); assertTrue(lease.expired(9000, 5000))
    }
    @Test fun backwardClockExpiresRatherThanPins() {
        val lease = PeerLease(100); assertTrue(lease.expired(99, 5000))
    }
}
