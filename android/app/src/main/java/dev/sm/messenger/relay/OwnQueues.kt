package dev.sm.messenger.relay

import java.nio.ByteBuffer

/** Строки `kv` для почты узла (в приложении — [dev.sm.messenger.store.SecureDb], строки зашифрованы). */
interface RelayKv {
    fun get(table: String, id: ByteArray): ByteArray?

    fun put(table: String, id: ByteArray, value: ByteArray)

    fun delete(table: String, id: ByteArray)
}

/**
 * Своя входящая очередь для контакта (ADR-82 п.1, ADR-134): период (ADR-137), ID получателя
 * (из секрета устройства) и ID отправителя, который вернул узел на `NEW` и который знает контакт.
 */
class OwnQueue(val period: Long, val recipientId: ByteArray, val senderId: ByteArray) {
    init {
        require(period >= 0 && recipientId.size == OwnQueues.ID_LEN && senderId.size == OwnQueues.ID_LEN) { "очередь" }
    }
}

/**
 * Свои очереди на контакт: таблица `relay_own`, ключ — адрес контакта. Хранятся две последние по
 * периоду: в новую контакт шлёт, когда получит новый ID отправителя (сообщение «адрес почты»),
 * до этого — в прежнюю, поэтому прежнюю ещё читаем (ADR-137).
 */
class OwnQueues(private val kv: RelayKv) {
    /** Очереди контакта, новая первой; пусто — ещё не создавались или строка не того формата. */
    fun get(address: String): List<OwnQueue> {
        val b = kv.get(TABLE, key(address)) ?: return emptyList()
        if (b.isEmpty() || (b.size - 1) % ROW != 0 || b[0].toInt() != VERSION) return emptyList()
        val buf = ByteBuffer.wrap(b, 1, b.size - 1)
        val out = ArrayList<OwnQueue>()
        repeat((b.size - 1) / ROW) {
            val period = buf.long
            val r = ByteArray(ID_LEN).also { buf.get(it) }
            val s = ByteArray(ID_LEN).also { buf.get(it) }
            if (period < 0) return emptyList()
            out.add(OwnQueue(period, r, s))
        }
        return out
    }

    /**
     * Записать очередь [q]: та же по периоду заменяется, остаются две новейшие. Очередь старше
     * обеих хранимых не записывается — `false`.
     */
    fun put(address: String, q: OwnQueue): Boolean {
        val kept = get(address).filter { it.period != q.period }
        val all = (kept + q).sortedByDescending { it.period }.take(KEEP)
        if (q !in all) return false
        val buf = ByteBuffer.allocate(1 + all.size * ROW).put(VERSION.toByte())
        all.forEach { buf.putLong(it.period).put(it.recipientId).put(it.senderId) }
        kv.put(TABLE, key(address), buf.array())
        return true
    }

    /** Удалить очереди контакта (контакт удалён). */
    fun delete(address: String) = kv.delete(TABLE, key(address))

    private fun key(address: String) = address.toByteArray(Charsets.UTF_8)

    companion object {
        /** Период ID очередей — 30 дней (ADR-137). */
        const val PERIOD_SECS = 30L * 24 * 60 * 60

        /** Длина ID очереди на узле. */
        const val ID_LEN = 32

        /** Номер периода для времени Unix [nowSecs] (с). */
        fun period(nowSecs: Long): Long = nowSecs / PERIOD_SECS

        private const val TABLE = "relay_own"
        private const val VERSION = 1
        private const val KEEP = 2
        private const val ROW = Long.SIZE_BYTES + 2 * ID_LEN
    }
}
