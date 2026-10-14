package dev.sm.messenger.chat

import dev.sm.messenger.diagnostics.Diagnostics
import dev.sm.messenger.diagnostics.DiagnosticEvent
import dev.sm.messenger.diagnostics.DiagnosticResult
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
import dev.sm.messenger.contacts.SignalGate
import dev.sm.messenger.contacts.SignalKv
import dev.sm.messenger.keys.DeviceKeys
import dev.sm.messenger.keys.KeysState
import dev.sm.messenger.relay.InviteLinks
import dev.sm.messenger.relay.PeerMailboxes
import dev.sm.messenger.relay.RelayAccount
import dev.sm.messenger.relay.RelayHub
import dev.sm.messenger.relay.RelayKv
import dev.sm.messenger.store.DeliveryStore
import dev.sm.messenger.store.SecureDb
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
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
import uniffi.sm_ffi.parseFrame

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
 * раз**, кадр сохраняется в `kv` `chat_ct` (адрес ‖ id, [FrameCache]) в той же транзакции, что и
 * шаг сессии, повторы шлют тот же кадр ([collect]); подтверждение `Delivered` заменяет его надгробием. Так повторы не двигают цепочку Signal: сколько
 * угодно попыток к поддельному или долго отсутствующему контакту не уведут её за окно
 * пропуска получателя (`MAX_FORWARD_JUMPS` = 25 000) — одно логическое сообщение = один шаг
 * (ревью v0.0.1-alpha, прогоны 9–10: счётчик шифрований обходился и терял подтверждения).
 *
 * Приём ([onFrame]): кадр расшифровывается сессией одного из контактов (рядом — первыми),
 * текст пишется в историю, SHA-256 кадра — в `chat_rx` (→ адрес и id) — всё одной транзакцией с
 * шагом сессии ([SignalGate.atomic]): сбой до записи откатывает и расшифровку, повтор кадра
 * откроется снова (ревью v0.0.1-alpha, п.14). Подтверждение `Delivered` ставится в память и уходит при ближайшем подключении к отправителю. Подтверждение тоже
 * шифруется один раз и хранится в `chat_ack` (адрес ‖ id). Пришёл повтор того же кадра (libsignal
 * его не откроет, цепочка не сдвигается) — по `chat_rx` снова ставим то же сохранённое
 * подтверждение, ничего не шифруя.
 *
 * Известные ограничения: сохранённые кадры устаревают при повторном сопряжении с контактом
 * (новая сессия их не откроет), если до доставки цепочка прошла больше 5 шагов храповика
 * (libsignal хранит только 5 прошлых цепочек получения, `MAX_RECEIVER_CHAINS`) или если после
 * него в той же цепочке доставлено больше 2000 сообщений (`MAX_MESSAGE_KEYS`). Строки `chat_rx`
 * и `chat_ack` пока не удаляются — по одной на полученное сообщение, как и история.
 *
 * Signal, Keystore и SQLite — только в потоке `sm-chat`; результат для Bluetooth — в главный.
 */
class ChatHub private constructor(private val app: Application) {
    init { Diagnostics.initialize(app) }
    private val db = SecureDb.get(app)
    val book = ContactBook(db)
    private val messages = MessageStore(db)

    /** Адреса почты контактов на узле (ADR-82). */
    val peers = PeerMailboxes(db)

    /** Своя почта на узле: запись узла, пропуск, свои очереди на контакт (ADR-134, ADR-136). */
    val account = RelayAccount(relayKv())

    /** Ссылки-приглашения: свои и открытые чужие (ADR-83). */
    val invites = InviteLinks(relayKv())
    private val delivery = DeliveryStore.get(app)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "sm-chat").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var sessions: SignalGate? = null

    /** Сессии Signal устройства — одни на процесс (у каждого объекта свой замок, два испортили бы сессию). */
    val signal: SignalGate? get() = sessions
    @Volatile private var ownKey: ByteArray? = null

    /** Свой открытый ключ личности (33 байта) или `null`, пока ключ устройства не открыт. */
    val ownIdentity: ByteArray? get() = ownKey

    /** Подтверждения к отправке (только поток `sm-chat`). */
    private val acks = AckQueue(MAX_ACKS)

    private val frames = FrameCache(
        object : FrameKv {
            override fun get(table: String, id: ByteArray): ByteArray? =
                try {
                    db.kvGet(table, id)
                } catch (_: SecureDb.CorruptRow) {
                    throw CorruptFrame()
                }

            override fun put(table: String, id: ByteArray, value: ByteArray) = db.kvPut(table, id, value)

            override fun <T : Any> atomic(block: () -> T?): T? =
                (sessions ?: throw SQLiteException("ключ устройства не открыт")).atomic(block)
        },
    ) { address, plain -> encrypt(address, plain) }

    /** История или статусы изменились — экраны перечитывают (меняется в главном потоке). */
    var revision by mutableIntStateOf(0)
        private set

    /** Контакты, которым есть что отправить: сообщения в очереди или подтверждения. */
    @Volatile var waiting: Set<String> = emptySet()
        private set

    /** CHAT-01: composition без RESUMED не подавляет уведомления и не отмечает прочтение. */
    internal val visibility = ChatVisibility()

    /** Кого будить, когда для контакта появилось что отправить (ставит [dev.sm.messenger.ble.BleHub]). */
    @Volatile var onWork: ((String) -> Unit)? = null

    /** То же для узла (ставит [dev.sm.messenger.relay.RelayHub]); вызывается в главном потоке. */
    @Volatile var onRelayWork: ((String) -> Unit)? = null

    private val notifier = Notifier(app)

    /** Ключ устройства открыт (экран после проверки ключей). */
    fun attach(identity: DeviceIdentity) {
        synchronized(this) {
            if (sessions != null) return
            sessions = SignalGate(SignalSessions(identity, SignalKv(db)), db)
            ownKey = identity.publicKey()
        }
        io.execute {
            recoverQueue()
            refreshWaiting()
        }
        RelayHub.get(app).start()
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

    /**
     * Отправить текст контакту: в историю и очередь; уйдёт при подключении. Строка истории и
     * запись журнала [SendEntry] — одна транзакция; запись удаляется, когда снимок очереди с
     * сообщением записан. Процесс погиб раньше — [recoverQueue] вернёт сообщение в очередь.
     */
    fun send(contact: Contact, text: String) = io.execute {
        val attempt = Diagnostics.begin(DiagnosticEvent.ChatQueue)
        var queueResult = DiagnosticResult.Failed
        val id = messageIdNew()
        val sentAt = System.currentTimeMillis()
        val ok = guard {
            val blob = chatEncode(ChatMessage(id, sentAt.toULong(), ChatBody.Text(text)))
            val written = db.atomic {
                if (!messages.addOutgoing(contact.rowId, contact.address, id, text, sentAt)) return@atomic false
                db.kvPut(SEND, id, SendEntry(id, contact.address, blob).encode())
                true
            } == true
            if (!written) return@guard false
            try {
                delivery.outbox.push(contactMailbox(contact.address).direct(), id.direct(), blob, delivery.now())
                if (delivery.saveOutboxNow()) db.kvDelete(SEND, id) else delivery.changed()
                queueResult = DiagnosticResult.Ok
            } catch (_: DeliveryFfiException) {
                queueResult = DiagnosticResult.Full
                // Очередь переполнена (1000 сообщений / 32 МиБ): сообщение сразу «не доставлено».
                messages.setState(contact.address, id, MessageState.Failed)
                db.kvDelete(SEND, id)
            }
            true
        } == true
        Diagnostics.record(DiagnosticEvent.ChatQueue, if (ok) queueResult else DiagnosticResult.Failed, attempt)
        bump()
        if (ok) {
            refreshWaiting()
            wake(contact.address)
        }
    }

    /**
     * При открытии ключа: снимок очереди пропал ([DeliveryStore.outboxLost]) — свои «в очереди»
     * становятся «не доставлено»; записи журнала отправки, которых нет в очереди, ставятся в неё
     * снова (и снова «в очереди»). Записи удаляются, когда снимок с ними записан. Уже
     * доставленное повторно не уйдёт: [FrameCache] хранит надгробие, [collect] его уберёт.
     */
    private fun recoverQueue() {
        guard {
            val queued = delivery.outbox.due(ULong.MAX_VALUE).map { SecureDb.hex(it.key.mailbox) + SecureDb.hex(it.key.messageId) }.toSet()
            val lost = delivery.outboxLost
            if (lost) messages.failQueued()
            val entries = db.kvAll(SEND).mapNotNull(SendEntry::decode)
            for (e in entries) {
                val box = contactMailbox(e.address)
                if (SecureDb.hex(box) + SecureDb.hex(e.id) in queued) continue
                try {
                    delivery.outbox.push(box.direct(), e.id.direct(), e.blob, delivery.now())
                    if (lost) messages.setState(e.address, e.id, MessageState.Queued)
                } catch (_: DeliveryFfiException) {
                    messages.setState(e.address, e.id, MessageState.Failed)
                }
            }
            if (entries.isNotEmpty() && delivery.saveOutboxNow()) entries.forEach { db.kvDelete(SEND, it.id) }
        }
        bump()
    }

    /**
     * Кадры для контакта [address], к которому только что подключились ([reconnect] — повторить
     * всё сразу) или подключены: подтверждения и сообщения, которым пора уйти, не больше
     * [MAX_FRAMES]. Каждое сообщение и подтверждение шифруется один раз ([FrameCache]),
     * повторы берут сохранённый кадр. Подтверждение уходит из очереди только вместе с готовым
     * кадром: при ошибке оно остаётся до следующего раза. [deliver] вызывается в главном потоке.
     */
    fun collect(address: String, reconnect: Boolean, deliver: (List<ByteArray>) -> Unit) = io.execute {
        val out = gather(address, reconnect).map { it.first }
        if (out.isNotEmpty()) main.post { deliver(out) }
    }

    /**
     * Для узла (поток [dev.sm.messenger.relay.RelayHub], не главный): те же кадры, что [collect],
     * но сразу. Кадр — «ящик + блоб», на узел уходит блоб.
     */
    fun relayFrames(address: String): List<Pair<ByteArray, ByteArray?>> =
        io.submit<List<Pair<ByteArray, ByteArray?>>> { gather(address, false) }.get()

    /** Узел принял кадр своего сообщения [id] (поток узла): для «Сведений о сообщении». */
    fun onRelaySent(address: String, id: ByteArray) = io.execute {
        if (guard { messages.markOnNode(address, id) } == true) bump()
    }

    /** Кадры для контакта и id своего сообщения в каждом (у подтверждений — `null`). */
    private fun gather(address: String, reconnect: Boolean, limit: Int = MAX_FRAMES): List<Pair<ByteArray, ByteArray?>> {
        if (sessions == null) {
            Diagnostics.record(DiagnosticEvent.ChatGather, DiagnosticResult.NoKey)
            return emptyList()
        }
        val out = ArrayList<ByteArray>()
        val ids = ArrayList<ByteArray?>()
        guard {
            val box = contactMailbox(address)
            // Испорченная строка подтверждения: не шифруем заново и не перезаписываем (ADR-63 п.5).
            acks.drain(address, out, limit) { target ->
                frames.ackFrame(address, target) {
                    chatEncode(ChatMessage(messageIdNew(), System.currentTimeMillis().toULong(), ChatBody.Delivered(target)))
                }
            }
            while (ids.size < out.size) ids.add(null)
            val now = delivery.now()
            if (reconnect) delivery.outbox.retryPeerNow(box.direct(), now)
            for (d in delivery.outbox.due(now)) {
                if (out.size >= limit) break
                if (!d.key.mailbox.contentEquals(box)) continue
                val id = d.key.messageId
                val f = try {
                    frames.outFrame(address, id, d.blob)
                } catch (_: CorruptFrame) {
                    // Сохранённый кадр испорчен: второе шифрование сдвинуло бы цепочку — сообщение «не доставлено».
                    runCatching { delivery.outbox.confirm(box.direct(), id.direct()) }
                    frames.delivered(address, id)
                    if (messages.setState(address, id, MessageState.Failed)) bump()
                    null
                }
                when (f) {
                    null -> Unit
                    // Подтверждение пришло, а очередь его не сохранила (сбой до снимка) — убрать без отправки.
                    OutFrame.Delivered -> runCatching { delivery.outbox.confirm(box.direct(), id.direct()) }
                    is OutFrame.Ready -> {
                        out.add(f.frame)
                        ids.add(id)
                        delivery.outbox.attempted(box.direct(), id.direct(), now)
                    }
                }
            }
            delivery.changed()
        }
        refreshWaiting()
        while (ids.size < out.size) ids.add(null)
        Diagnostics.record(DiagnosticEvent.ChatGather, if (out.isEmpty()) DiagnosticResult.Empty else DiagnosticResult.Ok, value = out.size)
        return out.zip(ids)
    }

    /**
     * Кадр по Bluetooth. [nearby] — контакты, чьи метки сейчас видны: их сессии пробуем первыми.
     * Место кадра в [InboundBudget] освобождается после обработки при любом исходе.
     */
    fun onFrame(blob: ByteArray, nearby: List<String>) = io.execute {
        try {
            Diagnostics.record(DiagnosticEvent.BleReceive)
            openFrame(blob, nearby, MessagePath.Ble)
        } finally {
            InboundBudget.release(blob.size)
        }
    }

    /**
     * Блоб из своей очереди на узле для контакта [address] (поток узла, не главный). `true` —
     * обработан (принят, повтор или не открылся) и его можно удалить с узла (`ACK`); `false` —
     * сбой хранилища или ключ не открыт, блоб остаётся на узле до следующего раза.
     */
    fun onRelayFrame(blob: ByteArray, address: String): Boolean =
        io.submit<Boolean> { openFrame(blob, listOf(address), MessagePath.Node) }.get()

    /**
     * Отправить контакту адрес своей почты (ADR-82 п.1): ID отправителя своей очереди и запись
     * своего узла — сообщением внутри Signal, через ту же очередь доставки, без строки в истории.
     * `false` — не поставлено в очередь.
     */
    fun sendMailbox(address: String, senderId: ByteArray, node: String): Boolean =
        queueMailbox(address, senderId, node) != null

    /** ADR-151: ID нужен только для ожидания подтверждения во время сопряжения. */
    internal fun queueMailbox(address: String, senderId: ByteArray, node: String): ByteArray? {
        val id = io.submit<ByteArray?> {
            guard {
                val id = messageIdNew()
                val blob = chatEncode(ChatMessage(id, System.currentTimeMillis().toULong(), ChatBody.Mailbox(senderId, node)))
                // Как и текст: служебное сообщение восстанавливается после гибели процесса.
                db.kvPut(SEND, id, SendEntry(id, address, blob).encode())
                try {
                    delivery.outbox.push(contactMailbox(address).direct(), id.direct(), blob, delivery.now())
                } catch (e: DeliveryFfiException) {
                    db.kvDelete(SEND, id)
                    throw e
                }
                if (delivery.saveOutboxNow()) db.kvDelete(SEND, id) else delivery.changed()
                refreshWaiting()
                id
            }
        }.get()
        if (id != null) wake(address)
        return id
    }

    /** Не больше очереди PairLink; те же сохранённые кадры и сроки повторов, что у двух транспортов. */
    internal fun pairFrames(address: String): List<ByteArray> =
        io.submit<List<ByteArray>> { gather(address, false, 8).map { it.first } }.get()

    internal fun mailboxDelivered(address: String, id: ByteArray): Boolean =
        io.submit<Boolean> { guard { frames.isDelivered(address, id) } == true }.get()

    /** Временный BLE-канал после сопряжения: только кадр и сессия выбранного сохранённого контакта. */
    internal fun onPairFrame(frame: ByteArray, address: String): Boolean = io.submit<Boolean> {
        val f = parseFrame(frame.direct()) ?: return@submit false
        if (f.mailbox.any { it != 0.toByte() }) return@submit false
        openFrame(f.blob, listOf(address), MessagePath.Ble, onlyNearby = true)
    }.get()

    private fun openFrame(blob: ByteArray, nearby: List<String>, via: MessagePath, onlyNearby: Boolean = false): Boolean {
        val s = sessions ?: return false.also { Diagnostics.record(DiagnosticEvent.ChatFrame, DiagnosticResult.NoKey) }
        val attempt = Diagnostics.begin(DiagnosticEvent.ChatFrame)
        val ok = guard {
            val contacts = book.list().let { all ->
                if (onlyNearby) all.filter { it.address in nearby } else all
            }
            val order = nearby.filter { n -> contacts.any { it.address == n } } +
                if (onlyNearby) emptyList() else contacts.map { it.address }.filter { it !in nearby }
            val digest = FrameCache.sha256(blob)
            // Расшифровка и записи о сообщении — одна транзакция (п.14): исключение — откат шага
            // сессии, кадр откроется при повторе. Подтверждения, очередь и экран — после записи.
            val got = s.atomic { receive(s, order, blob, digest, contacts, via) }
            if (got == null) {
                Diagnostics.record(DiagnosticEvent.ChatFrame, DiagnosticResult.Ignored, attempt)
                replayed(digest, contacts)
                return@guard true
            }
            val (c, m, fresh) = got
            val box = contactMailbox(c.address)
            when (val body = m.body) {
                is ChatBody.Text -> {
                    runCatching { delivery.seen.firstTime(box.direct(), m.id.direct()) }
                    delivery.changed()
                    ack(c.address, m.id)
                    if (fresh) {
                        bump()
                        if (!visibility.isVisible(c.address)) notifier.message(c, body.text)
                    }
                }
                is ChatBody.Delivered -> {
                    Diagnostics.record(DiagnosticEvent.ChatDelivered)
                    runCatching { delivery.outbox.confirm(box.direct(), body.target.direct()) }
                    delivery.changed()
                    if (fresh) bump()
                }
                is ChatBody.Mailbox, is ChatBody.Other -> ack(c.address, m.id)
            }
            true
        } == true
        Diagnostics.record(DiagnosticEvent.ChatFrame, if (ok) DiagnosticResult.Ok else DiagnosticResult.Failed, attempt)
        refreshWaiting()
        return ok
    }

    /**
     * Внутри транзакции [SignalGate.atomic]: открыть кадр и записать, что он значит. `null` — не
     * открылся (откат). Третье поле — изменилась ли история.
     */
    private fun receive(
        s: SignalGate,
        order: List<String>,
        blob: ByteArray,
        digest: ByteArray,
        contacts: List<Contact>,
        via: MessagePath,
    ): Triple<Contact, ChatMessage, Boolean>? {
        val opened = try {
            s.open(order, blob.direct())
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.ChatDecrypt, DiagnosticResult.Signal)
            null
        } ?: return null.also { Diagnostics.record(DiagnosticEvent.ChatDecrypt, DiagnosticResult.Failed) }
        Diagnostics.record(DiagnosticEvent.ChatDecrypt)
        val c = contacts.first { it.address == opened.contact }
        val m = opened.message
        val changed = when (val body = m.body) {
            is ChatBody.Text -> messages.addIncoming(c.rowId, c.address, m.id, body.text, m.sentAtMs.toLong()).also {
                frames.remember(digest, c.address, m.id)
            }
            // Контакт расшифровал наше сообщение — вместо сохранённого кадра надгробие.
            is ChatBody.Delivered -> {
                frames.delivered(c.address, body.target)
                messages.setState(c.address, body.target, MessageState.Delivered, via)
            }
            // Адрес почты контакта: запись узла без подписи владельца не принимается (ADR-81).
            is ChatBody.Mailbox -> {
                peers.accept(c.address, body.senderId, body.node, m.sentAtMs.toLong(), System.currentTimeMillis() / MS_PER_S)
                frames.remember(digest, c.address, m.id)
                false
            }
            is ChatBody.Other -> {
                frames.remember(digest, c.address, m.id)
                false
            }
        }
        return Triple(c, m, changed)
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

    /** Отметить успешно загруженную историю видимого чата. Не в главном потоке. */
    fun markRead(c: Contact, lastLoadedRow: Long) {
        if (!visibility.isVisible(c.address)) return
        val marked = guard {
            // При паузе Activity во время IO не гасим непрочитанное/уведомление в фоне.
            if (!visibility.isVisible(c.address)) return@guard false
            if (lastLoadedRow > readMark(c)) db.kvPut(READ, c.address.toByteArray(Charsets.UTF_8), ByteBuffer.allocate(Long.SIZE_BYTES).putLong(lastLoadedRow).array())
            true
        } == true
        if (marked && visibility.isVisible(c.address)) notifier.clear(c)
    }

    /**
     * Удалить контакт с этого телефона (макет `ui-v14.html`): сообщения контакта в очереди
     * доставки, история, сессия Signal, метка прочтения, журнал отправки, ссылки с ним, адрес
     * почты и свои очереди (строки). Историю, сессию и строки — одной транзакцией: не вышло —
     * ничего из них не стёрто. [done] — в главном потоке: ID очередей на своём узле, которые
     * надо закрыть (сеть здесь не нужна), или `null` — не удалось.
     */
    fun removeContact(c: Contact, done: (List<ByteArray>?) -> Unit) = io.execute {
        val queues = guard { dropContact(c) }
        bump()
        if (queues != null) refreshWaiting()
        main.post { done(queues) }
    }

    private fun dropContact(c: Contact): List<ByteArray> {
        val box = contactMailbox(c.address)
        delivery.outbox.due(ULong.MAX_VALUE)
            .filter { it.key.mailbox.contentEquals(box) }
            .forEach { delivery.outbox.confirm(box.direct(), it.key.messageId.direct()) }
        if (!delivery.saveOutboxNow()) delivery.changed()
        val queues = account.own.get(c.address).mapTo(ArrayList<ByteArray>()) { it.recipientId }
        db.atomic {
            sessions?.forget(c.address)
            messages.deleteContact(c.rowId)
            db.kvDelete(READ, c.address.toByteArray(Charsets.UTF_8))
            db.kvAll(SEND).mapNotNull(SendEntry::decode).filter { it.address == c.address }.forEach { db.kvDelete(SEND, it.id) }
            invites.ins().filter { it.inviter == c.address }.forEach {
                queues.add(it.queue.recipientId)
                invites.removeIn(it.inviter)
            }
            invites.outs().filter { it.contact == c.address }.forEach {
                queues.add(it.recipientId)
                invites.removeOut(it.secret)
            }
            book.delete(c.rowId)
            true
        } ?: throw SQLiteException("контакт не удалён")
        runCatching { delivery.seen.forget(box.direct()) }
        acks.remove(c.address)
        notifier.clear(c)
        return queues
    }

    private fun readMark(c: Contact): Long =
        db.kvGet(READ, c.address.toByteArray(Charsets.UTF_8))?.takeIf { it.size == Long.SIZE_BYTES }?.let { ByteBuffer.wrap(it).long } ?: 0L

    private fun ack(address: String, id: ByteArray) {
        Diagnostics.record(DiagnosticEvent.ChatAck)
        acks.add(address, id)
        wake(address)
    }

    private fun encrypt(address: String, plain: ByteArray): ByteArray? {
        val s = sessions ?: return null
        val attempt = Diagnostics.begin(DiagnosticEvent.ChatEncrypt)
        return try {
            encodeFrame(BLE_MAILBOX.direct(), s.encrypt(address, plain.direct()).direct()).also { Diagnostics.record(DiagnosticEvent.ChatEncrypt, attempt = attempt) }
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.ChatEncrypt, DiagnosticResult.Signal, attempt)
            null
        }
    }

    /**
     * Кадр не открылся. Если это повтор уже принятого — отправитель не получил подтверждение:
     * ставим в очередь то же сохранённое, ничего не расшифровывая и не шифруя.
     */
    private fun replayed(digest: ByteArray, contacts: List<Contact>) {
        val (address, id) = frames.replayed(digest) ?: return
        Diagnostics.record(DiagnosticEvent.ChatFrame, DiagnosticResult.Duplicate)
        if (contacts.any { it.address == address }) ack(address, id)
    }

    private fun refreshWaiting() {
        val queued = guard {
            delivery.outbox.due(ULong.MAX_VALUE).map { addressOf(it.key.mailbox) }.toSet()
        } ?: emptySet()
        waiting = queued + acks.pending()
    }

    private fun wake(address: String) {
        main.post {
            onWork?.invoke(address)
            onRelayWork?.invoke(address)
        }
    }

    private fun relayKv() = object : RelayKv {
        // Испорченная строка — исключение, а не «нет строки»: секрет не пересоздаётся молча.
        override fun get(table: String, id: ByteArray): ByteArray? = db.kvGet(table, id)

        override fun put(table: String, id: ByteArray, value: ByteArray) = db.kvPut(table, id, value)

        override fun delete(table: String, id: ByteArray) = db.kvDelete(table, id)
    }

    private fun bump() {
        main.post { revision++ }
    }

    /** Ошибки хранилища и ядра — отказ операции без падения процесса (ADR-63 п.5). */
    private inline fun <T> guard(block: () -> T): T? =
        try {
            block()
        } catch (_: SQLiteException) {
            Diagnostics.record(DiagnosticEvent.ChatStore, DiagnosticResult.Storage)
            null
        } catch (_: GeneralSecurityException) {
            Diagnostics.record(DiagnosticEvent.ChatStore, DiagnosticResult.Keystore)
            null
        } catch (_: SecureDb.CorruptRow) {
            Diagnostics.record(DiagnosticEvent.ChatStore, DiagnosticResult.Corrupt)
            null
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.ChatStore, DiagnosticResult.Signal)
            null
        } catch (_: DeliveryFfiException) {
            Diagnostics.record(DiagnosticEvent.ChatStore, DiagnosticResult.Delivery)
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
        private const val SEND = "chat_send"
        private const val MAX_FRAMES = 32
        private const val MAX_ACKS = 256
        private const val MS_PER_S = 1000L
    }
}
