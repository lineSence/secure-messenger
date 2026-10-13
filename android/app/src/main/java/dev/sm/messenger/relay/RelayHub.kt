package dev.sm.messenger.relay

import dev.sm.messenger.diagnostics.Diagnostics
import dev.sm.messenger.diagnostics.DiagnosticEvent
import dev.sm.messenger.diagnostics.DiagnosticResult
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.sm.messenger.ble.direct
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.store.SecureDb
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import uniffi.sm_ffi.NodeFfiException
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.nodeRecordOpen
import uniffi.sm_ffi.parseFrame

/**
 * Почта через узел в приложении (п.11 плана 0.0.2-alpha; ADR-82, ADR-85, ADR-134). Один на процесс,
 * запускается, когда открыт ключ устройства ([ChatHub.attach]). Два потока:
 *
 * - приём (`sm-relay-in`): на каждого контакта своя очередь на своём узле ([OwnQueues]); нет
 *   очереди текущего периода (ADR-137) — `NEW` по пропуску и сообщение «адрес почты» контакту.
 *   По Wi-Fi — `WAIT` (узел держит ответ до 25 с), по мобильной сети — `FETCH` с опросом
 *   2→15 мин ([RelaySchedule]). Блоб удаляется с узла (`ACK`) только после того, как
 *   [ChatHub.onRelayFrame] его обработал. `NO_QUEUE` (узел перезапускался) — `NEW` с тем же ID
 *   получателя: ID отправителя тот же, контакту ничего не шлём (ADR-134). Очереди
 *   ссылок-приглашений ([InviteFlow]) читаются в том же запросе, первыми;
 * - отправка (`sm-relay-out`): контакт не рядом по Bluetooth и известен его адрес почты
 *   ([PeerMailboxes]) — те же кадры, что ушли бы по Bluetooth ([ChatHub.relayFrames]), кладутся
 *   в его очередь на его узле. Повторы и подтверждения — по очереди доставки (ADR-40).
 *
 * Ошибки сети и хранилища не роняют процесс: пауза по [RelaySchedule.retryDelayMs] и снова.
 *
 * Приложение на экране (ADR-144): и на мобильной сети приём — `WAIT`, как по Wi-Fi; свёрнуто — опрос
 * ADR-85. Возврат на экран будит поток приёма сразу.
 *
 * Смена сети (ADR-138): колбэк сети по умолчанию; новая сеть — через 3–5 с (против «мигания»)
 * замена канала ([HttpsRelay.renewChannel]), сброс пауз и сразу шаг приёма в режиме этой сети;
 * повторная смена за это время отменяет прежнюю замену — не больше одной за раз. Сети нет —
 * потоки ждут следующую, без повторов в пустоту.
 */
class RelayHub private constructor(private val app: Application) {
    private val chat = ChatHub.get(app)
    private val random = SecureRandom()
    private val schedule = RelaySchedule { random.nextDouble() }
    private val clients = HashMap<String, HttpsRelay>()
    private val outgoing = LinkedBlockingQueue<String>()
    private val preparing = PrepareSlots(4)
    private val fair = FairQueues()
    private val prepareFair = FairQueues()
    private val senderSchedule = RelaySchedule { random.nextDouble() }
    private class Cleanup(val owner: String, val relay: HttpsRelay, val recipientId: ByteArray)
    private val cleanup = java.util.concurrent.ConcurrentLinkedQueue<Cleanup>()
    @Volatile private var channelUntil = 0L
    private val main = Handler(Looper.getMainLooper())
    private val switchToken = Any()

    /** Будильник пауз потока приёма: смена сети прерывает ожидание. */
    private val receiverWake = RelayWake()
    private val senderWake = RelayWake()

    @Volatile private var started = false

    /** Сколько окон приложения сейчас на экране (ADR-144). */
    private val resumed = AtomicInteger(0)

    /** Связь с узлом для экрана «Узел»: обновляет поток приёма. */
    @Volatile var link: NodeLink = NodeLink.None
        private set

    /** Ссылки-приглашения: их очереди читает поток приёма (ADR-83). */
    val invites = InviteFlow(app, chat) { text, now -> client(text, now) }

    /** Удаление контакта: телефон и закрытие очередей на своём узле (макет `ui-v14.html`). */
    val removal = ContactRemoval(chat) { text, now -> client(text, now) }

    /** Очередь в одном `WAIT`/`FETCH`: контакта ([address], [own]) или ссылки ([invite]). */
    private class Box(
        val recipientId: ByteArray,
        val address: String? = null,
        val own: OwnQueue? = null,
        val invite: InviteBox? = null,
        val contact: Contact? = null,
    )

    /** Запустить потоки приёма и отправки; повторный вызов ничего не делает. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        chat.onRelayWork = { outgoing.offer(it); senderWake.signal() }
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    resumed.incrementAndGet()
                    wake()
                }

                override fun onActivityPaused(activity: Activity) {
                    if (resumed.decrementAndGet() < 0) resumed.set(0)
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

                override fun onActivityStarted(activity: Activity) = Unit

                override fun onActivityStopped(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()

                override fun onLost(network: Network) = Unit
            },
        )
        thread(name = "sm-relay-in", isDaemon = true) { loop(::receiveOnce, receive = true) }
        thread(name = "sm-relay-out", isDaemon = true) { loop(::sendOnce) }
    }

    /** [step] возвращает паузу до следующего шага, мс; сбой — пауза с удвоением. */
    private fun loop(step: () -> Long, receive: Boolean = false) {
        while (true) {
            var failed = false
            val epoch = (if (receive) receiverWake else senderWake).epoch()
            val pause = try {
                step()
            } catch (_: RelayException) {
                Diagnostics.record(DiagnosticEvent.RelayLoop, DiagnosticResult.NoNetwork)
                failed = true
                retryDelay(receive)
            } catch (_: SQLiteException) {
                Diagnostics.record(DiagnosticEvent.RelayLoop, DiagnosticResult.Storage)
                failed = true
                retryDelay(receive)
            } catch (_: GeneralSecurityException) {
                Diagnostics.record(DiagnosticEvent.RelayLoop, DiagnosticResult.Keystore)
                failed = true
                retryDelay(receive)
            } catch (_: SecureDb.CorruptRow) {
                Diagnostics.record(DiagnosticEvent.RelayLoop, DiagnosticResult.Corrupt)
                failed = true
                retryDelay(receive)
            } catch (_: SignalFfiException) {
                Diagnostics.record(DiagnosticEvent.RelayLoop, DiagnosticResult.Signal)
                failed = true
                retryDelay(receive)
            } catch (_: ExecutionException) {
                Diagnostics.record(DiagnosticEvent.RelayLoop, DiagnosticResult.Worker)
                // Сбой внутри потока ChatHub, который guard не ловит: не роняем поток узла.
                failed = true
                retryDelay(receive)
            }
            if (pause > 0) {
                val wake = if (receive) receiverWake else senderWake
                wake.await(pause, failed, epoch, network() == Net.None)
            }
        }
    }

    /** Новая сеть по умолчанию (главный поток): замена канала через 3–5 с, прежняя отменяется. */
    private fun onNetworkChanged() {
        Diagnostics.record(DiagnosticEvent.NetworkChange, when (network()) { Net.Wifi -> DiagnosticResult.Wifi; Net.None -> DiagnosticResult.Offline; else -> DiagnosticResult.Mobile })
        main.removeCallbacksAndMessages(switchToken)
        val delay = SWITCH_MIN_MS + (random.nextDouble() * (SWITCH_MAX_MS - SWITCH_MIN_MS)).toLong()
        main.postAtTime(::switchChannel, switchToken, SystemClock.uptimeMillis() + delay)
    }

    private fun switchChannel() {
        synchronized(clients) { clients.values.forEach { it.renewChannel() } }
        synchronized(schedule) {
            schedule.onSuccess()
            schedule.onMail()
        }
        synchronized(senderSchedule) { senderSchedule.onSuccess() }
        channelUntil = 0L
        receiverWake.signal(reset = true)
        senderWake.signal(reset = true)
    }

    /** Принята новая запись узла или пропуск: не ждать конца паузы. */
    fun wake() {
        receiverWake.signal()
        senderWake.signal()
    }

    /**
     * Приложение на экране: окно возобновлено (счётчик с момента запуска потоков) или процесс
     * сейчас «на переднем плане» по оценке Android — на случай, когда окно открылось раньше запуска.
     */
    private fun onScreen(): Boolean {
        if (resumed.get() > 0) return true
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    private fun retryDelay(receive: Boolean = true): Long {
        val policy = if (receive) schedule else senderSchedule
        return synchronized(policy) { policy.retryDelayMs() }
    }

    private fun receiveOnce(): Long {
        val now = nowSecs()
        val net = network()
        val nodeText = chat.account.nodeText()
        val relay = nodeText?.let { client(it, now) }
        if (net == Net.None || nodeText == null || relay == null) {
            Diagnostics.record(DiagnosticEvent.RelayReceive, if (net == Net.None) DiagnosticResult.NoNetwork else DiagnosticResult.NoNode)
            link = if (relay == null) NodeLink.None else NodeLink.Offline
            return IDLE_MS
        }
        if (link == NodeLink.None || link == NodeLink.Offline) link = NodeLink.Connecting
        val long = waitsLong(net == Net.Wifi, onScreen())
        renewIfDue(relay, long)
        retryCleanup()
        val invited = invites.boxes().map { Box(it.recipientId, invite = it) }
        val contacts = ensureQueues(relay, nodeText, now).map { (a, q) -> Box(q.recipientId, a.address, q, contact = a) }
        val all = invited + contacts
        val boxes = fair.window(all, MAX_QUEUES)
        if (boxes.isEmpty()) {
            link = NodeLink.Idle
            return IDLE_MS
        }
        val got = try {
            relay.receive(boxes.map { it.recipientId }, wait = long)
        } catch (e: RelayException) {
            fair.advance(all.size, if (e.queue in boxes.indices) e.queue else 0)
            if (e.failure != RelayFailure.NoQueue || e.queue !in boxes.indices) {
                link = NodeLink.Down
                throw e
            }
            val b = boxes[e.queue]
            if (b.contact != null && b.own != null) {
                recreate(relay, b.contact, b.own, nodeText, now)
            } else {
                // Очередь ссылки: тот же ID получателя — тот же ID отправителя, что в ссылке.
                chat.account.pass(now)?.let { relay.newQueue(b.recipientId, it) }
            }
            return 0
        }
        fair.advance(all.size, got?.queue ?: (boxes.size - 1))
        synchronized(schedule) { schedule.onSuccess() }
        link = NodeLink.Ok
        if (got == null) {
            if (long) return 0
            if (all.size > boxes.size) return IDLE_MS
            return synchronized(schedule) {
                schedule.onQuiet()
                schedule.pollDelayMs()
            }
        }
        val b = boxes[got.queue]
        Diagnostics.record(DiagnosticEvent.RelayReceive)
        val handled = when {
            got.blob.isEmpty() -> true // sequence was validated; safely discard poison head
            b.invite != null -> invites.onFrame(b.invite, got.blob)
            b.address != null -> chat.onRelayFrame(got.blob, b.address)
            else -> true
        }
        if (!handled) return retryDelay().also { Diagnostics.record(DiagnosticEvent.RelayReceive, DiagnosticResult.Failed) }
        relay.ack(b.recipientId, got.seq)
        synchronized(schedule) { schedule.onMail() }
        return 0
    }

    /**
     * Свои очереди всех контактов, текущего периода первыми. Нет очереди текущего периода —
     * создать (`NEW` по пропуску) и отправить контакту «адрес почты»; запись — только после того,
     * как сообщение поставлено в очередь доставки, иначе при следующем шаге — снова.
     */
    private fun ensureQueues(relay: HttpsRelay, nodeText: String, now: Long): List<Pair<Contact, OwnQueue>> {
        val out = ArrayList<Pair<Contact, OwnQueue>>()
        val contacts = chat.book.list()
        // At most one NEW per sweep, and a bad first contact cannot capture preparation forever.
        val candidate = prepareFair.window(contacts, 1).firstOrNull()
        prepareFair.advance(contacts.size, 0)
        if (candidate != null) {
            try {
                prepareMailbox(candidate, relay, nodeText, now, alwaysAnnounce = false)
            } catch (_: RelayException) {
                // Existing queues remain pollable despite a temporary preparation failure.
            }
        }
        for (c in contacts) {
            chat.account.own.get(c.address).forEach { out.add(c to it) }
        }
        return out.sortedByDescending { it.second.period }
    }

    /** No network or ChatHub.get under a monitor; contact incarnation captured BEFORE NEW. */
    internal fun preparePairMailbox(address: String): ByteArray? {
        val c = chat.book.find(address) ?: return null
        return preparePairMailbox(c) { true }
    }

    internal fun preparePairMailbox(contact: Contact, live: () -> Boolean): ByteArray? {
        if (!live()) return null
        val now = nowSecs()
        val node = chat.account.nodeText() ?: return null
        val relay = client(node, now) ?: return null
        return prepareMailbox(contact, relay, node, now, alwaysAnnounce = true, live = live)
    }

    private fun prepareMailbox(c: Contact, relay: HttpsRelay, node: String, now: Long, alwaysAnnounce: Boolean, live: () -> Boolean = { true }): ByteArray? {
        val owner = c.address + ":" + c.incarnation
        if (cleanup.size >= MAX_CLEANUP || cleanup.any { it.owner == owner } || !preparing.acquire(c.address)) return null
        val fence = MailboxFence()
        val caller = Thread.currentThread()
        var created: OwnQueue? = null
        var committed = false
        try {
            val period = OwnQueues.period(now)
            val old = chat.account.own.get(c.address).firstOrNull { it.period == period }
            if (old != null && !alwaysAnnounce && chat.account.announced.isCurrent(c.address, node)) return null
            val q = old ?: run {
                val pass = chat.account.pass(now) ?: return null
                // Scope new queue to the insertion, so stale DEL cannot delete a replacement.
                val rid = chat.account.recipientId(c.address + ":" + c.incarnation, period)
                // NEW may succeed remotely even if its reply is lost; rid is already known.
                created = OwnQueue(period, rid, ByteArray(32))
                OwnQueue(period, rid, relay.newQueue(rid, pass))
            }
            if (Thread.currentThread().isInterrupted || !live()) return null
            val id = chat.queueMailboxFor(c, q.senderId, node) {
                if (caller.isInterrupted || !live() || !fence.beginCommit() || chat.account.nodeText() != node) false
                else if (!chat.account.own.put(c.address, q)) false
                else { chat.account.announced.mark(c.address, node); true }
            }
            committed = id != null
            if (committed) wake()
            return id
        } finally {
            if (!committed && fence.cancelBeforeCommit()) {
                created?.let { cleanup.offer(Cleanup(owner, relay, it.recipientId)) }
            }
            preparing.release(c.address)
        }
    }

    /** Retry one stale NEW compensation per sweep; bounded by NEW admission. */
    private fun retryCleanup() {
        val item = cleanup.peek() ?: return
        try {
            item.relay.deleteQueue(item.recipientId)
            cleanup.poll()
        } catch (_: RelayException) {
            // Keep for the next sweep; never revive contact metadata.
        }
    }

    /** Recreate the old queue only for the still-current contact, then use the same SQL fence. */
    private fun recreate(relay: HttpsRelay, c: Contact, q: OwnQueue, nodeText: String, now: Long) {
        val address = c.address
        val scoped = chat.account.recipientId(address + ":" + c.incarnation, q.period)
        val legacy = chat.account.recipientId(address, q.period)
        if (!q.recipientId.contentEquals(scoped) && !q.recipientId.contentEquals(legacy)) return
        val owner = address + ":" + c.incarnation
        if (cleanup.size >= MAX_CLEANUP || cleanup.any { it.owner == owner } || !preparing.acquire(address)) return
        val fence = MailboxFence()
        val caller = Thread.currentThread()
        var committed = false
        var fresh: OwnQueue? = null
        try {
            val pass = chat.account.pass(now) ?: return
            val sender = relay.newQueue(q.recipientId, pass)
            val replacement = OwnQueue(q.period, q.recipientId, sender)
            fresh = replacement
            committed = chat.queueMailboxFor(c, sender, nodeText) {
                if (caller.isInterrupted || !fence.beginCommit() || chat.account.nodeText() != nodeText || !chat.account.own.put(address, replacement)) false
                else { chat.account.announced.mark(address, nodeText); true }
            } != null
        } finally {
            try {
                if (!committed && fence.cancelBeforeCommit() && q.recipientId.contentEquals(scoped) &&
                    chat.book.find(address)?.incarnation != c.incarnation) {
                    fresh?.let { cleanup.offer(Cleanup(owner, relay, it.recipientId)) }
                }
            } finally {
                preparing.release(address)
            }
        }
    }

    /** Плановая замена долгого канала (ADR-93, ADR-144): следующий запрос поднимет новое соединение. */
    private fun renewIfDue(relay: HttpsRelay, long: Boolean) {
        if (!long) return
        val t = System.currentTimeMillis()
        val until = channelUntil
        if (t < until) return
        if (until != 0L) relay.renewChannel()
        channelUntil = t + synchronized(schedule) { schedule.channelLifetimeMs() }
    }

    private fun sendOnce(): Long {
        val attempt = Diagnostics.begin(DiagnosticEvent.RelaySendLoop)
        val record: (DiagnosticResult) -> Unit = {
            Diagnostics.record(DiagnosticEvent.RelaySendLoop, it, attempt)
        }
        // BLE-метка и даже открытый GATT не доказывают доставку. Общая outbox сама
        // задаёт срок повтора; FrameCache не позволяет шифровать сообщение второй раз.
        val online = RelaySendDiagnostics.sweep(
            outgoing, { chat.waiting }, { network() != Net.None },
            { address -> sendTo(address, record) },
            { result -> if (result != DiagnosticResult.Start) record(result) },
        )
        if (!online) return IDLE_MS
        synchronized(senderSchedule) { senderSchedule.onSuccess() }
        return SWEEP_MS
    }

    private fun sendTo(address: String, record: (DiagnosticResult) -> Unit) =
        RelaySendDiagnostics.route(
            { chat.peers.get(address) },
            { peer -> client(peer.node, nowSecs()) },
            { peer, relay ->
                val frames = chat.relayFrames(address).mapNotNull { (frame, id) ->
                    parseFrame(frame.direct())?.blob?.let { RelayOutgoing(it, id) }
                }
                RelayFrameSender(
                    { blob -> relay.send(peer.senderId, blob) },
                    { id -> chat.onRelaySent(address, id) },
                ).deliver(frames)
            },
            record,
        )

    /** Клиент узла по тексту записи; `null` — подпись не владельца, срок вышел или поля не подходят. */
    private fun client(text: String, now: Long): HttpsRelay? {
        val info = try {
            nodeRecordOpen(text, now.toULong())
        } catch (_: NodeFfiException) {
            Diagnostics.record(DiagnosticEvent.NodeOpen, DiagnosticResult.Bad)
            return null
        }
        val endpoint = RelayAccount.endpoint(info) ?: return null
        return synchronized(clients) {
            clients.getOrPut(text) { HttpsRelay(endpoint, System::currentTimeMillis) }
        }
    }

    private enum class Net { None, Wifi, Mobile }

    private fun network(): Net {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return Net.None
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Net.None
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Net.None
        val fixed = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return if (fixed) Net.Wifi else Net.Mobile
    }

    private fun nowSecs(): Long = System.currentTimeMillis() / MS_PER_S

    companion object {
        @Volatile private var instance: RelayHub? = null

        fun get(app: Application): RelayHub =
            instance ?: synchronized(this) { instance ?: RelayHub(app).also { instance = it } }

        /** Очередей в одном `FETCH`/`WAIT` (ADR-114): 32 контакта × 2 периода; больше — [QueueRounds]. */
        private const val MAX_QUEUES = 64
        private const val MAX_CLEANUP = 32

        /** Нет сети, своего узла или очередей — проверить снова через минуту. */
        private const val IDLE_MS = 60_000L

        /** Повторы из очереди доставки для контактов не рядом — раз в минуту. */
        private const val SWEEP_MS = 60_000L
        private const val MS_PER_S = 1000L

        /** Пауза перед заменой канала на новой сети (ADR-138, оценка). */
        private const val SWITCH_MIN_MS = 3_000L
        private const val SWITCH_MAX_MS = 5_000L
    }
}

/** Связь с узлом (экран «Узел», макет `ui-v11.html`). */
enum class NodeLink {
    /** Нет своей записи узла или она не открывается. */
    None,

    /** Телефон не в сети. */
    Offline,

    /** Узел ещё не ответил. */
    Connecting,

    /** Нет очередей: контактов через узел пока нет. */
    Idle,

    /** Узел ответил на последний запрос. */
    Ok,

    /** Сеть есть, узел не ответил: пробуем другие адреса. */
    Down,
}
