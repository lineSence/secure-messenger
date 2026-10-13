package dev.sm.messenger.relay

import dev.sm.messenger.diagnostics.Diagnostics
import dev.sm.messenger.diagnostics.DiagnosticEvent
import dev.sm.messenger.diagnostics.DiagnosticResult
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
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import uniffi.sm_ffi.ContactHello
import uniffi.sm_ffi.InviteFfiException
import uniffi.sm_ffi.InviteLinkContent
import uniffi.sm_ffi.InviteLinkKind
import uniffi.sm_ffi.NodeFfiException
import uniffi.sm_ffi.PairMessage
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.contactOfCard
import uniffi.sm_ffi.contactOfIdentity
import uniffi.sm_ffi.inviteLinkDecode
import uniffi.sm_ffi.inviteLinkDecodeCoded
import uniffi.sm_ffi.inviteLinkEncode
import uniffi.sm_ffi.inviteLinkEncodeCoded
import uniffi.sm_ffi.inviteLinkKind
import uniffi.sm_ffi.inviteLinkSalt
import uniffi.sm_ffi.inviteLinkSaltNew
import uniffi.sm_ffi.nodeRecordOpen
import uniffi.sm_ffi.pairFrameDecline
import uniffi.sm_ffi.pairFrameDecode
import uniffi.sm_ffi.pairingSecretNew
import uniffi.sm_ffi.queueInviteRecipientId

/** Почему действие со ссылкой не удалось — экран показывает текст. */
enum class LinkProblem(val text: String) {
    NoNode("Сначала подключите узел: ссылка работает только через него"),
    NoPass("В ссылке нет пропуска узла: попросите его у владельца узла и введите в разделе «Связь»"),
    NoNetwork("Нет связи с узлом — попробуйте позже"),
    Bad("Ссылка не работает: её уже использовали, отозвали или текст неполный. Попросите новую"),
    Expired("Прошло 24 часа — ссылка больше не действует. Попросите новую"),
    Known("Этот человек уже в контактах"),
    Own("Это ваша собственная ссылка"),
    Full("Слишком много ссылок сразу — отзовите ненужные"),
    NoMemory("Не хватило памяти, чтобы проверить код. Закройте другие приложения и попробуйте снова"),
}

/** Только что созданная ссылка: текст и, если она с кодом, слова кода (показываются один раз). */
class MadeLink(val text: String, val code: String?)

/** Состояние ссылок для экрана «Новый контакт → Ссылка». */
data class InviteView(
    val loaded: Boolean = false,
    val nodeReady: Boolean = false,
    val outs: List<OutLink> = emptyList(),
    val ins: List<InLink> = emptyList(),
    val busy: Boolean = false,
    /** Только что созданная ссылка — для окна «Ссылка создана». */
    val made: MadeLink? = null,
    /** Текст вставленной ссылки с кодом: ждём 4 слова (экран «Ссылка защищена кодом»). */
    val askCode: String? = null,
    /** Последний введённый код не подошёл. */
    val codeWrong: Boolean = false,
    val problem: LinkProblem? = null,
    /** Имя только что добавленного по ссылке контакта. */
    val added: String? = null,
)

/** Очередь ссылки для потока приёма [RelayHub]: своя ссылка ([out]) или открытая чужая ([inviter]). */
class InviteBox(val recipientId: ByteArray, val out: ByteArray?, val inviter: String?)

/**
 * Ссылка-приглашение в приложении (п.12 плана 0.0.2-alpha; ADR-83, ADR-133, ADR-142).
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

    private class Outcome(
        val problem: LinkProblem? = null,
        val made: MadeLink? = null,
        val added: String? = null,
        /** Вставлена ссылка с кодом — спросить код. */
        val askCode: String? = null,
        /** Код не подошёл — остаться на вводе кода. */
        val wrongCode: Boolean = false,
        /** Ввод кода закончен (открыто, отказ или отмена). */
        val closeCode: Boolean = false,
    )

    private val random = SecureRandom()

    /** Перечитать ссылки (экран открыт). */
    fun refresh() = act { Outcome() }

    fun clearMade() {
        view = view.copy(made = null)
    }

    fun clearNotice() {
        view = view.copy(problem = null, added = null)
    }

    /** Отказаться от ввода кода: вставленный текст забыт. */
    fun cancelCode() {
        view = view.copy(askCode = null, codeWrong = false)
    }

    /**
     * Создать свою ссылку: нужен свой узел, пропуск и связь с узлом. [coded] — защитить кодом из
     * 4 слов (ADR-133): ключ из Argon2id считается до создания очереди на узле, чтобы отказ
     * (память) не оставил лишней очереди.
     */
    fun create(coded: Boolean) = act(DiagnosticEvent.InviteCreate) {
        val now = nowSecs()
        val nodeText = chat.account.nodeText() ?: return@act Outcome(LinkProblem.NoNode)
        val relay = client(nodeText, now) ?: return@act Outcome(LinkProblem.NoNode)
        val pass = chat.account.pass(now) ?: return@act Outcome(LinkProblem.NoNode)
        val key = chat.ownIdentity ?: return@act Outcome(LinkProblem.NoNode)
        if (links.outs().size >= InviteLinks.MAX_OUT) return@act Outcome(LinkProblem.Full)
        val secret = pairingSecretNew()
        val rid = queueInviteRecipientId(chat.account.secret().direct(), secret.direct())
        val words = if (coded) InviteCode.generate(random) else null
        val salt = if (words != null) inviteLinkSaltNew() else null
        val codeKey = if (words != null && salt != null) {
            InviteKdf.key(words, salt) ?: return@act Outcome(LinkProblem.NoMemory)
        } else {
            null
        }
        val sender = relay.newQueue(rid, pass)
        val expires = now + InviteLinks.LIFETIME_SECS
        val parts = InviteLinkContent(key, secret, expires.toULong(), sender, nodeText, ticketFor(relay, pass))
        val text = if (salt != null && codeKey != null) {
            inviteLinkEncodeCoded(parts, salt.direct(), codeKey.direct())
        } else {
            inviteLinkEncode(parts)
        }
        val link = OutLink(secret, now, expires, rid, sender, coded = coded)
        if (!links.putOut(link)) return@act Outcome(LinkProblem.Full)
        RelayHub.get(app).wake()
        Outcome(made = MadeLink(text, words?.let(InviteCode::display)))
    }

    /**
     * Билет узла для ссылки (ADR-146): по нему принявший получит гостевой пропуск и ему не нужен
     * пропуск владельца. Узел отказал (нет ключа гостевых пропусков, лимит) — ссылка без билета:
     * принявшему придётся ввести пропуск отдельно.
     */
    private fun ticketFor(relay: HttpsRelay, pass: ByteArray): ByteArray? =
        try {
            relay.ticket(pass)
        } catch (e: RelayException) {
            if (e.failure != RelayFailure.Rejected) throw e
            Diagnostics.record(DiagnosticEvent.InviteTicket, DiagnosticResult.Rejected)
            null
        }

    /** Отозвать свою ссылку: по ней больше не добавиться. */
    fun revoke(secret: ByteArray) = act(DiagnosticEvent.InviteRevoke) {
        val l = links.outs().firstOrNull { it.secret.contentEquals(secret) } ?: return@act Outcome()
        links.removeOut(secret)
        if (l.stage == OutStage.Started) forgetIfNew(l.contact)
        dropQueue(l.recipientId)
        Outcome()
    }

    /**
     * Открыть вставленную чужую ссылку: ссылка с кодом — сначала спросить код ([openCoded]), без
     * кода — карточка уходит пригласившему, ждём начало сессии.
     */
    fun open(text: String) = act(DiagnosticEvent.InviteOpen) {
        val kind = try {
            inviteLinkKind(text)
        } catch (_: InviteFfiException) {
            return@act Outcome(LinkProblem.Bad)
        }
        if (kind == InviteLinkKind.CODED) return@act Outcome(askCode = text)
        val c = try {
            inviteLinkDecode(text, nowSecs().toULong())
        } catch (_: InviteFfiException.Expired) {
            return@act Outcome(LinkProblem.Expired)
        } catch (_: InviteFfiException) {
            return@act Outcome(LinkProblem.Bad)
        }
        openContent(c)
    }

    /**
     * Ссылка с кодом: [input] — 4 слова. Ключ из Argon2id считается на телефоне (секунды), узел о
     * попытке не узнаёт. Не тот код — остаёмся на вводе; открылась — как обычная ссылка.
     */
    fun openCoded(text: String, input: String) = act(DiagnosticEvent.InviteOpenCoded) {
        val words = InviteCode.parse(input) ?: return@act Outcome(wrongCode = true)
        val salt = try {
            inviteLinkSalt(text)
        } catch (_: InviteFfiException) {
            return@act Outcome(LinkProblem.Bad, closeCode = true)
        }
        val key = InviteKdf.key(words, salt) ?: return@act Outcome(LinkProblem.NoMemory)
        val c = try {
            inviteLinkDecodeCoded(text, key.direct(), nowSecs().toULong())
        } catch (_: InviteFfiException.WrongCode) {
            return@act Outcome(wrongCode = true)
        } catch (_: InviteFfiException.Expired) {
            return@act Outcome(LinkProblem.Expired, closeCode = true)
        } catch (_: InviteFfiException) {
            return@act Outcome(LinkProblem.Bad, closeCode = true)
        }
        val o = openContent(c)
        Outcome(o.problem, o.made, o.added, closeCode = true)
    }

    /** Содержимое открытой ссылки: карточка пригласившему и ожидание начала сессии. */
    private fun openContent(c: InviteLinkContent): Outcome {
        val now = nowSecs()
        val gate = chat.signal ?: return Outcome(LinkProblem.Bad)
        val key = chat.ownIdentity ?: return Outcome(LinkProblem.NoNode)
        if (c.identityKey.contentEquals(key)) return Outcome(LinkProblem.Own)
        val inviter = contactOfIdentity(c.identityKey.direct())
        if (chat.book.find(inviter) != null) return Outcome(LinkProblem.Known)
        val peer = client(c.node, now) ?: return Outcome(LinkProblem.Bad)
        // Своего узла ещё нет — берём запись из ссылки: подпись проверяет `setNode`, чужую не принять.
        if (chat.account.nodeText() == null) chat.account.setNode(c.node, now)
        val nodeText = chat.account.nodeText() ?: return Outcome(LinkProblem.NoNode)
        val relay = client(nodeText, now) ?: return Outcome(LinkProblem.NoNode)
        val pass = chat.account.pass(now) ?: guestPass(c, peer, now) ?: return Outcome(LinkProblem.NoPass)
        val period = OwnQueues.period(now)
        val rid = chat.account.recipientId(inviter, period)
        val sender = relay.newQueue(rid, pass)
        val frame = gate.linkCardFrame(c.secret.direct(), sender.direct(), nodeText)
        val l = InLink(inviter, c.identityKey, c.senderId, c.node, c.expires.toLong(), OwnQueue(period, rid, sender))
        if (!links.putIn(l)) return Outcome(LinkProblem.Full)
        try {
            peer.send(c.senderId, frame)
        } catch (e: RelayException) {
            links.removeIn(inviter)
            throw e
        }
        RelayHub.get(app).wake()
        return Outcome()
    }

    /**
     * Гостевой пропуск по билету из ссылки (ADR-146), если своего пропуска нет: билет меняется на
     * узле пригласившего ([peer]) — пропуск годится только там, поэтому нужен тот же узел, что у
     * принимающего (своего узла нет — он взят из ссылки). Билета нет или узел его не признал
     * (истёк, узел перезапускался) — `null`: дальше «Нужен пропуск узла».
     */
    private fun guestPass(c: InviteLinkContent, peer: HttpsRelay, now: Long): ByteArray? {
        val ticket = c.ticket ?: return null.also { Diagnostics.record(DiagnosticEvent.InviteGuest, DiagnosticResult.NoTicket) }
        val own = chat.account.nodeInfo(now) ?: return null
        val theirs = try {
            nodeRecordOpen(c.node, now.toULong())
        } catch (_: NodeFfiException) {
            return null
        }
        if (!GuestPolicy.sameNode(own.port.toInt(), own.path, theirs.port.toInt(), theirs.path)) return null.also { Diagnostics.record(DiagnosticEvent.InviteGuest, DiagnosticResult.NodeMismatch) }
        val bytes = try {
            peer.guestPass(ticket)
        } catch (e: RelayException) {
            if (e.failure != RelayFailure.Rejected) throw e
            return null
        }
        return if (chat.account.setGuest(bytes, now)) chat.account.pass(now) else null
    }

    /** «Добавить …?» — да: ответ пригласившему, контакт «не сверен». */
    fun confirm(inviter: String) = act(DiagnosticEvent.InviteConfirm) {
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
    fun cancel(inviter: String) = act(DiagnosticEvent.InviteCancel) {
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
        Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Start)
        val now = nowSecs()
        val gate = chat.signal ?: return@onWorker false.also { Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.NoKey) }
        val out = box.out?.let { s -> links.outs().firstOrNull { it.secret.contentEquals(s) } }
        val inl = box.inviter?.let { a -> links.ins().firstOrNull { it.inviter == a } }
        when {
            out != null && out.stage == OutStage.Done -> chat.onRelayFrame(blob, out.contact)
            out != null -> onOut(gate, out, decode(blob), now)
            inl != null -> onIn(gate, inl, decode(blob))
            else -> true.also { Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Ignored) }
        }.also { handled -> Diagnostics.record(DiagnosticEvent.InviteFrame, if (handled) DiagnosticResult.Ok else DiagnosticResult.Failed) }
    }

    private fun onOut(gate: SignalGate, l: OutLink, msg: PairMessage?, now: Long): Boolean {
        when {
            l.stage == OutStage.Active && msg is PairMessage.LinkCard -> {
                Diagnostics.record(DiagnosticEvent.InviteCard, DiagnosticResult.Start)
                acceptCard(gate, l, msg, now)
            }
            l.stage == OutStage.Started && msg is PairMessage.Reply -> {
                Diagnostics.record(DiagnosticEvent.InviteReply, DiagnosticResult.Start)
                val h = try {
                    gate.openReply(l.contact, msg.ciphertext.direct())
                } catch (_: SignalFfiException) {
                    Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Signal)
                    return true
                }
                chat.book.save(l.contact, h.name, l.identityKey, h.beaconKey, verified = false)
                chat.peers.accept(l.contact, l.peerSender, l.peerNode, 0, now)
                links.putOut(OutLink(l.secret, l.createdAt, l.expires, l.recipientId, l.senderId, OutStage.Done, l.contact, l.identityKey, l.peerSender, l.peerNode, l.coded))
                contactAdded()
                publish(Outcome(added = h.name))
            }
            l.stage == OutStage.Started && msg is PairMessage.Decline -> {
                Diagnostics.record(DiagnosticEvent.InviteReply, DiagnosticResult.Declined)
                forgetIfNew(l.contact)
                links.removeOut(l.secret)
                publish(Outcome())
            }
        }
        return true
    }

    /** Первая карточка по ссылке: запись узла открывшего — подписана владельцем; не контакт. */
    private fun acceptCard(gate: SignalGate, l: OutLink, msg: PairMessage.LinkCard, now: Long) {
        val peer = client(msg.node, now) ?: return.also { Diagnostics.record(DiagnosticEvent.InviteCard, DiagnosticResult.Bad) }
        val contact = try {
            contactOfCard(msg.card.direct())
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Signal)
            return
        }
        if (chat.book.find(contact) != null) return.also { Diagnostics.record(DiagnosticEvent.InviteCard, DiagnosticResult.Known) }
        val st = try {
            gate.acceptLinkCard(msg.card.direct(), msg.proof.direct(), msg.senderId.direct(), msg.node, l.secret.direct(), hello())
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Signal)
            return
        }
        // Сначала отправка: не ушло — карточка останется на узле и будет принята заново.
        peer.send(msg.senderId, st.frame)
        Diagnostics.record(DiagnosticEvent.InviteCard, DiagnosticResult.Ok)
        links.putOut(OutLink(l.secret, l.createdAt, l.expires, l.recipientId, l.senderId, OutStage.Started, st.contact, st.identityKey, msg.senderId, msg.node, l.coded))
        publish(Outcome())
    }

    private fun onIn(gate: SignalGate, l: InLink, msg: PairMessage?): Boolean {
        if (l.stage != InStage.Sent || msg !is PairMessage.Start) return true.also { Diagnostics.record(DiagnosticEvent.InviteStart, DiagnosticResult.Ignored) }
        Diagnostics.record(DiagnosticEvent.InviteStart, DiagnosticResult.Start)
        val acc = try {
            gate.acceptStart(msg.ciphertext.direct(), l.identityKey)
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Signal)
            return true
        }
        links.putIn(InLink(l.inviter, l.identityKey, l.inviterSender, l.inviterNode, l.expires, l.queue, InStage.Ask, acc.hello.name, acc.hello.beaconKey))
        publish(Outcome())
        Diagnostics.record(DiagnosticEvent.InviteStart, DiagnosticResult.AskContact)
        return true
    }

    private fun decode(blob: ByteArray): PairMessage? =
        try {
            pairFrameDecode(blob.direct())
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Signal)
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
        Diagnostics.record(DiagnosticEvent.ContactAdded)
        BleHub.get(app).loadContacts()
        chat.touch()
        RelayHub.get(app).wake()
    }

    private fun act(event: DiagnosticEvent? = null, block: () -> Outcome) {
        main.post { view = view.copy(busy = true) }
        worker.execute {
            val attempt = event?.let(Diagnostics::begin) ?: 0L
            val o = guard(block) ?: Outcome(LinkProblem.NoNetwork)
            event?.let { Diagnostics.record(it, outcomeResult(o), attempt) }
            publish(o)
        }
    }

    private fun outcomeResult(o: Outcome): DiagnosticResult = when {
        o.wrongCode -> DiagnosticResult.WrongCode
        o.askCode != null -> DiagnosticResult.AskCode
        o.problem == null -> DiagnosticResult.Ok
        else -> when (o.problem) {
            LinkProblem.NoNode -> DiagnosticResult.NoNode
            LinkProblem.NoPass -> DiagnosticResult.NoPass
            LinkProblem.NoNetwork -> DiagnosticResult.NoNetwork
            LinkProblem.Bad -> DiagnosticResult.Bad
            LinkProblem.Expired -> DiagnosticResult.Expired
            LinkProblem.Known -> DiagnosticResult.Known
            LinkProblem.Own -> DiagnosticResult.Own
            LinkProblem.Full -> DiagnosticResult.Full
            LinkProblem.NoMemory -> DiagnosticResult.NoMemory
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
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.NoNetwork)
            null
        } catch (_: SQLiteException) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Storage)
            null
        } catch (_: GeneralSecurityException) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Keystore)
            null
        } catch (_: SecureDb.CorruptRow) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Corrupt)
            null
        } catch (_: SignalFfiException) {
            Diagnostics.record(DiagnosticEvent.InviteFrame, DiagnosticResult.Signal)
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
                askCode = if (o.closeCode) null else o.askCode ?: view.askCode,
                codeWrong = when {
                    o.wrongCode -> true
                    o.closeCode || o.askCode != null -> false
                    else -> view.codeWrong
                },
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
