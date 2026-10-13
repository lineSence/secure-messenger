package dev.sm.messenger.relay

import java.net.SocketTimeoutException

/** Total monotonic budget, not renewed by slow progress or changes to wall clock. */
internal class OperationDeadline(ms: Long, private val nanos: () -> Long = System::nanoTime) {
    private val start = nanos()
    private val budget = TimeUnitSafe.millisToNanos(ms)
    fun remainingMs(): Long {
        val elapsed = nanos() - start
        return ((budget - elapsed.coerceAtLeast(0)) / 1_000_000L).coerceAtLeast(0)
    }
    fun check() {
        if (remainingMs() <= 0 || Thread.currentThread().isInterrupted) throw SocketTimeoutException("operation deadline")
    }
}

private object TimeUnitSafe {
    fun millisToNanos(ms: Long): Long {
        require(ms in 1..Long.MAX_VALUE / 1_000_000L)
        return ms * 1_000_000L
    }
}
