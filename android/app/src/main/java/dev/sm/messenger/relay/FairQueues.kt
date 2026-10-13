package dev.sm.messenger.relay

/** All queues, including invitations, share the cursor. Advance on every outcome. */
internal class FairQueues {
    private var cursor = 0
    fun <T> window(items: List<T>, limit: Int): List<T> {
        require(limit > 0)
        if (items.isEmpty()) return emptyList()
        cursor %= items.size
        return List(minOf(limit, items.size)) { items[(cursor + it) % items.size] }
    }
    fun advance(total: Int, selected: Int) {
        if (total > 0) cursor = (cursor + selected + 1) % total
    }
}
