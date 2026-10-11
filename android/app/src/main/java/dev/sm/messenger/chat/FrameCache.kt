package dev.sm.messenger.chat

import java.security.MessageDigest

/** Строки `kv` для кэша кадров: в приложении — `SecureDb`, в тестах — карта. */
internal interface FrameKv {
    /** Значение или `null`, если строки нет. Подмена или порча строки — [CorruptFrame]. */
    fun get(table: String, id: ByteArray): ByteArray?

    fun put(table: String, id: ByteArray, value: ByteArray)

    /**
     * [block] — одна транзакция вместе с сессией Signal: исключение или `null` — откат и строк,
     * и шага цепочки (ревью v0.0.1-alpha, п.13).
     */
    fun <T : Any> atomic(block: () -> T?): T?
}

/** Кадр исходящего сообщения: готовый к отправке или сообщение уже доставлено. */
internal sealed interface OutFrame {
    class Ready(val frame: ByteArray) : OutFrame

    /** Подтверждение уже пришло ([FrameCache.delivered]) — шифровать и слать нечего. */
    object Delivered : OutFrame
}

/** Строка кэша не расшифровалась: подмена, другой ключ или повреждение (ADR-63 п.5). */
internal class CorruptFrame : Exception("строка кэша кадров не расшифровалась")

/**
 * Кадры Signal, зашифрованные один раз (ревью v0.0.1-alpha, прогоны 9–10): исходящие
 * сообщения ([CT], адрес ‖ id → кадр, до подтверждения), подтверждения ([ACK], адрес ‖ id
 * подтверждаемого → кадр) и принятые кадры ([RX], SHA-256 кадра → id ‖ адрес) — чтобы узнать
 * повтор, который libsignal уже не откроет. Без Android и ядра: [encrypt] — сессия Signal.
 *
 * Шифрование и запись кадра — одна транзакция ([FrameKv.atomic]): кадр отдаётся только после
 * записи, а сбой записи откатывает и шаг цепочки — следующая попытка снова один шаг. Доставленное
 * сообщение оставляет в [CT] надгробие (пустую строку): если очередь отправки ещё не успела
 * сохранить подтверждение, сообщение не зашифруется второй раз.
 */
internal class FrameCache(
    private val kv: FrameKv,
    private val encrypt: (address: String, plain: ByteArray) -> ByteArray?,
) {
    /** Кадр сообщения [id]: сохранённый или зашифрованный сейчас (один раз) и сохранённый; `null` — не зашифровался. */
    fun outFrame(address: String, id: ByteArray, plain: ByteArray): OutFrame? {
        val f = once(CT, cacheKey(address, id)) { encrypt(address, plain) } ?: return null
        return if (f.isEmpty()) OutFrame.Delivered else OutFrame.Ready(f)
    }

    /** Кадр подтверждения [target]: сохранённый или зашифрованный сейчас (один раз) и сохранённый. */
    fun ackFrame(address: String, target: ByteArray, plain: () -> ByteArray): ByteArray? =
        once(ACK, cacheKey(address, target)) { encrypt(address, plain()) }

    /** Сообщение [id] доставлено: вместо кадра — надгробие. */
    fun delivered(address: String, id: ByteArray) = kv.put(CT, cacheKey(address, id), ByteArray(0))

    /** Полученный кадр [digest] — сообщение [id] от [address]. */
    fun remember(digest: ByteArray, address: String, id: ByteArray) =
        kv.put(RX, digest, id + address.toByteArray(Charsets.UTF_8))

    /** Кадр [digest] уже принимали: отправитель и id; иначе (или строка испорчена) — `null`. */
    fun replayed(digest: ByteArray): Pair<String, ByteArray>? {
        val v = try {
            kv.get(RX, digest)
        } catch (_: CorruptFrame) {
            null
        } ?: return null
        if (v.size <= ID_BYTES) return null
        return String(v, ID_BYTES, v.size - ID_BYTES, Charsets.UTF_8) to v.copyOfRange(0, ID_BYTES)
    }

    private fun once(table: String, key: ByteArray, make: () -> ByteArray?): ByteArray? {
        kv.get(table, key)?.let { return it }
        return kv.atomic {
            make()?.takeIf { it.isNotEmpty() }?.also { kv.put(table, key, it) }
        }
    }

    companion object {
        const val CT = "chat_ct"
        const val ACK = "chat_ack"
        const val RX = "chat_rx"
        const val ID_BYTES = 16

        fun cacheKey(address: String, id: ByteArray): ByteArray =
            address.toByteArray(Charsets.UTF_8) + '|'.code.toByte() + id

        fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    }
}

/**
 * Подтверждения к отправке в памяти: контакт → id полученных, без повторов, не больше [max]
 * на контакт (старые вытесняются). Только поток `sm-chat`.
 */
internal class AckQueue(private val max: Int) {
    private val acks = HashMap<String, ArrayDeque<ByteArray>>()

    fun add(address: String, id: ByteArray) {
        val q = acks.getOrPut(address) { ArrayDeque() }
        if (q.any { it.contentEquals(id) }) return
        if (q.size >= max) q.removeFirst()
        q.addLast(id)
    }

    /** Контакты, которым есть что подтвердить. */
    fun pending(): Set<String> = acks.filterValues { it.isNotEmpty() }.keys.toSet()

    /**
     * Кадры подтверждений для [address] — в [out], пока в нём меньше [limit]. Подтверждение
     * уходит из очереди только вместе с готовым кадром; [frame] вернул `null` — остаётся до
     * следующего раза; [CorruptFrame] — выбрасывается (заново не шифруем). Другое исключение
     * прерывает обход: готовые кадры уже в [out], остальные подтверждения — в очереди.
     */
    fun drain(address: String, out: MutableList<ByteArray>, limit: Int, frame: (ByteArray) -> ByteArray?) {
        val q = acks[address] ?: return
        val pending = q.iterator()
        while (pending.hasNext() && out.size < limit) {
            val target = pending.next()
            val f = try {
                frame(target)
            } catch (_: CorruptFrame) {
                pending.remove()
                continue
            } ?: continue
            out.add(f)
            pending.remove()
        }
        if (q.isEmpty()) acks.remove(address)
    }
}
