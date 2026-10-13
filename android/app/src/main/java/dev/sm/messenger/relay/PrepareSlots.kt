package dev.sm.messenger.relay

/** Short reservations only: release the monitor BEFORE HTTP, SQLite or sm-chat waits. */
internal class PrepareSlots(private val limit: Int) {
    private val active = HashSet<String>()
    @Synchronized fun acquire(address: String): Boolean =
        active.size < limit && active.add(address)
    @Synchronized fun release(address: String) { active.remove(address) }
}
