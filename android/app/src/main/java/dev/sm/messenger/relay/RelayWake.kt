package dev.sm.messenger.relay

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Independent coalescing channels. Work wakes interrupt idle, not failing endpoint backoff. */
internal class RelayWake {
    private val bell = LinkedBlockingQueue<Unit>(1)
    private val generation = AtomicLong()
    fun epoch(): Long = generation.get()
    fun signal(reset: Boolean = false) {
        if (reset) generation.incrementAndGet()
        bell.offer(Unit)
    }
    fun await(ms: Long, failed: Boolean, epoch: Long, offline: Boolean) {
        if (offline) { bell.take(); return }
        val deadline = OperationDeadline(ms)
        do {
            val remaining = deadline.remainingMs()
            if (remaining <= 0) return
            bell.poll(remaining, TimeUnit.MILLISECONDS)
        } while (failed && epoch == generation.get())
    }
}
