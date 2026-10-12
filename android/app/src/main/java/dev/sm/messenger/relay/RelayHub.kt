package dev.sm.messenger.relay

import android.app.Application
import android.database.sqlite.SQLiteException
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.ble.direct
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.store.SecureDb
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
    @Volatile private var channelUntil = 0L
    private val main = Handler(Looper.getMainLooper())
    private val switchToken = Any()

    /** Будильник пауз потока приёма: смена сети прерывает ожидание. */
    private val bell = LinkedBlockingQueue<Unit>(1)

    @Volatile private var started = false

    /** Часть очередей контактов, которую читает следующий шаг приёма (только поток приёма). */
    private var round = 0

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
    )

    /** Запустить потоки приёма и отправки; повторный вызов ничего не делает. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        chat.onRelayWork = { outgoing.offer(it) }
        app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onNetworkChanged()

                override fun onLost(network: Network) = Unit
            },
        )
        thread(name = "sm-relay-in", isDaemon = true) { loop(::receiveOnce) }
        thread(name = "sm-relay-out", isDaemon = true) { loop(::sendOnce) }
    }

    /** [step] возвращает паузу до следующего шага, мс; сбой — пауза с удвоением. */
    private fun loop(step: () -> Long) {
        while (true) {
            val pause = try {
                step()
            } catch (_: RelayException) {
                retryDelay()
            } catch (_: SQLiteException) {
                retryDelay()
            } catch (_: GeneralSecurityException) {
                retryDelay()
            } catch (_: SecureDb.CorruptRow) {
                retryDelay()
            } catch (_: SignalFfiException) {
                retryDelay()
            } catch (_: ExecutionException) {
                // Сбой внутри потока ChatHub, который guard не ловит: не роняем поток узла.
                retryDelay()
            }
            if (pause > 0) nap(pause)
        }
    }

    /** Пауза [ms], которую прерывает смена сети; без сети — до появления сети. */
    private fun nap(ms: Long) {
        if (network() == Net.None) bell.take() else bell.poll(ms, TimeUnit.MILLISECONDS)
    }

    /** Новая сеть по умолчанию (главный поток): замена канала через 3–5 с, прежняя отменяется. */
    private fun onNetworkChanged() {
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
        channelUntil = 0L
        bell.offer(Unit)
    }

    /** Принята новая запись узла или пропуск: не ждать конца паузы. */
    fun wake() {
        bell.offer(Unit)
    }

    private fun retryDelay(): Long = synchronized(schedule) { schedule.retryDelayMs() }

    private fun receiveOnce(): Long {
        val now = nowSecs()
        val net = network()
        val nodeText = chat.account.nodeText()
        val relay = nodeText?.let { client(it, now) }
        if (net == Net.None || nodeText == null || relay == null) {
            link = if (relay == null) NodeLink.None else NodeLink.Offline
            return IDLE_MS
        }
        if (link == NodeLink.None || link == NodeLink.Offline) link = NodeLink.Connecting
        renewIfDue(relay, net)
        // Очереди ссылок — первыми и в каждой части: их мало, а ждущий обмен ключами не должен
        // выпасть из списка. Очереди контактов не в один запрос (больше 32 контактов) — по кругу.
        val invited = invites.boxes().map { Box(it.recipientId, invite = it) }
        val room = (MAX_QUEUES - invited.size).coerceAtLeast(1)
        val contacts = ensureQueues(relay, nodeText, now).map { (a, q) -> Box(q.recipientId, a, q) }
        val parts = QueueRounds.parts(contacts.size, room)
        val part = round % parts
        val boxes = (invited + QueueRounds.part(contacts, room, part)).take(MAX_QUEUES)
        if (boxes.isEmpty()) {
            link = NodeLink.Idle
            return IDLE_MS
        }
        val got = try {
            relay.receive(boxes.map { it.recipientId }, wait = net == Net.Wifi)
        } catch (e: RelayException) {
            if (e.failure != RelayFailure.NoQueue || e.queue >= boxes.size) {
                link = NodeLink.Down
                throw e
            }
            val b = boxes[e.queue]
            if (b.address != null && b.own != null) {
                recreate(relay, b.address, b.own, nodeText, now)
            } else {
                // Очередь ссылки: тот же ID получателя — тот же ID отправителя, что в ссылке.
                chat.account.pass(now)?.let { relay.newQueue(b.recipientId, it) }
            }
            return 0
        }
        synchronized(schedule) { schedule.onSuccess() }
        link = NodeLink.Ok
        if (got == null) {
            round = (part + 1) % parts
            // Круг не закончен — следующая часть сразу; опрос ждёт конца круга.
            if (net == Net.Wifi || part != parts - 1) return 0
            return synchronized(schedule) {
                schedule.onQuiet()
                schedule.pollDelayMs()
            }
        }
        val b = boxes[got.queue]
        val handled = when {
            b.invite != null -> invites.onFrame(b.invite, got.blob)
            b.address != null -> chat.onRelayFrame(got.blob, b.address)
            else -> true
        }
        if (!handled) return retryDelay()
        relay.ack(b.recipientId, got.seq)
        synchronized(schedule) { schedule.onMail() }
        return 0
    }

    /**
     * Свои очереди всех контактов, текущего периода первыми. Нет очереди текущего периода —
     * создать (`NEW` по пропуску) и отправить контакту «адрес почты»; запись — только после того,
     * как сообщение поставлено в очередь доставки, иначе при следующем шаге — снова.
     */
    private fun ensureQueues(relay: HttpsRelay, nodeText: String, now: Long): List<Pair<String, OwnQueue>> {
        val period = OwnQueues.period(now)
        val out = ArrayList<Pair<String, OwnQueue>>()
        for (c in chat.book.list()) {
            var queues = chat.account.own.get(c.address)
            val pass = if (queues.none { it.period == period }) chat.account.pass(now) else null
            if (pass != null) {
                val rid = chat.account.recipientId(c.address, period)
                val q = OwnQueue(period, rid, relay.newQueue(rid, pass))
                if (chat.sendMailbox(c.address, q.senderId, nodeText) && chat.account.own.put(c.address, q)) {
                    queues = chat.account.own.get(c.address)
                    chat.account.announced.mark(c.address, nodeText)
                }
            } else if (queues.isNotEmpty() && !chat.account.announced.isCurrent(c.address, nodeText)) {
                // Запись узла сменилась до смены периода: контакту — тот же ID отправителя и новая запись.
                if (chat.sendMailbox(c.address, queues[0].senderId, nodeText)) {
                    chat.account.announced.mark(c.address, nodeText)
                }
            }
            queues.forEach { out.add(c.address to it) }
        }
        return out.sortedByDescending { it.second.period }
    }

    /** Узел перезапускался: та же очередь заново; другой ID отправителя — обновить и сообщить контакту. */
    private fun recreate(relay: HttpsRelay, address: String, q: OwnQueue, nodeText: String, now: Long) {
        val pass = chat.account.pass(now) ?: return
        val sender = relay.newQueue(q.recipientId, pass)
        if (sender.contentEquals(q.senderId)) return
        val fresh = OwnQueue(q.period, q.recipientId, sender)
        if (chat.sendMailbox(address, sender, nodeText) && chat.account.own.put(address, fresh)) {
            chat.account.announced.mark(address, nodeText)
        }
    }

    /** Плановая замена канала по Wi-Fi (ADR-93): следующий запрос поднимет новое соединение. */
    private fun renewIfDue(relay: HttpsRelay, net: Net) {
        if (net != Net.Wifi) return
        val t = System.currentTimeMillis()
        val until = channelUntil
        if (t < until) return
        if (until != 0L) relay.renewChannel()
        channelUntil = t + synchronized(schedule) { schedule.channelLifetimeMs() }
    }

    private fun sendOnce(): Long {
        val first = outgoing.poll(SWEEP_MS, TimeUnit.MILLISECONDS)
        val targets = LinkedHashSet<String>()
        if (first != null) targets.add(first) else targets.addAll(chat.waiting)
        outgoing.drainTo(targets)
        if (network() == Net.None) return 0
        val ble = BleHub.get(app)
        for (address in targets) {
            if (!ble.isNear(address)) sendTo(address)
        }
        return 0
    }

    private fun sendTo(address: String) {
        val peer = chat.peers.get(address) ?: return
        val relay = client(peer.node, nowSecs()) ?: return
        for ((frame, id) in chat.relayFrames(address)) {
            val blob = parseFrame(frame.direct())?.blob ?: continue
            relay.send(peer.senderId, blob)
            if (id != null) chat.onRelaySent(address, id)
        }
    }

    /** Клиент узла по тексту записи; `null` — подпись не владельца, срок вышел или поля не подходят. */
    private fun client(text: String, now: Long): HttpsRelay? {
        val info = try {
            nodeRecordOpen(text, now.toULong())
        } catch (_: NodeFfiException) {
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
