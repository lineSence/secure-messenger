package dev.sm.messenger.contacts

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.sm_ffi.SignalFfiException

class PairCardOwnersTest {
    @Test fun handleRoundTripCarriesExactCardAndGeneration() {
        val id = UUID.randomUUID().toString()
        val owner = UUID.randomUUID().toString()
        val card = byteArrayOf(1, 2, 3)
        val entry = PairCardOwners.decode(PairCardOwners.encode(id, owner, card))
        assertEquals(id, entry.id)
        assertEquals(owner, entry.owner)
        assertArrayEquals(card, entry.card)
    }

    @Test(expected = SignalFfiException.Store::class)
    fun malformedHandleFailsClosed() { PairCardOwners.decode(ByteArray(73)) }

    @Test(expected = SignalFfiException.Store::class)
    fun oversizedHandleFailsClosed() { PairCardOwners.decode(ByteArray(4169)) }

    @Test(expected = IllegalArgumentException::class)
    fun emptyCardCannotBeJournaled() {
        PairCardOwners.encode(UUID.randomUUID().toString(), UUID.randomUUID().toString(), byteArrayOf())
    }
}
