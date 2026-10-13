package dev.sm.messenger.ble

/** Unauthenticated reservations expire without renewal; only accepted AEAD/protocol parts renew. */
internal class PeerLease(private val created: Long) {
    private var acceptedAt: Long? = null
    fun accepted(now: Long) { acceptedAt = now }
    fun expired(now: Long, idleMs: Long): Boolean {
        val at = acceptedAt ?: created
        return now < at || now - at >= idleMs
    }
    fun touched(): Long = acceptedAt ?: created
}
