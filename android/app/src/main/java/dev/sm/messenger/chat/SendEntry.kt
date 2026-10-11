package dev.sm.messenger.chat

/**
 * Запись журнала отправки (`kv/chat_send`): сообщение уже в истории «в очереди», но, возможно,
 * ещё не в снимке очереди на диске. Пишется в одной транзакции со строкой истории и удаляется
 * после записи снимка; оставшиеся после гибели процесса записи при запуске возвращаются в
 * очередь ([ChatHub]).
 *
 * Формат: `[версия = 1][длина id][id][длина адреса][адрес ASCII][кадр сообщения]`.
 */
internal class SendEntry(val id: ByteArray, val address: String, val blob: ByteArray) {
    fun encode(): ByteArray {
        val a = address.toByteArray(Charsets.US_ASCII)
        require(id.size in 1..MAX_FIELD && a.size in 1..MAX_FIELD) { "поле записи журнала вне предела" }
        return byteArrayOf(VERSION, id.size.toByte()) + id + byteArrayOf(a.size.toByte()) + a + blob
    }

    companion object {
        private const val VERSION: Byte = 1
        private const val MAX_FIELD = 255

        /** `null` — не запись журнала этой версии. */
        fun decode(b: ByteArray): SendEntry? {
            if (b.size < 2 || b[0] != VERSION) return null
            val idLen = b[1].toInt() and 0xFF
            val addrAt = 2 + idLen
            if (idLen == 0 || b.size <= addrAt) return null
            val addrLen = b[addrAt].toInt() and 0xFF
            val blobAt = addrAt + 1 + addrLen
            if (addrLen == 0 || b.size < blobAt) return null
            val address = b.copyOfRange(addrAt + 1, blobAt)
            if (address.any { it < 0x21 || it > 0x7E }) return null
            return SendEntry(b.copyOfRange(2, addrAt), String(address, Charsets.US_ASCII), b.copyOfRange(blobAt, b.size))
        }
    }
}
