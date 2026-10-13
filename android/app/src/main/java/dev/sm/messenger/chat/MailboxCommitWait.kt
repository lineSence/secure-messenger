package dev.sm.messenger.chat

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/** Bounded caller wait; SQL execution is never interrupted. Null after claim means unknown. */
internal class MailboxCommitWait(private val now: () -> Long = System::nanoTime) {
    private val startedAt = now()
    private val phase = AtomicInteger(WAITING)

    fun beginCommit(): Boolean {
        // The worker must honor the deadline even if the caller has not been scheduled to cancel.
        if (now() - startedAt >= TimeUnit.MILLISECONDS.toNanos(WAIT_MS)) {
            phase.compareAndSet(WAITING, CANCELLED)
            return false
        }
        return phase.compareAndSet(WAITING, CLAIMED)
    }

    private fun cancelBeforeCommit(task: Future<*>): Boolean {
        if (!phase.compareAndSet(WAITING, CANCELLED)) return false
        // false: never interrupt sm-chat, especially a SQLite transaction.
        task.cancel(false)
        return true
    }

    fun <T> await(task: Future<T>, timeoutMs: Long = WAIT_MS): T? =
        try {
            task.get(
                minOf(TimeUnit.MILLISECONDS.toNanos(timeoutMs), (TimeUnit.MILLISECONDS.toNanos(WAIT_MS) - (now() - startedAt)).coerceAtLeast(0L)),
                TimeUnit.NANOSECONDS,
            )
        } catch (_: TimeoutException) {
            cancelBeforeCommit(task)
            null
        } catch (_: InterruptedException) {
            cancelBeforeCommit(task)
            Thread.currentThread().interrupt()
            null
        }

    private companion object {
        const val WAITING = 0
        const val CLAIMED = 1
        const val CANCELLED = 2
        const val WAIT_MS = 20_000L
    }
}
