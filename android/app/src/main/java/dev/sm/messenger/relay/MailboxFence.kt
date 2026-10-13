package dev.sm.messenger.relay

import java.util.concurrent.atomic.AtomicInteger

/** Cancellation and commit claim are exclusive. Unknown SQL outcome must never trigger live DEL. */
internal class MailboxFence {
    private val phase = AtomicInteger(0)
    fun beginCommit(): Boolean = phase.compareAndSet(0, 1)
    fun cancelBeforeCommit(): Boolean = phase.compareAndSet(0, 2)
}
