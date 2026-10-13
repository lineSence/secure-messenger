package dev.sm.messenger.contacts

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real ownership helper with transactional in-memory port, NOT Android SQLite instrumentation. */
class PairSessionOwnersTest {
    private class Port : PairSessionPort {
        val journal = HashMap<String, ByteArray>()
        val sessions = HashMap<String, String>()
        val contacts = HashSet<String>()
        var failForget = false
        private var depth = 0
        override fun get(contact: String) = journal[contact]
        override fun put(contact: String, row: ByteArray) { check(depth > 0); journal[contact] = row }
        override fun delete(contact: String) { check(depth > 0); journal.remove(contact) }
        override fun rows() = journal.values.toList()
        override fun known(contact: String): Boolean { check(depth > 0); return contact in contacts }
        override fun forget(contact: String) {
            check(depth > 0)
            sessions.remove(contact)
            if (failForget) throw IllegalStateException("injected store failure")
        }
        override fun corrupt(): Nothing = throw IllegalStateException("corrupt ownership")
        override fun <T : Any> atomic(block: () -> T?): T? {
            if (depth > 0) return block()
            val oldJournal = HashMap(journal)
            val oldSessions = HashMap(sessions)
            depth++
            try {
                val result = block()
                if (result == null) { journal.clear(); journal.putAll(oldJournal); sessions.clear(); sessions.putAll(oldSessions) }
                return result
            } catch (e: Exception) {
                journal.clear(); journal.putAll(oldJournal); sessions.clear(); sessions.putAll(oldSessions)
                throw e
            } finally { depth-- }
        }
    }

    private val port = Port()
    private val owners = PairSessionOwners(port)
    private val peer = "05" + "a".repeat(64)
    private fun owner() = UUID.randomUUID().toString()

    private fun accept(owner: String) {
        port.atomic {
            port.sessions[peer] = owner
            owners.invalidate(peer) // SignalGate does this for ALL successful accept lanes.
            owners.claim(owner, peer) // Pairing does this BEFORE outer transaction commits.
            Unit
        }
    }

    @Test fun committedAcceptIsCleanedEvenWithoutAnyUiPublication() {
        val owner = owner()
        accept(owner)
        // No started/accepted UI map exists yet; close releases the durable UUID, not the map.
        owners.release(owner)
        assertTrue(port.sessions.isEmpty())
        assertTrue(port.journal.isEmpty())
    }

    @Test fun queuedUiCallbackAfterCloseCannotResurrectReleasedSession() {
        val owner = owner()
        var generation = 1
        var published = false
        accept(owner)
        val queuedCallback = { if (generation == 1) published = true }
        generation++
        owners.release(owner)
        queuedCallback()
        assertFalse(published)
        assertTrue(port.sessions.isEmpty())
        assertFalse(owners.owns(owner, peer))
    }

    @Test fun replacementPairingOwnerSurvivesOldGenerationCleanup() {
        val old = owner(); val newer = owner()
        accept(old); accept(newer)
        owners.releaseContact(old, peer)
        owners.release(old)
        assertEquals(newer, port.sessions[peer])
        assertTrue(owners.owns(newer, peer))
        owners.recover()
        assertEquals(newer, port.sessions[peer])
    }

    @Test fun nonPairingAcceptInvalidatesOldMarkerAndSurvivesCleanup() {
        val old = owner()
        accept(old)
        port.atomic { port.sessions[peer] = "invite"; owners.invalidate(peer); Unit }
        assertFalse(owners.owns(old, peer))
        owners.releaseContact(old, peer); owners.release(old); owners.recover()
        assertEquals("invite", port.sessions[peer])
        assertTrue(port.journal.isEmpty())
    }

    @Test fun durableContactSurvivesTerminalCleanup() {
        val owner = owner()
        accept(owner)
        port.atomic { port.contacts.add(peer); Unit }
        owners.release(owner)
        assertEquals(owner, port.sessions[peer])
        assertTrue(port.journal.isEmpty())
    }

    @Test fun failedCleanupRollsBackAndRemainsRetryableWhileOwnerIsInactive() {
        val owner = owner()
        accept(owner)
        port.failForget = true
        assertThrows(IllegalStateException::class.java) { owners.release(owner) }
        assertEquals(owner, port.sessions[peer])
        assertTrue(owners.owns(owner, peer))
        port.failForget = false
        owners.recover()
        assertTrue(port.sessions.isEmpty())
        assertTrue(port.journal.isEmpty())
    }

    @Test fun restartReclaimsAbandonedButNotDurablySavedSession() {
        val owner = owner()
        accept(owner)
        PairSessionOwners(port).recover() // new Gate/process has an empty live-owner set.
        assertTrue(port.sessions.isEmpty())
        accept(owner)
        port.contacts.add(peer)
        PairSessionOwners(port).recover()
        assertEquals(owner, port.sessions[peer])
        assertTrue(port.journal.isEmpty())
    }

    @Test fun recoveryDoesNotReclaimAnotherLiveScreenSharingTheGate() {
        val owner = owner()
        accept(owner)
        owners.recover()
        assertEquals(owner, port.sessions[peer])
        assertTrue(owners.owns(owner, peer))
    }

    @Test fun acceptanceRollbackCannotPublishOwnershipOrReplacePreviousSession() {
        val old = owner(); val newer = owner()
        accept(old)
        assertThrows(IllegalStateException::class.java) {
            port.atomic { accept(newer); throw IllegalStateException("commit failed") }
        }
        assertEquals(old, port.sessions[peer])
        assertTrue(owners.owns(old, peer))
        owners.release(newer)
        assertEquals(old, port.sessions[peer])
    }

    @Test fun corruptJournalFailsClosedWithoutForgettingSession() {
        val owner = owner()
        accept(owner)
        port.journal[peer] = ByteArray(102)
        assertThrows(IllegalStateException::class.java) { owners.release(owner) }
        assertEquals(owner, port.sessions[peer])
    }
}
