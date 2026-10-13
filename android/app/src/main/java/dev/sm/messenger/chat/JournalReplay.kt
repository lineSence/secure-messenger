package dev.sm.messenger.chat

/** Retire only entries successfully included in the durable snapshot, never an entire journal. */
internal object JournalReplay {
    fun <T> included(entries: Iterable<T>, admit: (T) -> Boolean): List<T> =
        entries.filter(admit)

    fun <T> retire(included: List<T>, save: () -> Boolean, remove: (T) -> Unit) {
        if (included.isNotEmpty() && save()) included.forEach(remove)
    }
}
