package dev.sm.messenger.chat

import dev.sm.messenger.relay.MailboxFence
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MailboxCommitWaitTest {
    @Test fun queuedTimeoutCannotClaimOrCommitLater() {
        val wait = MailboxCommitWait()
        val committed = AtomicBoolean()
        val task = FutureTask {
            committed.set(wait.beginCommit())
            "id"
        }
        assertNull(wait.await(task, 1))
        task.run()
        assertTrue(task.isCancelled)
        assertFalse(wait.beginCommit())
        assertFalse(committed.get())
    }

    @Test fun taskWaitingForSqlCannotWriteAfterTimeout() {
        val pool = Executors.newSingleThreadExecutor()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val committed = AtomicBoolean()
        val wait = MailboxCommitWait()
        try {
            val task = pool.submit<String> {
                reached.countDown()
                release.await()
                if (wait.beginCommit()) committed.set(true)
                finished.countDown()
                "id"
            }
            assertTrue(reached.await(1, TimeUnit.SECONDS))
            assertNull(wait.await(task, 1))
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertFalse(committed.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun claimedSqlIsNotCancelledAndRemoteDelIsForbidden() {
        val pool = Executors.newSingleThreadExecutor()
        val reached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val wait = MailboxCommitWait()
        val remote = MailboxFence()
        try {
            val task = pool.submit<String> {
                check(wait.beginCommit())
                check(remote.beginCommit())
                reached.countDown()
                release.await()
                "durable-id"
            }
            assertTrue(reached.await(1, TimeUnit.SECONDS))
            assertNull(wait.await(task, 1))
            assertFalse(task.isCancelled)
            assertFalse(remote.cancelBeforeCommit())
            release.countDown()
            assertEquals("durable-id", task.get(1, TimeUnit.SECONDS))
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun remoteCancellationBetweenInternalAndExternalClaimsRejectsCommit() {
        val wait = MailboxCommitWait()
        val remote = MailboxFence()
        val task = FutureTask { "id" }
        assertTrue(wait.beginCommit())
        assertNull(wait.await(task, 1))
        assertTrue(remote.cancelBeforeCommit())
        assertFalse(remote.beginCommit())
        assertFalse(task.isCancelled)
    }

    @Test fun callerInterruptionIsRestoredAndCancelsPreclaim() {
        val wait = MailboxCommitWait()
        val task = FutureTask { "id" }
        try {
            Thread.currentThread().interrupt()
            assertNull(wait.await(task, 100))
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(wait.beginCommit())
            assertTrue(task.isCancelled)
        } finally { Thread.interrupted() }
    }

    @Test fun expiredWorkerCannotClaimEvenBeforeCallerRuns() {
        var clock = 0L
        val wait = MailboxCommitWait { clock }
        clock = TimeUnit.SECONDS.toNanos(20)
        assertFalse(wait.beginCommit())
        clock = 0L
        assertFalse(wait.beginCommit())
    }

    @Test fun interruptionAfterClaimDoesNotCancelSqlOrAuthorizeDel() {
        val wait = MailboxCommitWait()
        val remote = MailboxFence()
        val task = FutureTask { "id" }
        assertTrue(wait.beginCommit())
        assertTrue(remote.beginCommit())
        try {
            Thread.currentThread().interrupt()
            assertNull(wait.await(task, 100))
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(task.isCancelled)
            assertFalse(remote.cancelBeforeCommit())
            task.run()
            Thread.interrupted()
            assertEquals("id", task.get())
        } finally { Thread.interrupted() }
    }

    @Test fun completedResultIsUnchanged() {
        val wait = MailboxCommitWait()
        val task = FutureTask { check(wait.beginCommit()); "id" }
        task.run()
        assertEquals("id", wait.await(task, 100))
    }
}
