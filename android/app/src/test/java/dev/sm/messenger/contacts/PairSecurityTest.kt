package dev.sm.messenger.contacts

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PairSecurityTest {
    @Test fun rawNoiseCannotRenewProvisionalSlot() {
        val lease = PairAdmissionLease(0)
        repeat(20) { lease.activity(it * 1000L) }
        assertFalse(lease.expired(19_999))
        assertTrue(lease.expired(20_000))
        lease.authenticated(20_000)
        lease.activity(30_000)
        assertFalse(lease.expired(89_999))
        assertTrue(lease.expired(90_000))
    }

    @Test fun cancellationDrainsQueueAndReleasesEachReservationOnce() {
        val scope = PairTaskScope(2, 10)
        val queue = ArrayList<Runnable>()
        val first = scope.acquire(5)!!
        val second = scope.acquire(5)!!
        assertNull(scope.acquire(0))
        var calls = 0
        first.dispatch({ queue.add(it); true }, { queue.remove(it) }) { calls++ }
        second.dispatch({ queue.add(it); true }, { queue.remove(it) }) { calls++ }
        val stale = queue.toList()
        scope.close(); scope.close()
        assertTrue(queue.isEmpty())
        stale.forEach { it.run() }
        first.release(); second.release()
        assertEquals(0, calls)
        assertNull(scope.acquire(1))
    }

    @Test fun duplicateTaskCannotDoubleRunOrDoubleRelease() {
        val scope = PairTaskScope(1, 10)
        val lease = scope.acquire(10)!!
        var calls = 0
        lease.run { calls++ }; lease.run { calls++ }; lease.release()
        assertEquals(1, calls)
        assertNotNull(scope.acquire(10))
        assertNull(scope.acquire(1))
    }

    @Test fun blockedPrepareCannotCaptureFrameWorkerOrOrdinaryBleBudget() {
        val io = Executors.newSingleThreadExecutor()
        val frames = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val task = io.submit {
            entered.countDown()
            try { CountDownLatch(1).await() } catch (_: InterruptedException) { cancelled.countDown() }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val scope = PairTaskScope(16, 262_144)
            val pending = List(16) { scope.acquire(100)!! }
            assertNull(scope.acquire(100))
            assertTrue(dev.sm.messenger.ble.InboundBudget.tryAcquire(100))
            dev.sm.messenger.ble.InboundBudget.release(100)
            assertEquals(7, frames.submit<Int> { 7 }.get(2, TimeUnit.SECONDS))
            task.cancel(true); scope.close()
            pending.forEach { it.release() }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
        } finally { io.shutdownNow(); frames.shutdownNow() }
    }

    @Test fun duplicateGattCallbackCannotAcknowledgeDifferentFrame() {
        var queued = true
        val port = object : PairMailboxPort {
            override fun prepare() = byteArrayOf(1)
            override fun frames(): List<ByteArray> = if (queued) {
                queued = false
                listOf(byteArrayOf(1), byteArrayOf(2))
            } else emptyList()
            override fun hasPeer() = true
            override fun delivered(id: ByteArray) = true
        }
        val exchange = PairMailboxExchange(port, 0)
        val send = exchange.step(0) as MailboxStep.Send
        exchange.written(send.token, 0); exchange.written(send.token, 0)
        assertSame(MailboxStep.Wait, exchange.step(1))
        exchange.written(send.token, 1)
        assertTrue((exchange.step(2) as MailboxStep.Complete).ready)
    }

    @Test fun asyncPreparationDoesNotRunOnStepOrResurrectTimedOutExchange() {
        var prepares = 0
        val port = object : PairMailboxPort {
            override fun prepare(): ByteArray { prepares++; error("blocking IO on frame worker") }
            override fun frames() = emptyList<ByteArray>()
            override fun hasPeer() = true
            override fun delivered(id: ByteArray) = true
        }
        val exchange = PairMailboxExchange(port, 0, prepareOnStep = false)
        assertSame(MailboxStep.Wait, exchange.step(0))
        assertEquals(0, prepares)
        assertFalse((exchange.step(60_000) as MailboxStep.Complete).ready)
        exchange.prepared(byteArrayOf(1))
        assertFalse((exchange.step(60_001) as MailboxStep.Complete).ready)
    }
}
