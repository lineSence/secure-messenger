package dev.sm.messenger.chat

/** The SQL cleanup intent is deleted only after AtomicFile persisted the cleared snapshot.
 * Exceptions/crash leave the intent for attach recovery; repeating cleanup is idempotent.
 */
internal fun completeRemovalSnapshot(persist: () -> Boolean, deleteIntent: () -> Unit): Boolean {
    if (!persist()) return false
    deleteIntent()
    return true
}
