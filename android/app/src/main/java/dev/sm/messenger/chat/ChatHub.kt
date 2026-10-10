package dev.sm.messenger.chat

import android.app.Application
import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.sm.messenger.ble.InboundBudget
import dev.sm.messenger.ble.direct
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.contacts.ContactBook
import dev.sm.messenger.contacts.SignalKv
import dev.sm.messenger.keys.DeviceKeys
import dev.sm.messenger.keys.KeysState
import dev.sm.messenger.store.DeliveryStore
import dev.sm.messenger.store.SecureDb
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.concurrent.Executors
import uniffi.sm_ffi.ChatBody
import uniffi.sm_ffi.ChatMessage
import uniffi.sm_ffi.DeliveryFfiException
import uniffi.sm_ffi.DeviceIdentity
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.SignalSessions
import uniffi.sm_ffi.chatEncode
import uniffi.sm_ffi.contactMailbox
import uniffi.sm_ffi.encodeFrame
import uniffi.sm_ffi.messageIdNew

/** Строка списка чатов. */
class ChatSummary(val contact: Contact, val last: ChatLine?, val unread: Int)

/**
 * Сообщения 1:1 (пункт 6 плана, ADR-2, ADR-40): история, очередь, шифрование и подтверждения.
 * Один на процесс ([get]) — работает и при закрытом приложении, пока жива служба связи.
 *
 * Срока хранения нет (UX-29): сообщение ждёт в очереди, пока не доставлено (или пока
 * пользователь его не удалит — позже); `overdue` ядра — для «Ещё не доставлено» (позже).
 *
 * Отправка: текст → история («в очереди») → `OutboxQueue` хранит **выровненное открытое**
 * сообщение (снимок на диске — под `sm.store`) → при первой попытке шифруется Signal **один
 * раз**, кадр сохраняется в `kv` [CT] (адрес ‖ id), повторы шлют тот же кадр ([collect]);
 * подтверждение `Delivered` удаляет его. Так повторы не двигают цепочку Signal: сколько
 * угодно попыток к поддельному или долго отсутствующему контакту не уведут её за окно
 * пропуска получателя (`MAX_FORWARD_JUMPS` = 25 000) — одно логическое сообщение = один шаг
 * (ревью v0.0.1-alpha, прогоны 9–10: счётчик шифрований обходился и терял подтверждения).
 *
 * Приём ([onFrame]): кадр расшифровывается сессией одного из контактов (рядом — первыми),
 * текст пишется в историю, SHA-256 кадра — в [RX] (→ адрес и id), подтверждение `Delivered`
 * ставится в память и уходит при ближайшем подключении к отправителю. Подтверждение тоже
 * шифруется один раз и хранится в [ACK] (адрес ‖ id). Пришёл повтор того же кадра (libsignal
 * его не откроет, цепочка не сдвигается) — по [RX] снова ставим то же сохранённое
 * подтверждение, ничего не шифруя.
 *
 * Известные ограничения: сохранённые кадры устаревают при повторном сопряжении с контактом
 * (новая сессия их не откроет), если до доставки цепочка прошла больше 5 шагов храповика
 * (libsignal хранит только 5 прошлых цепочек получения, `MAX_RECEIVER_CHAINS`) или если после
 * него в той же цепочке доставлено больше 2000 сообщений (`MAX_MESSAGE_KEYS`). Строки [RX] и
 * [ACK] пока не удаляются — по одной на полученное сообщение, как и история.
 *
 * Signal, Keystore и SQLite — только в потоке `sm-chat`; результат для Bluetooth — в главный.
 */
class ChatHub private constructor(private val app: Application) {
    private val db = SecureDb.get(app)
    val book = ContactBook(db)
    private val messages = MessageStore(db)
    private val delivery = DeliveryStore.get(app)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sm-chat").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var sessions: SignalSessions? = null

    /** Сессии Signal устройства — одни на процесс (свой замок у каждого объекта, два испортили бы сессию). */
    val signal: SignalSessions? get() = sessions
    @Volatile private var ownKey: ByteArray? = null

    /** Подтверждения к отправке: контакт → id полученных (только поток `sm-chat`). */
    private val acks = HashMap<String, ArrayDeque<ByteArray>>()

    /** История или статусы изменились — экраны перечитывают (меняется в главном потоке). */
    var revision by mutableIntStateOf(0)
        private set

    /** Контакты, которым есть что отправить: сообщения в очереди или подтверждения. */
    @Volatile var waiting: Set<String> = emptySet()
        private set

    /** Открытый сейчас чат — по нему не показываем уведомление. */
    @Volatile var visibleChat: String? = null

    /** Кого будить, когда для контакта появилось что отправить (ставит [dev.sm.messenger.ble.BleHub]). */
    @Volatile var onWork: ((String) -> Unit)? = null

    private val notifier = Notifier(app)

    /** Ключ устройства открыт (экран после проверки ключей). */
    fun attach(identity: DeviceIdentity) {
        synchronized(this) {
            if (sessions != null) return
            sessions = SignalSessions(identity, SignalKv(db))
            ownKey = identity.publicKey()
        }
        io.execute { refreshWaiting() }
    }

    /** Для фоновой службы: ключ ещё не открыт — открыть самим. `false` — ключа нет или он сломан. */
    fun ensureKeys(): Boolean {
        if (sessions != null) return true
        val k = DeviceKeys(app).load()
        if (k !is KeysState.Ready) return false
        attach(k.identity)
        return true
    }

    /** Код безопасности с контактом (60 цифр) или `null`. */
    fun safetyNumber(c: Contact): String? {
        val own = ownKey ?: return null
        return try {
            uniffi.sm_ffi.safetyNumber(own.direct(), c.identityKey.direct())
        } catch (_: SignalFfiException) {
            null
        }
    }

    /** Отправить текст контакту: в историю и очередь; уйдёт при подключении. */
    fun send(contact: Contact, text: String) = io.execute {
        val id = messageIdNew()
        val sentAt = System.currentTimeMillis()
        val ok = guard {
            val blob = chatEncode(ChatMessage(id, sentAt.toULong(), ChatBody.Text(text)))
            if (!messages.addOutgoing(contact.rowId, contact.address, id, text, sentAt)) return@guard false
            try {
                delivery.outbox.push(contactMailbox(contact.address).direct(), id.direct(), blob, delivery.now())
                delivery.changed()
            } catch (_: DeliveryFfiException) {
                // Очередь переполнена (1000 сообщений / 32 МиБ): сообщение сразу «не доставлено».
                messages.setState(contact.address, id, MessageState.Failed)
            }
            true
        } == true
        bump()
        if (ok) {
            refreshWaiting()
            wake(contact.address)
        }
    }

    /**
     * Кадры для контакта [address], к которому только что подключились ([reconnect] — повторить
     * всё сразу) или подключены: подтверждения и сообщения, которым пора уйти, не больше
     * [MAX_FRAMES]. Каждое сообщение и подтверждение шифруется один раз ([outFrame], [ackFrame]),
     * повторы берут сохранённый кадр. Подтверждение уходит из очереди только вместе с готовым
     * кадром: при ошибке оно остаётся до следующего раза. [deliver] вызывается в главном потоке.
     */
    fun collect(address: String, reconnect: Boolean, deliver: (List<ByteArray>) -> Unit) = io.execute {
        val s = sessions ?: return@execute
        val out = ArrayList<ByteArray>()
        guard {
            val box = contactMailbox(address)
            acks[address]?.let { q ->
                val pending = q.iterator()
                while (pending.hasNext() && out.size < MAX_FRAMES) {
                    val target = pending.next()
                    val f = try {
                        ackFrame(s, address, target)
                    } catch (_: SecureDb.CorruptRow) {
                        // Строка подменена или испорчена: не шифруем заново и не перезаписываем (ADR-63 п.5).
                        pending.remove()
                        continue
                    } ?: continue
                    out.add(f)
                    pending.remove()
                }
                if (q.isEmpty()) acks.remove(address)
            }
            val now = delivery.now()
            if (reconnect) delivery.outbox.retryPeerNow(box.direct(), now)
            for (d in delivery.outbox.due(now)) {
                if (out.size >= MAX_FRAMES) break
                if (!d.key.mailbox.contentEquals(box)) continue
                val id = d.key.messageId
                val f = try {
                    outFrame(s, address, id, d.blob)
                } catch (_: SecureDb.CorruptRow) {
                    // Сохранённый кадр испорчен: второе шифрование сдвинуло бы цепочку — сообщение «не доставлено».
                    runCatching { delivery.outbox.confirm(box.direct(), id.direct()) }
                    db.kvDelete(CT, cacheKey(address, id))
                    if (messages.setState(address, id, MessageState.Failed)) bump()
                    null
                } ?: continue
                out.add(f)
                delivery.outbox.attempted(box.direct(), id.direct(), now)
            }
            delivery.changed()
        }
        refreshWaiting()
        if (out.isNotEmpty()) main.post { deliver(out) }
    }

    /**
     * Кадр по Bluetooth. [nearby] — контакты, чьи метки сейчас видны: их сессии пробуем первыми.
     * Место кадра в [InboundBudget] освобождается после обработки при любом исходе.
     */
    fun onFrame(blob: ByteArray, nearby: List<String>) = io.execute {
        try {
            openFrame(blob, nearby)
        } finally {
            InboundBudget.release(blob.size)
        }
    }

    private fun openFrame(blob: ByteArray, nearby: List<String>) {
        val s = sessions ?: return
        guard {
            val contacts = book.list()
            val order = nearby.filter { n -> contacts.any { it.address == n } } +
                contacts.map { it.address }.filter { it !in nearby }
            val digest = sha256(blob)
            val opened = try {
                s.open(order, blob.direct())
            } catch (_: SignalFfiException) {
                null
            }
            if (opened == null) {
                replayed(digest, contacts)
                return@guard
            }
            val c = contacts.first { it.address == opened.contact }
            val box = contactMailbox(c.address)
            val m = opened.message
            when (val body = m.body) {
                is ChatBody.Text -> {
                    val fresh = messages.addIncoming(c.rowId, c.address, m.id, body.text, m.sentAtMs.toLong())
                    runCatching { delivery.seen.firstTime(box.direct(), m.id.direct()) }
                    delivery.changed()
                    ack(c.address, m.id)
                    remember(digest, c.address, m.id)
                    if (fresh) {
                        bump()
                        if (visibleChat != c.address) notifier.message(c, body.text)
                    }
                }
                is ChatBody.Delivered -> {
                    // Контакт расшифровал наше сообщение — сохранённый кадр больше не нужен.
                    runCatching { delivery.outbox.confirm(box.direct(), body.target.direct()) }
                    db.kvDelete(CT, cacheKey(c.address, body.target))
                    delivery.changed()
                    if (messages.setState(c.address, body.target, MessageState.Delivered)) bump()
                }
                is ChatBody.Other -> {
                    ack(c.address, m.id)
                    remember(digest, c.address, m.id)
                }
            }
        }
        refreshWaiting()
    }

    /** Контакты изменились (добавлен, сверен) — экраны перечитывают. */
    fun touch() = bump()

    /** Список чатов: контакты, последнее сообщение, непрочитанные. Не в главном потоке. */
    fun summaries(): List<ChatSummary>? = guard {
        book.list().map { c ->
            ChatSummary(c, messages.last(c.rowId), messages.unread(c.rowId, readMark(c)))
        }.sortedByDescending { it.last?.rowId ?: 0L }
    }

    /** История чата и сколько строк не открылось. Не в главном потоке. */
    fun history(c: Contact): Pair<List<ChatLine>, Int>? = guard { messages.history(c.rowId) }

    /** Отметить прочитанным всё, что есть в чате. Не в главном потоке. */
    fun markRead(c: Contact) {
        guard {
            val last = messages.lastRowId(c.rowId)
            if (last > readMark(c)) db.kvPut(READ, c.address.toByteArray(Charsets.UTF_8), ByteBuffer.allocate(Long.SIZE_BYTES).putLong(last).array())
        }
        notifier.clear(c)
    }

    private fun readMark(c: Contact): Long =
        db.kvGet(READ, c.address.toByteArray(Charsets.UTF_8))?.takeIf { it.size == Long.SIZE_BYTES }?.let { ByteBuffer.wrap(it).long } ?: 0L

    private fun ack(address: String, id: ByteArray) {
        val q = acks.getOrPut(address) { ArrayDeque() }
        if (q.none { it.contentEquals(id) }) {
            if (q.size >= MAX_ACKS) q.removeFirst()
            q.addLast(id)
        }
        wake(address)
    }

    /** Кадр сообщения [id]: сохранённый в [CT] или зашифрованный сейчас (один раз) и сохранённый. */
    private fun outFrame(s: SignalSessions, address: String, id: ByteArray, plain: ByteArray): ByteArray? {
        val key = cacheKey(address, id)
        db.kvGet(CT, key)?.let { return it }
        val f = encrypt(s, address, plain) ?: return null
        db.kvPut(CT, key, f)
        return f
    }

    /** Кадр подтверждения [target]: сохранённый в [ACK] или зашифрованный сейчас (один раз) и сохранённый. */
    private fun ackFrame(s: SignalSessions, address: String, target: ByteArray): ByteArray? {
        val key = cacheKey(address, target)
        db.kvGet(ACK, key)?.let { return it }
        val plain = chatEncode(ChatMessage(messageIdNew(), System.currentTimeMillis().toULong(), ChatBody.Delivered(target)))
        val f = encrypt(s, address, plain) ?: return null
        db.kvPut(ACK, key, f)
        return f
    }

    private fun encrypt(s: SignalSessions, address: String, plain: ByteArray): ByteArray? =
        try {
            encodeFrame(BLE_MAILBOX.direct(), s.encrypt(address, plain.direct()).direct())
        } catch (_: SignalFfiException) {
            null
        }

    /** Полученный кадр [digest] — сообщение [id] от [address]: повтор узнаем без расшифровки. */
    private fun remember(digest: ByteArray, address: String, id: ByteArray) {
        db.kvPut(RX, digest, id + address.toByteArray(Charsets.UTF_8))
    }

    /**
     * Кадр не открылся. Если это повтор уже принятого ([RX]) — отправитель не получил
     * подтверждение: ставим в очередь то же сохранённое, ничего не расшифровывая и не шифруя.
     */
    private fun replayed(digest: ByteArray, contacts: List<Contact>) {
        val v = try {
            db.kvGet(RX, digest)
        } catch (_: SecureDb.CorruptRow) {
            null
        } ?: return
        if (v.size <= ID_BYTES) return
        val address = String(v, ID_BYTES, v.size - ID_BYTES, Charsets.UTF_8)
        if (contacts.none { it.address == address }) return
        ack(address, v.copyOfRange(0, ID_BYTES))
    }

    private fun refreshWaiting() {
        val queued = guard {
            delivery.outbox.due(ULong.MAX_VALUE).map { addressOf(it.key.mailbox) }.toSet()
        } ?: emptySet()
        waiting = queued + acks.filterValues { it.isNotEmpty() }.keys
    }

    private fun wake(address: String) {
        main.post { onWork?.invoke(address) }
    }

    private fun bump() {
        main.post { revision++ }
    }

    /** Ошибки хранилища и ядра — отказ операции без падения процесса (ADR-63 п.5). */
    private inline fun <T> guard(block: () -> T): T? =
        try {
            block()
        } catch (_: SQLiteException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: SecureDb.CorruptRow) {
            null
        } catch (_: SignalFfiException) {
            null
        } catch (_: DeliveryFfiException) {
            null
        }

    companion object {
        @Volatile private var instance: ChatHub? = null

        fun get(app: Application): ChatHub =
            instance ?: synchronized(this) { instance ?: ChatHub(app).also { instance = it } }

        /** Адрес контакта из ящика: ключ личности = `05` + 32 байта. */
        fun addressOf(mailbox: ByteArray): String = "05" + SecureDb.hex(mailbox)

        /** Поле ящика в кадре Bluetooth: узла нет, получатель — тот, к кому подключились. */
        private val BLE_MAILBOX = ByteArray(32)
        private const val READ = "chat_read"

        /** Кадры исходящих сообщений до доставки: адрес ‖ id → кадр. */
        private const val CT = "chat_ct"

        /** Кадры подтверждений: адрес ‖ id подтверждаемого → кадр. */
        private const val ACK = "chat_ack"

        /** Принятые кадры: SHA-256 кадра → id ‖ адрес отправителя. */
        private const val RX = "chat_rx"
        private const val ID_BYTES = 16
        private const val MAX_FRAMES = 32
        private const val MAX_ACKS = 256

        private fun cacheKey(address: String, id: ByteArray): ByteArray =
            address.toByteArray(Charsets.UTF_8) + '|'.code.toByte() + id

        private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    }
}
