package dev.sm.messenger.relay

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkSecurityTest {
    @Test fun cancelledPrepareCannotCommitLate() {
        val f = MailboxFence(); assertTrue(f.cancelBeforeCommit()); assertFalse(f.beginCommit())
    }
    @Test fun unknownCommitOutcomeMustNotDeleteLiveQueue() {
        val f = MailboxFence(); assertTrue(f.beginCommit()); assertFalse(f.cancelBeforeCommit())
    }
    @Test fun commitCanOnlyBeClaimedOnce() {
        val f = MailboxFence(); assertTrue(f.beginCommit()); assertFalse(f.beginCommit())
    }
    @Test fun emptyEnvelopePreservesUnsignedSequenceForAck() {
        val d = byteArrayOf(1) + ByteArray(8) { 0xff.toByte() }
        val got = receiveEnvelope(d, 2)
        assertEquals(1, got.queue); assertEquals(-1L, got.seq); assertTrue(got.blob.isEmpty())
    }
    @Test fun truncatedEnvelopeCannotInventAnAck() {
        for (n in 0..8) {
            try { receiveEnvelope(ByteArray(n), 1); fail("truncated $n") }
            catch (_: RelayException) { /* expected */ }
        }
    }
    @Test fun outOfRangeQueueCannotAckAnotherQueue() {
        try { receiveEnvelope(byteArrayOf(2) + ByteArray(8), 2); fail("queue") }
        catch (_: RelayException) { /* expected */ }
    }
    @Test fun nonemptyEnvelopePreservesBlob() {
        assertArrayEquals(byteArrayOf(42), receiveEnvelope(ByteArray(9) + byteArrayOf(42), 1).blob)
    }
    @Test fun continuouslyNonemptyFirstQueueDoesNotStarveInvitesOrContacts() {
        val f = FairQueues(); val all = (0..129).toList(); val seen = HashSet<Int>()
        repeat(260) { seen.add(f.window(all, 64).first()); f.advance(all.size, 0) }
        assertEquals(all.toSet(), seen)
    }
    @Test fun temporarilyMissingOrUnhandleableQueueDoesNotStarveOthers() {
        val f = FairQueues(); val all = listOf("bad", "busy", "quiet")
        assertEquals("bad", f.window(all, 64).first()); f.advance(all.size, 0)
        assertEquals("busy", f.window(all, 64).first()); f.advance(all.size, 0)
        assertEquals("quiet", f.window(all, 64).first())
    }
    @Test fun emptyWindowsRotateAcrossAllQueuesAndChangingMembership() {
        val f = FairQueues(); val all = (0..129).toList(); val seen = HashSet<Int>()
        repeat(3) { seen.addAll(f.window(all, 64)); f.advance(all.size, 63) }
        assertEquals(130, seen.size)
        assertEquals(listOf(7), f.window(listOf(7), 64)); assertTrue(f.window(emptyList<Int>(), 64).isEmpty())
    }
    @Test fun selectedQueueAdvancesPastItsPosition() {
        val f = FairQueues(); f.advance(4, 2); assertEquals(3, f.window((0..3).toList(), 4).first())
    }
    @Test fun farFutureAndNegativeTimestampAreRejected() {
        assertFalse(MailboxTime.accepts(null, Long.MAX_VALUE, 1000))
        assertFalse(MailboxTime.accepts(null, -1, 1000))
    }
    @Test fun legacyPoisonRecoversWithAuthenticatedNormalTime() {
        assertTrue(MailboxTime.accepts(Long.MAX_VALUE, 1000, 1000))
    }
    @Test fun boundedFutureIsClampedSoClockCorrectionDoesNotFreezeRoute() {
        assertTrue(MailboxTime.accepts(null, 1000 + MailboxTime.SKEW_MS, 1000))
        val stored = MailboxTime.stored(1000 + MailboxTime.SKEW_MS, 1000)
        assertEquals(1000L, stored)
        assertTrue(MailboxTime.accepts(stored, 1001, 1001))
    }
    @Test fun normalOlderAndDuplicateRoutesStayRejected() {
        assertFalse(MailboxTime.accepts(1000, 999, 1000))
        assertFalse(MailboxTime.accepts(1000, 1000, 1000))
        assertTrue(MailboxTime.accepts(1000, 1001, 1001))
    }
    @Test fun timePolicyOverflowIsBounded() {
        assertTrue(MailboxTime.accepts(null, Long.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun deadlineIsTotalNotRenewedByProgress() {
        var n = 0L; val d = OperationDeadline(100) { n }
        n = 40_000_000; assertEquals(60L, d.remainingMs())
        n = 100_000_000; assertEquals(0L, d.remainingMs())
        try { d.check(); fail("deadline") } catch (_: SocketTimeoutException) { /* expected */ }
    }
    @Test fun deadlineUsesMonotonicWrapSafeElapsed() {
        var n = Long.MAX_VALUE - 10_000_000; val d = OperationDeadline(100) { n }
        n += 50_000_000; assertEquals(50L, d.remainingMs())
    }
    @Test fun interruptCancelsDeadlineWithoutClearingFlag() {
        Thread.currentThread().interrupt()
        try {
            try { OperationDeadline(1000).check(); fail("interrupt") } catch (_: SocketTimeoutException) { /* expected */ }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
    @Test fun reservationsBoundConcurrencyAndNeverWaitOnAnotherAddress() {
        val p = PrepareSlots(2)
        assertTrue(p.acquire("a")); assertFalse(p.acquire("a")); assertTrue(p.acquire("b")); assertFalse(p.acquire("c"))
        p.release("a"); assertTrue(p.acquire("c"))
    }
    @Test fun independentSenderAndReceiverWakeCannotStealSignal() {
        val sender = RelayWake(); val receiver = RelayWake()
        sender.signal(); receiver.signal()
        val before = System.nanoTime()
        sender.await(2000, false, sender.epoch(), false)
        receiver.await(2000, false, receiver.epoch(), false)
        assertTrue((System.nanoTime() - before) / 1_000_000 < 1000)
    }
    @Test fun ordinaryWakePreservesFailingEndpointBackoff() {
        val wake = RelayWake(); wake.signal()
        val before = System.nanoTime(); wake.await(80, true, wake.epoch(), false)
        assertTrue((System.nanoTime() - before) / 1_000_000 >= 60)
    }
    @Test fun networkResetInterruptsEvenMaximumBackoff() {
        val wake = RelayWake(); val entered = CountDownLatch(1); val done = CountDownLatch(1)
        val t = Thread { val epoch = wake.epoch(); entered.countDown(); wake.await(300_000, true, epoch, false); done.countDown() }
        t.start()
        try { assertTrue(entered.await(1, TimeUnit.SECONDS)); wake.signal(reset = true); assertTrue(done.await(1, TimeUnit.SECONDS)) }
        finally { t.interrupt(); t.join(1000) }
    }
}
