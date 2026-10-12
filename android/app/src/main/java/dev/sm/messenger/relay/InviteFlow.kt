package dev.sm.messenger.relay

import android.app.Application
import android.database.sqlite.SQLiteException
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.ble.direct
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.contacts.SignalGate
import dev.sm.messenger.onboarding.Profile
import dev.sm.messenger.store.SecureDb
import java.security.GeneralSecurityException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import uniffi.sm_ffi.ContactHello
import uniffi.sm_ffi.InviteFfiException
import uniffi.sm_ffi.PairMessage
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.contactOfCard
import uniffi.sm_ffi.contactOfIdentity
import uniffi.sm_ffi.inviteLinkDecode
import uniffi.sm_ffi.inviteLinkEncode
import uniffi.sm_ffi.pairFrameDecline
import uniffi.sm_ffi.pairFrameDecode
import uniffi.sm_ffi.pairingSecretNew
import uniffi.sm_ffi.queueInviteRecipientId

/** Почему действие со ссылкой не удалось — экран показывает текст. */
enum class LinkProblem(val text: String) {
    NoNode("Сначала подключите узел: ссылка работает только через него"),
    NoNetwork("Нет связи с узлом — попробуйте позже"),
    Bad("Ссылка не работает: её уже использовали, отозвали или текст неполный. Попросите новую"),
    Expired("Прошло 24 часа — ссылка больше не действует. Попросите новую"),
    Known("Этот человек уже в контактах"),
    Own("Это ваша собственная ссылка"),
    Full("Слишком много ссылок сразу — отзовите ненужные"),
}

/** Состояние ссылок для экрана «Новый контакт → Ссылка». */
data class InviteView(
    val loaded: Boolean = false,
    val nodeReady: Boolean = false,
    val outs: List<OutLink> = emptyList(),
    val ins: List<InLink> = emptyList(),
    val busy: Boolean = false,
    /** Текст только что созданной ссылки — для листа «Ссылка создана». */
    val made: String? = null,
    val problem: LinkProblem? = null,
    /** Имя только что добавленного по ссылке контакта. */
    val added: String? = null,
)

/** Очередь ссылки для потока приёма [RelayHub]: своя ссылка ([out]) или открытая чужая ([inviter]). */
class InviteBox(val recipientId: ByteArray, val out: ByteArray?, val inviter: String?)

/**
 * Ссылка-приглашение в приложении (п.12 плана 0.0.2-alpha; ADR-83, ADR-141 — пока без кода).
 * Протокол — в ядре (`sm_signal::invite`, `PairFrame::LinkCard`); здесь порядок шагов через узел.
 *
 * Пригласивший ([create]): секрет ссылки → своя очередь ссылки на своём узле (`NEW` по пропуску,
 * ID получателя из секрета, ADR-134) → текст ссылки. Карточка из очереди ссылки принимается
 * сама (ссылка — уже согласие пригласившего), но только первая: начало сессии уходит в очередь
 * открывшего, ссылка больше не принимает карточек. Ответ — контакт «не сверен» (ADR-23).
 * Отзыв ([revoke]) — строка удаляется, очередь на узле тоже (если узел доступен).
 *
 * Получивший ([open]): своя очередь для пригласившего (та же, что потом станет очередью контакта)
 * → карточка с доказательством в очередь ссылки → ждём начало сессии; ключ пригласившего — из
 * ссылки, иной — отказ. Пришло — экран спрашивает «Добавить …?» с именем, которое тот указал сам;
 * «Добавить» ([confirm]) — ответ и контакт «не сверен», «Отмена» ([cancel]) — отказ и сессия забыта.
 *
 * До сообщения «адрес почты» от пригласившего получивший пишет ему в очередь ссылки; пригласивший
 * читает её ещё неделю ([InviteLinks.DONE_KEEP_SECS]) и передаёт кадры в [ChatHub.onRelayFrame].
 *
 * Все шаги — в одном потоке `sm-invite`, состояние экрана — в главном. Ошибки сети и хранилища не
 * роняют процесс: действие экрана — [LinkProblem], кадр из очереди — не подтверждается и придёт снова.
 */
class InviteFlow internal constructor(
    private val app: Application,
    private val chat: ChatHub,
    private val client: (String, Long) -> HttpsRelay?,
) {
    private val links = chat.invites
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sm-invite").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    var view by mutableStateOf(InviteView())
        private set

    private class Outcome(val problem: LinkProblem? = null, val made: String? = null, val added: String? = null)

    /** Перечитать ссылки (экран открыт). */
    fun refresh() = act { Outcome() }

    fun clearMade() {
        view = view.copy(made = null)
    }

    fun clearNotice() {
        view = view.copy(problem = null, added = null)
    }

    /** Создать свою ссылку: нужен свой узел, пропуск и связь с узлом. */
    fun create() = act {
        val now = nowSecs()
        val nodeText = chat.account.nodeText() ?: return@act Outcome(LinkProblem.NoNode)
        val relay = client(nodeText, now) ?: return@act Outcome(LinkProblem.NoNode)
        val pass = chat.account.pass(now) ?: return@act Outcome(LinkProblem.NoNode)
        val key = chat.ownIdentity ?: return@act Outcome(LinkProblem.NoNode)
        if (links.outs().size >= InviteLinks.MAX_OUT) return@act Outcome(LinkProblem.Full)
        val secret = pairingSecretNew()
        val rid = queueInviteRecipientId(chat.account.secret().direct(), secret.direct())
        val sender = relay.newQueue(rid, pass)
        val expires = now + InviteLinks.LIFETIME_SECS
        val text = inviteLinkEncode(key.direct(), secret.direct(), expires.toULong(), sender.direct(), nodeText)
        if (!links.putOut(OutLink(secret, now, expires, rid, sender))) return@act Outcome(LinkProblem.Full)
        Outcome(made = text)
    }

    /** Отозвать свою ссылку: по ней больше не добавиться. */
    fun revoke(secret: ByteArray) = act {
        val l = links.outs().firstOrNull { it.secret.contentEquals(secret) } ?: return@act Outcome()
        links.removeOut(secret)
        if (l.stage == OutStage.Started) forgetIfNew(l.contact)
        dropQueue(l.recipientId)
        Outcome()
    }

    /** Открыть вставленную чужую ссылку: карточка уходит пригласившему, ждём начало сессии. */
    fun open(text: String) = act {
        val now = nowSecs()
        val gate = chat.signal ?: return@act Outcome(LinkProblem.Bad)
        val c = try {
            inviteLinkDecode(text, now.toULong())
        } catch (_: InviteFfiException.Expired) {
            return@act Outcome(LinkProblem.Expired)
        } catch (_: InviteFfiException) {
            return@act Outcome(LinkProblem.Bad)
        }
        val key = chat.ownIdentity ?: return@act Outcome(LinkProblem.NoNode)
        if (c.identityKey.contentEquals(key)) return@act Outcome(LinkProblem.Own)
        val inviter = contactOfIdentity(c.identityKey.direct())
        if (chat.book.find(inviter) != null) return@act Outcome(LinkProblem.Known)
        val peer = client(c.node, now) ?: return@act Outcome(LinkProblem.Bad)
        val nodeText = chat.account.nodeText() ?: return@act Outcome(LinkProblem.NoNode)
        val relay = client(nodeText, now) ?: return@act Outcome(LinkProblem.NoNode)
        val pass = chat.account.pass(now) ?: return@act Outcome(LinkProblem.NoNode)
        val period = OwnQueues.period(now)
        val rid = chat.account.recipientId(inviter, period)
        val sender = relay.newQueue(rid, pass)
        val frame = gate.linkCardFrame(c.secret.direct(), sender.direct(), nodeText)
        val l = InLink(inviter, c.identityKey, c.senderId, c.node, c.expires.toLong(), OwnQueue(period, rid, sender))
        if (!links.putIn(l)) return@act Outcome(LinkProblem.Full)
        try {
            peer.send(c.senderId, frame)
        } catch (e: RelayException) {
            links.removeIn(inviter)
            throw e
        }
        Outcome()
    }

    /** «Добавить …?» — да: ответ пригласившему, контакт «не сверен». */
    fun confirm(inviter: String) = act {
        val now = nowSecs()
        val gate = chat.signal ?: return@act Outcome(LinkProblem.Bad)
        val l = links.ins().firstOrNull { it.inviter == inviter && it.stage == InStage.Ask }
            ?: return@act Outcome(LinkProblem.Bad)
        val peer = client(l.inviterNode, now) ?: return@act Outcome(LinkProblem.Bad)
        peer.send(l.inviterSender, gate.replyFrame(inviter, hello()))
        chat.book.save(inviter, l.name, l.identityKey, l.beaconKey, verified = false)
        chat.peers.accept(inviter, l.inviterSender, l.inviterNode, 0, now)
        chat.account.own.put(inviter, l.queue)
        links.removeIn(inviter)
        contactAdded()
        Outcome(added = l.name)
    }

    /** Отменить открытую ссылку: пригласившему — отказ (если уже есть сессия), сессия забыта. */
    fun cancel(inviter: String) = act {
        val l = links.ins().firstOrNull { it.inviter == inviter } ?: return@act Outcome()
        links.removeIn(inviter)
        if (l.stage == InStage.Ask) {
            forgetIfNew(inviter)
            runCatching { client(l.inviterNode, nowSecs())?.send(l.inviterSender, pairFrameDecline()) }
        }
        dropQueue(l.queue.recipientId)
        Outcome()
    }

    /** Очереди ссылок для потока приёма; отжившие ссылки убираются (сессии незаконченных забыты). */
    fun boxes(): List<InviteBox> = onWorker(emptyList()) {
        val (deadOut, deadIn) = links.prune(nowSecs())
        deadOut.filter { it.stage == OutStage.Started }.forEach { forgetIfNew(it.contact) }
        deadIn.filter { it.stage == InStage.Ask }.forEach { forgetIfNew(it.inviter) }
        if (deadOut.isNotEmpty() || deadIn.isNotEmpty()) publish(Outcome())
        links.outs().map { InviteBox(it.recipientId, it.secret, null) } +
            links.ins().map { InviteBox(it.queue.recipientId, null, it.inviter) }
    }

    /**
     * Блоб из очереди ссылки. `true` — обработан или не наш (удалить с узла); `false` или
     * исключение — оставить на узле, придёт снова.
     */
    fun onFrame(box: InviteBox, blob: ByteArray): Boolean = onWorker(false) {
        val now = nowSecs()
        val gate = chat.signal ?: return@onWorker false
        val out = box.out?.let { s -> links.outs().firstOrNull { it.secret.contentEquals(s) } }
        val inl = box.inviter?.let { a -> links.ins().firstOrNull { it.inviter == a } }
        when {
            out != null && out.stage == OutStage.Done -> chat.onRelayFrame(blob, out.contact)
            out != null -> onOut(gate, out, decode(blob), now)
            inl != null -> onIn(gate, inl, decode(blob))
            else -> true
        }
    }

    private fun onOut(gate: SignalGate, l: OutLink, msg: PairMessage?, now: Long): Boolean {
        when {
            l.stage == OutStage.Active && msg is PairMessage.LinkCard -> acceptCard(gate, l, msg, now)
            l.stage == OutStage.Started && msg is PairMessage.Reply -> {
                val h = try {
                    gate.openReply(l.contact, msg.ciphertext.direct())
                } catch (_: SignalFfiException) {
                    return true
                }
                chat.book.save(l.contact, h.name, l.identityKey, h.beaconKey, verified = false)
                chat.peers.accept(l.contact, l.peerSender, l.peerNode, 0, now)
                links.putOut(OutLink(l.secret, l.createdAt, l.expires, l.recipientId, l.senderId, OutStage.Done, l.contact, l.identityKey, l.peerSender, l.peerNode))
                contactAdded()
                publish(Outcome(added = h.name))
            }
            l.stage == OutStage.Started && msg is PairMessage.Decline -> {
                forgetIfNew(l.contact)
                links.removeOut(l.secret)
                publish(Outcome())
            }
        }
        return true
    }

    /** Первая карточка по ссылке: запись узла открывшего — подписана владельцем; не контакт. */
    private fun acceptCard(gate: SignalGate, l: OutLink, msg: PairMessage.LinkCard, now: Long) {
        val peer = client(msg.node, now) ?: return
        val contact = try {
            contactOfCard(msg.card.direct())
        } catch (_: SignalFfiException) {
            return
        }
        if (chat.book.find(contact) != null) return
        val st = try {
            gate.acceptLinkCard(msg.card.direct(), msg.proof.direct(), msg.senderId.direct(), msg.node, l.secret.direct(), hello())
        } catch (_: SignalFfiException) {
            return
        }
        // Сначала отправка: не ушло — карточка останется на узле и будет принята заново.
        peer.send(msg.senderId, st.frame)
        links.putOut(OutLink(l.secret, l.createdAt, l.expires, l.recipientId, l.senderId, OutStage.Started, st.contact, st.identityKey, msg.senderId, msg.node))
        publish(Outcome())
    }

    private fun onIn(gate: SignalGate, l: InLink, msg: PairMessage?): Boolean {
        if (l.stage != InStage.Sent || msg !is PairMessage.Start) return true
        val acc = try {
            gate.acceptStart(msg.ciphertext.direct(), l.identityKey)
        } catch (_: SignalFfiException) {
            return true
        }
        links.putIn(InLink(l.inviter, l.identityKey, l.inviterSender, l.inviterNode, l.expires, l.queue, InStage.Ask, acc.hello.name, acc.hello.beaconKey))
        publish(Outcome())
        return true
    }

    private fun decode(blob: ByteArray): PairMessage? =
        try {
            pairFrameDecode(blob.direct())
        } catch (_: SignalFfiException) {
            null
        }

    private fun hello(): ContactHello =
        ContactHello(Profile(app).name?.takeIf { it.isNotBlank() } ?: NO_NAME, chat.book.ownBeaconKey())

    private fun forgetIfNew(contact: String) {
        if (chat.book.find(contact) == null) runCatching { chat.signal?.forget(contact) }
    }

    /** Удалить очередь на своём узле; узел недоступен — останется до его перезапуска. */
    private fun dropQueue(recipientId: ByteArray) {
        val now = nowSecs()
        val relay = chat.account.nodeText()?.let { client(it, now) } ?: return
        try {
            relay.deleteQueue(recipientId)
        } catch (_: RelayException) {
            // Не страшно: строки ссылки уже нет, кадры из очереди никто не прочтёт.
        }
    }

    /** Новый контакт: метка Bluetooth (ADR-61) и список чатов. */
    private fun contactAdded() {
        BleHub.get(app).loadContacts()
        chat.touch()
    }

    private fun act(block: () -> Outcome) {
        main.post { view = view.copy(busy = true) }
        worker.execute {
            val o = guard(block) ?: Outcome(LinkProblem.NoNetwork)
            publish(o)
        }
    }

    private fun <T> onWorker(fallback: T, block: () -> T): T =
        try {
            worker.submit<T> { guard(block) ?: fallback }.get()
        } catch (_: ExecutionException) {
            fallback
        }

    /** Сбои сети, хранилища и ядра — `null` (действие не удалось), процесс не падает. */
    private fun <T> guard(block: () -> T): T? =
        try {
            block()
        } catch (_: RelayException) {
            null
        } catch (_: SQLiteException) {
            null
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: SecureDb.CorruptRow) {
            null
        } catch (_: SignalFfiException) {
            null
        }

    private fun publish(o: Outcome) {
        val outs = guard { links.outs() }.orEmpty()
        val ins = guard { links.ins() }.orEmpty()
        val now = nowSecs()
        val ready = guard { chat.account.node(now) != null && chat.account.pass(now) != null } == true
        main.post {
            view = InviteView(
                loaded = true,
                nodeReady = ready,
                outs = outs.filter { it.stage != OutStage.Done },
                ins = ins,
                busy = false,
                made = o.made ?: view.made,
                problem = o.problem ?: view.problem,
                added = o.added ?: view.added,
            )
        }
    }

    private fun nowSecs(): Long = System.currentTimeMillis() / MS_PER_S

    private companion object {
        const val MS_PER_S = 1000L
        const val NO_NAME = "Без имени"
    }
}
