package dev.sm.messenger.relay

/** Signal-authenticated contact messages only. Reject far future; recover poisoned legacy rows. */
internal object MailboxTime {
    const val SKEW_MS = 5 * 60_000L
    fun accepts(old: Long?, incoming: Long, nowMs: Long): Boolean {
        if (incoming < 0 || nowMs < 0) return false
        val upper = if (nowMs > Long.MAX_VALUE - SKEW_MS) Long.MAX_VALUE else nowMs + SKEW_MS
        if (incoming > upper) return false
        // Future timestamps within skew cannot pin a route beyond local wall time.
        val previous = old?.takeIf { it >= 0 && it <= upper }?.coerceAtMost(nowMs)
        return previous == null || incoming > previous
    }
    fun stored(incoming: Long, nowMs: Long): Long = minOf(incoming, nowMs)
}
