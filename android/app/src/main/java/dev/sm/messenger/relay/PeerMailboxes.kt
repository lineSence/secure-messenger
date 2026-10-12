package dev.sm.messenger.relay

import dev.sm.messenger.store.SecureDb
import java.nio.ByteBuffer
import uniffi.sm_ffi.NodeFfiException
import uniffi.sm_ffi.nodeRecordOpen

/** Адрес почты контакта (ADR-82 п.1): ID отправителя его входящей очереди и запись его узла. */
class PeerMailbox(val senderId: ByteArray, val node: String, val sentAt: Long)

/**
 * Адреса почты контактов: таблица `relay_peer` в [SecureDb] (строки зашифрованы), ключ — адрес
 * контакта. Адрес приходит сообщением внутри сессии Signal (`ChatBody.Mailbox`). Запись узла
 * принимается, только если подпись владельца и срок верны (ADR-81). Из двух адресов одного
 * контакта остаётся более новый по времени отправки: старый может прийти позже другим путём
 * (Bluetooth и узел, ADR-82 п.2).
 */
class PeerMailboxes internal constructor(private val db: SecureDb) {
    fun get(address: String): PeerMailbox? {
        val b = db.kvGet(TABLE, key(address)) ?: return null
        if (b.size <= HEAD) return null
        val buf = ByteBuffer.wrap(b)
        val sentAt = buf.long
        val id = ByteArray(ID_LEN).also { buf.get(it) }
        return PeerMailbox(id, String(b, HEAD, b.size - HEAD, Charsets.US_ASCII), sentAt)
    }

    /**
     * Принять адрес из сообщения, отправленного в [sentAt] (мс, часы контакта). `false` — запись
     * узла не принята или уже есть адрес новее.
     */
    fun accept(address: String, senderId: ByteArray, node: String, sentAt: Long, nowSecs: Long): Boolean {
        if (senderId.size != ID_LEN) return false
        try {
            nodeRecordOpen(node, nowSecs.toULong())
        } catch (_: NodeFfiException) {
            return false
        }
        val old = get(address)
        if (old != null && old.sentAt >= sentAt) return false
        val text = node.toByteArray(Charsets.US_ASCII)
        val row = ByteBuffer.allocate(HEAD + text.size).putLong(sentAt).put(senderId).put(text).array()
        db.kvPut(TABLE, key(address), row)
        return true
    }

    fun delete(address: String) = db.kvDelete(TABLE, key(address))

    private fun key(address: String) = address.toByteArray(Charsets.UTF_8)

    private companion object {
        const val TABLE = MailboxTables.PEER
        const val ID_LEN = 32
        const val HEAD = Long.SIZE_BYTES + ID_LEN
    }
}
