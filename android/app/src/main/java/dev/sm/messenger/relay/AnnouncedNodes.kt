package dev.sm.messenger.relay

/** Таблицы `kv` с почтой по контакту (ключ — адрес контакта): уходят вместе с контактом. */
object MailboxTables {
    const val OWN = "relay_own"
    const val PEER = "relay_peer"
    const val ANNOUNCED = "relay_told"

    /** Убрать из [delete] всё, что узел знает о контакте [address] (контакт удалён). */
    fun forget(address: String, delete: (table: String, id: ByteArray) -> Unit) {
        val key = address.toByteArray(Charsets.UTF_8)
        listOf(OWN, PEER, ANNOUNCED).forEach { delete(it, key) }
    }
}

/**
 * Какую запись своего узла получил каждый контакт (сообщение «адрес почты», ADR-82 п.1):
 * таблица `relay_told`, ключ — адрес контакта, значение — текст записи. Запись узла сменилась до
 * смены периода (ADR-137) — контакт получает новую, не дожидаясь нового периода (п.11).
 */
class AnnouncedNodes(private val kv: RelayKv) {
    /** `true` — контакт [address] уже получил именно запись [node]. */
    fun isCurrent(address: String, node: String): Boolean =
        kv.get(MailboxTables.ANNOUNCED, key(address))?.contentEquals(node.toByteArray(Charsets.US_ASCII)) == true

    /** Запомнить: контакту [address] поставлено в очередь доставки сообщение с записью [node]. */
    fun mark(address: String, node: String) =
        kv.put(MailboxTables.ANNOUNCED, key(address), node.toByteArray(Charsets.US_ASCII))

    fun delete(address: String) = kv.delete(MailboxTables.ANNOUNCED, key(address))

    private fun key(address: String) = address.toByteArray(Charsets.UTF_8)
}
