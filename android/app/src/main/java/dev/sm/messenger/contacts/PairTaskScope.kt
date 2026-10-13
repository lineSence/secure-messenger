package dev.sm.messenger.contacts

import java.util.concurrent.atomic.AtomicBoolean

/** Pair-only admission; cancellation releases queued reservations exactly once. */
internal class PairTaskScope(private val maxTasks: Int = 16, private val maxBytes: Int = 262_144) {
    private val leases = HashSet<Lease>()
    private var bytes = 0
    private var closed = false

    internal inner class Lease(private val size: Int) {
        private val released = AtomicBoolean()
        private val started = AtomicBoolean()
        private var cancel: (() -> Unit)? = null
        fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(this@PairTaskScope) {
                cancel = null
                leases.remove(this)
                bytes -= size
            }
        }
        fun dispatch(schedule: (Runnable) -> Boolean, unschedule: (Runnable) -> Unit, block: () -> Unit) {
            val task = Runnable { run(block) }
            synchronized(this@PairTaskScope) {
                if (closed || released.get()) { release(); return }
                cancel = { unschedule(task) }
                try {
                    if (!schedule(task)) release()
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    release()
                }
            }
        }
        fun cancel() {
            val action = synchronized(this@PairTaskScope) { cancel }
            action?.invoke()
            release()
        }
        fun run(block: () -> Unit) {
            if (!started.compareAndSet(false, true)) return
            try { if (!released.get() && isOpen()) block() } finally { release() }
        }
    }

    @Synchronized fun acquire(size: Int): Lease? {
        if (closed || size < 0 || leases.size >= maxTasks || size > maxBytes - bytes) return null
        return Lease(size).also { leases.add(it); bytes += size }
    }
    @Synchronized fun isOpen(): Boolean = !closed
    fun close() {
        val pending = synchronized(this) { closed = true; leases.toList() }
        pending.forEach { it.cancel() }
    }
}
