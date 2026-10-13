package dev.sm.messenger.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContactIncarnationTest {
    @Test fun legacyPositionalCallerGetsFreshImmutableTokenPerInsertion() {
        val first = Contact(1, "peer", "name", byteArrayOf(1), byteArrayOf(2), false, 1)
        val second = Contact(1, "peer", "name", byteArrayOf(1), byteArrayOf(2), false, 1)
        assertNotEquals(first.incarnation, second.incarnation)
    }

    @Test fun explicitlyRestoredTokenIsPreserved() {
        val c = Contact(1, "peer", "name", byteArrayOf(1), byteArrayOf(2), false, 1, "durable-token")
        assertEquals("durable-token", c.incarnation)
    }
}
