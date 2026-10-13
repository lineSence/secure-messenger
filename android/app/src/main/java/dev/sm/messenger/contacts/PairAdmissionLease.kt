package dev.sm.messenger.contacts

/** Raw/partial traffic cannot renew a provisional admission slot. */
internal class PairAdmissionLease(private var at: Long) {
    private var authenticated = false
    fun authenticated(now: Long) { authenticated = true; at = now }
    fun activity(now: Long) { if (authenticated) at = now }
    fun expired(now: Long): Boolean = now - at >= if (authenticated) 60_000L else 20_000L
}
