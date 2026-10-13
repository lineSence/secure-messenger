package dev.sm.messenger.chat

/** sm-chat only. Per-transport bounded schedules; never consumes native/shared outbox attempts. */
internal class TransportRetry(private val maxEntries: Int = 1000) {
    private class Attempt(val last: ULong, var due: ULong, val delay: ULong)
    private val transports = HashMap<String, MutableMap<String, Attempt>>()

    fun eligible(transport: String, key: String, now: ULong): Boolean {
        val entries = transports.getOrPut(transport) { HashMap() }
        return entries[key]?.let { now >= it.due } ?: (entries.size < maxEntries)
    }

    fun attempted(transport: String, key: String, now: ULong) {
        val entries = transports.getOrPut(transport) { HashMap() }
        if (key !in entries && entries.size >= maxEntries) return
        val delay = entries[key]?.delay?.let { (it * 2uL).coerceAtMost(3_600_000uL) } ?: 60_000uL
        val due = if (ULong.MAX_VALUE - now < delay) ULong.MAX_VALUE else now + delay
        entries[key] = Attempt(now, due, delay)
    }

    fun retain(keys: Set<String>) {
        transports.values.forEach { it.keys.retainAll(keys) }
    }

    fun reset(transport: String, mailbox: String, now: ULong) {
        // Reconnect is attacker-controlled too: never bypass the one-minute retry floor.
        transports[transport]?.forEach { (key, attempt) ->
            if (key.startsWith(mailbox)) {
                val floor = if (ULong.MAX_VALUE - attempt.last < 60_000uL) ULong.MAX_VALUE else attempt.last + 60_000uL
                attempt.due = minOf(attempt.due, maxOf(now, floor))
            }
        }
    }
}
