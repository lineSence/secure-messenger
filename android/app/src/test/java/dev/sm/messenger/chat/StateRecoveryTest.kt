package dev.sm.messenger.chat

import org.junit.Assert.*
import org.junit.Test

class StateRecoveryTest {
    @Test fun fullReplayRetiresOnlyDurablyAdmittedEntriesAndRetriesWhenQuotaFreed() {
        val journal = mutableListOf(1, 2, 3)
        val snapshot = mutableSetOf(1)
        var free = 1
        fun replay(save: Boolean) {
            val included = JournalReplay.included(journal) { e ->
                e in snapshot || if (free > 0) { free--; snapshot.add(e); true } else false
            }
            JournalReplay.retire(included, { save }) { journal.remove(it) }
        }
        replay(true)
        assertEquals(listOf(3), journal)
        snapshot.remove(1); free++
        replay(true)
        assertTrue(journal.isEmpty())
        assertTrue(3 in snapshot)
    }

    @Test fun failedSnapshotKeepsJournalIncludingAlreadyAdmittedEntry() {
        val journal = mutableListOf(1, 2)
        val included = JournalReplay.included(journal) { true }
        JournalReplay.retire(included, { false }) { journal.remove(it) }
        assertEquals(listOf(1, 2), journal)
    }

    @Test fun relayFailureDoesNotConsumeBluetoothOrPairRetry() {
        val retry = TransportRetry()
        assertTrue(retry.eligible("relay", "box/id", 0uL))
        retry.attempted("relay", "box/id", 0uL)
        assertFalse(retry.eligible("relay", "box/id", 1uL))
        assertTrue(retry.eligible("ble", "box/id", 1uL))
        assertTrue(retry.eligible("pair", "box/id", 1uL))
        retry.attempted("ble", "box/id", 1uL)
        assertFalse(retry.eligible("ble", "box/id", 60_000uL))
        assertTrue(retry.eligible("ble", "box/id", 60_001uL))
    }

    @Test fun retryIsBoundedAndQuotaCanBeFreed() {
        val retry = TransportRetry(1)
        retry.attempted("relay", "a/id", 0uL)
        assertFalse(retry.eligible("relay", "b/id", 0uL))
        retry.retain(setOf("b/id"))
        assertTrue(retry.eligible("relay", "b/id", 0uL))
    }

    @Test fun reconnectResetsOnlyItsTransportAndPeerWithoutBypassingRetryFloor() {
        val retry = TransportRetry()
        repeat(2) {
            retry.attempted("ble", "a/id", 0uL)
            retry.attempted("ble", "b/id", 0uL)
            retry.attempted("relay", "a/id", 0uL)
        }
        retry.reset("ble", "a/", 1uL)
        assertFalse(retry.eligible("ble", "a/id", 1uL))
        assertTrue(retry.eligible("ble", "a/id", 60_000uL))
        assertFalse(retry.eligible("ble", "b/id", 60_000uL))
        assertFalse(retry.eligible("relay", "a/id", 60_000uL))
    }

    @Test fun backoffCapsAtOneHourAndSaturatesMonotonicTime() {
        val retry = TransportRetry()
        repeat(20) { retry.attempted("ble", "id", 0uL) }
        assertFalse(retry.eligible("ble", "id", 3_599_999uL))
        assertTrue(retry.eligible("ble", "id", 3_600_000uL))
        retry.attempted("ble", "id", ULong.MAX_VALUE - 1uL)
        assertFalse(retry.eligible("ble", "id", ULong.MAX_VALUE - 1uL))
    }
}
