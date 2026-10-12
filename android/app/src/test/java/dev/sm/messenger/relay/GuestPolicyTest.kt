package dev.sm.messenger.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правила гостевого пропуска (ADR-146): отзыв по себе и по родителю, тот же узел. */
class GuestPolicyTest {
    private val id = ByteArray(16) { 1 }
    private val parent = ByteArray(16) { 2 }
    private val other = ByteArray(16) { 3 }

    @Test
    fun passIsRevokedByItsOwnId() {
        assertTrue(GuestPolicy.isRevoked(listOf(other, id.copyOf()), id))
        assertFalse(GuestPolicy.isRevoked(listOf(other), id))
        assertFalse(GuestPolicy.isRevoked(emptyList(), id))
    }

    @Test
    fun guestPassIsRevokedWithItsParent() {
        assertTrue(GuestPolicy.isRevoked(listOf(parent.copyOf()), id, parent))
        assertTrue(GuestPolicy.isRevoked(listOf(id.copyOf()), id, parent))
        assertFalse(GuestPolicy.isRevoked(listOf(other), id, parent))
        // Родитель владельческого пропуска не задан: отзыв чужого родителя его не задевает.
        assertFalse(GuestPolicy.isRevoked(listOf(parent.copyOf()), id))
    }

    @Test
    fun sameNodeNeedsPortAndSecretPath() {
        assertTrue(GuestPolicy.sameNode(443, "/a1", 443, "/a1"))
        assertFalse(GuestPolicy.sameNode(443, "/a1", 8443, "/a1"))
        assertFalse(GuestPolicy.sameNode(443, "/a1", 443, "/b2"))
    }
}
