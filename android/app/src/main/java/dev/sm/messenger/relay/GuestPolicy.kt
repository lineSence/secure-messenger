package dev.sm.messenger.relay

/**
 * Чистые правила гостевого пропуска (ADR-146), без обращений к ядру: отзыв и «тот же узел».
 * Формат, срок и подпись проверяют ядро (`sm_core::pass`) и узел.
 */
internal object GuestPolicy {
    /**
     * Отозван ли пропуск: его [id] или, у гостевого, [parent] — пропуск владельца, от которого
     * пошла цепочка, — есть в списке [revoked] записи узла.
     */
    fun isRevoked(revoked: List<ByteArray>, id: ByteArray, parent: ByteArray? = null): Boolean =
        revoked.any { it.contentEquals(id) || (parent != null && it.contentEquals(parent)) }

    /**
     * Тот же узел: совпадают порт и секретный путь (путь у каждого узла свой). Гостевой пропуск
     * выдаёт и принимает только узел-выдавший, на другом он не нужен.
     */
    fun sameNode(portA: Int, pathA: String, portB: Int, pathB: String): Boolean = portA == portB && pathA == pathB
}
