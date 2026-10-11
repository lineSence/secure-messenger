package dev.sm.messenger.contacts

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sm.messenger.ble.NearbyBeacon
import dev.sm.messenger.ble.direct
import dev.sm.messenger.ble.epochNow
import dev.sm.messenger.ble.uuidOf
import java.util.concurrent.Executors
import uniffi.sm_ffi.ContactHello
import uniffi.sm_ffi.PairMessage
import uniffi.sm_ffi.QrContent
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.StartedSession
import uniffi.sm_ffi.AcceptedSession
import uniffi.sm_ffi.blePairingToken
import uniffi.sm_ffi.blePartKeyPairing
import uniffi.sm_ffi.contactOfCard
import uniffi.sm_ffi.contactOfIdentity
import uniffi.sm_ffi.contactOfStart
import uniffi.sm_ffi.pairFrameDecline
import uniffi.sm_ffi.pairFrameDecode
import uniffi.sm_ffi.pairFrameIntro
import uniffi.sm_ffi.pairingSecretNew
import uniffi.sm_ffi.qrDecode
import uniffi.sm_ffi.qrEncode

/** Телефон в списке «Рядом»: имя он указал сам (UX-25). */
data class NearbyPhone(val address: String, val name: String, val strong: Boolean)

/** Что показывает экран «Новый контакт». */
sealed interface PairState {
    data object Idle : PairState

    /** Мой QR: строка для QR и когда он сменится. */
    data class Showing(val qr: String, val expiresAt: Long) : PairState

    /** Сканировать: камера ищет QR. */
    data object Scanning : PairState

    /** QR прочитан — ищем телефон и обмениваемся ключами. */
    data object Exchanging : PairState

    /** Рядом: ищем, в списке — телефоны с открытым тем же экраном. */
    data class Searching(val phones: List<NearbyPhone>) : PairState

    /** Ждём подтверждения на другом телефоне. */
    data class Waiting(val name: String) : PairState

    /** Подтвердить добавление: [name] — имя, которое человек указал сам. */
    data class Ask(val name: String) : PairState

    /** Готово. */
    data class Done(val name: String, val verified: Boolean) : PairState

    /** Не получилось; ничего не сохранено. */
    data class Failed(val text: String) : PairState
}

/**
 * Добавление контакта при встрече (ADR-62, UX-25). Криптография — в ядре (через [SignalGate]);
 * здесь только порядок шагов и Bluetooth ([PairLink]).
 *
 * - **Мой QR**: в QR — свой ключ личности и одноразовый секрет; телефон объявляет метку секрета
 *   и ждёт карточку с доказательством знания секрета → начинает сессию → ждёт ответ → «сверен».
 * - **Сканировать**: из QR — ключ и секрет; ищем метку секрета, шлём карточку с доказательством,
 *   принимаем первое сообщение, только если ключ отправителя — из QR → отвечаем → «сверен».
 * - **Рядом**: оба объявляют общую метку и обмениваются именами; выбравший шлёт карточку, второй
 *   подтверждает и начинает сессию, выбравший тоже подтверждает и отвечает → «не сверен» у обоих.
 *
 * Вызовы ядра (Keystore, libsignal, SQLite) — в отдельном потоке; состояние — в главном.
 * Экран закрыт ([close]) — секрет забыт, Bluetooth выключен, незаконченное не сохраняется.
 *
 * Уже добавленный контакт не трогаем: его адрес узнаём до начала сессии (из QR, карточки или
 * первого сообщения) и отказываем — иначе новая сессия заменила бы рабочую, а отмена стёрла бы
 * её. Стираем только сессии адресов, которых нет в книге ([forgetIfNew]).
 *
 * Отправивший последний кадр (`Reply`) записывает контакт только после подтверждения, что
 * другой телефон кадр принял, — иначе контакт был бы только у одного из двоих.
 *
 * Каждый обмен — своё поколение ([generation]); [reset] его меняет, и запись контакта
 * ([ifLive]) из отменённого обмена не происходит (проверка и запись — под одним замком с [reset]).
 */
class Pairing(
    context: Context,
    private val sessions: SignalGate,
    private val book: ContactBook,
    private val myName: String,
    private val myIdentity: ByteArray,
) : PairLink.Listener {
    private val link = PairLink(context, this)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    var state by mutableStateOf<PairState>(PairState.Idle)
        private set

    /** Короткое сообщение (ошибка Bluetooth и т.п.); экран показывает и сбрасывает. */
    var notice by mutableStateOf<String?>(null)

    private enum class Mode { None, Show, Scan, Near }

    private var mode = Mode.None
    private var secret: ByteArray? = null
    private var scanned: QrContent? = null
    private var target: String? = null
    private var started = HashMap<String, StartedSession>()
    private var accepted: Pair<String, AcceptedSession>? = null
    /** «Мой QR»: телефон, чья карточка уже принимается (одно добавление на QR). */
    private var claimed: String? = null
    /** «Сканировать»: QR проверяется (нет ли контакта в книге). */
    private var checkingQr = false
    private var askCard: Pair<String, ByteArray>? = null
    private val introNames = HashMap<String, String>()
    private val phones = LinkedHashMap<String, NearbyPhone>()
    private val rssi = HashMap<String, Int>()
    private val expire = Runnable { onExpired() }

    private val gate = Any()

    /** Номер обмена; меняется в [reset] под [gate]. */
    @Volatile private var generation = 0L

    /** Обмен отменён, пока шла работа в фоне; итог отбрасывается. */
    private class Cancelled : Exception()

    private fun hello(): ContactHello = ContactHello(myName, book.ownBeaconKey())

    // ================= режимы =================

    /** Мой QR: новый секрет, объявление его метки. */
    fun showQr() {
        reset(Mode.Show)
        val s = pairingSecretNew()
        val qr = try {
            qrEncode(myIdentity.direct(), s.direct())
        } catch (_: SignalFfiException) {
            return fail("Не удалось создать QR")
        }
        val token = uuidOf(blePairingToken(s.direct(), epochNow().toULong())) ?: return fail("Не удалось вычислить метку Bluetooth")
        secret = s
        val partKey = runCatching { blePartKeyPairing(s.direct()) }.getOrNull() ?: return fail("Не удалось вычислить ключ Bluetooth")
        if (!link.start(own = token, search = emptyList(), partKey = partKey)) return fail("Включите Bluetooth в системе")
        state = PairState.Showing(qr, System.currentTimeMillis() + QR_LIFETIME_MS)
        main.postDelayed(expire, QR_LIFETIME_MS)
    }

    /** Сканировать: ждём QR от камеры. */
    fun startScan() {
        reset(Mode.Scan)
        state = PairState.Scanning
    }

    /** Камера прочитала строку. Чужой QR — подсказка, сканирование продолжается. */
    fun onQrText(text: String) {
        if (mode != Mode.Scan || state != PairState.Scanning) return
        val content = try {
            qrDecode(text)
        } catch (_: SignalFfiException) {
            notice = "Это не QR приложения"
            return
        }
        if (content.identityKey.contentEquals(myIdentity)) {
            notice = "Это ваш собственный QR"
            return
        }
        if (checkingQr) return
        checkingQr = true
        work({ known(contactOfIdentity(content.identityKey.direct())) }) { isKnown ->
            checkingQr = false
            if (state != PairState.Scanning) return@work
            if (isKnown) {
                notice = KNOWN
                return@work
            }
            val e = epochNow()
            val tokens = (e - 1..e + 1).mapNotNull { uuidOf(blePairingToken(content.secret.direct(), it.toULong())) }
            val partKey = runCatching { blePartKeyPairing(content.secret.direct()) }.getOrNull()
                ?: return@work fail("Не удалось вычислить ключ Bluetooth")
            scanned = content
            if (!link.start(own = null, search = tokens, partKey = partKey)) return@work fail("Включите Bluetooth в системе")
            state = PairState.Exchanging
            main.postDelayed(expire, EXCHANGE_TIMEOUT_MS)
        }
    }

    /** Рядом: объявляем общую метку и ищем такие же. */
    fun startNearby() {
        reset(Mode.Near)
        val e = epochNow()
        val own = NearbyBeacon.own(e) ?: return fail("Не удалось вычислить метку Bluetooth")
        val partKey = NearbyBeacon.ownPartKey() ?: return fail("Не удалось вычислить ключ Bluetooth")
        if (!link.start(own = own, search = NearbyBeacon.wanted(e), partKey = partKey)) return fail("Включите Bluetooth в системе")
        state = PairState.Searching(emptyList())
        main.postDelayed(expire, NEARBY_LIFETIME_MS)
    }

    /** «Добавить» у телефона из списка: шлём ему свою карточку без доказательства. */
    fun pick(phone: NearbyPhone) {
        if (mode != Mode.Near || state !is PairState.Searching) return
        target = phone.address
        state = PairState.Waiting(phone.name)
        work({ sessions.cardFrame(null) }) { frame -> link.sendToServer(phone.address, frame) }
    }

    /** Ответ на [PairState.Ask]. */
    fun answer(add: Boolean) {
        if (state !is PairState.Ask) return
        val card = askCard
        val acc = accepted
        when {
            // Второй телефон: нам прислали карточку.
            card != null -> {
                askCard = null
                val (device, bytes) = card
                if (!add) {
                    link.sendToClient(device, pairFrameDecline())
                    backToSearch()
                    return
                }
                state = PairState.Waiting(introNames[device] ?: UNNAMED)
                work(
                    { if (known(contactOfCard(bytes.direct()))) null else sessions.acceptCard(bytes.direct(), null, null, hello()) },
                    stale = { st -> st?.let { forgetIfNew(it.contact) } },
                ) { st ->
                    if (st == null) {
                        link.sendToClient(device, pairFrameDecline())
                        return@work failSoon(KNOWN_FAILED)
                    }
                    started[device] = st
                    link.sendToClient(device, st.frame)
                }
            }
            // Выбравший телефон: второй подтвердил и начал сессию.
            acc != null -> {
                val (address, a) = acc
                if (!add) {
                    accepted = null
                    forgetLater(a.contact)
                    link.sendToServer(address, pairFrameDecline())
                    backToSearch()
                    return
                }
                state = PairState.Waiting(a.hello.name)
                work({ sessions.replyFrame(a.contact, hello()) }) { reply -> sendReply(address, reply, a, verified = false) }
            }
        }
    }

    /** Вкладка без Bluetooth («Ссылка») или «Рядом» до поиска. */
    fun idle() = reset(Mode.None)

    /** «Искать снова» после неудачи. */
    fun retryNearby() = startNearby()

    /** Экран закрыт: всё незаконченное забывается. */
    fun close() {
        reset(Mode.None)
        worker.shutdown()
    }

    private fun reset(m: Mode) {
        synchronized(gate) { generation++ }
        link.stop()
        main.removeCallbacks(expire)
        // Начатые, но не законченные сессии — не контакты.
        val unfinished = started.values.map { it.contact } + listOfNotNull(accepted?.second?.contact)
        unfinished.forEach(::forgetLater)
        secret?.fill(0)
        secret = null
        scanned = null
        target = null
        started = HashMap()
        accepted = null
        claimed = null
        checkingQr = false
        askCard = null
        introNames.clear()
        phones.clear()
        rssi.clear()
        mode = m
        state = PairState.Idle
    }

    private fun onExpired() {
        when (mode) {
            Mode.Show -> showQr() // одноразовый код сменился
            Mode.Scan -> fail("Телефон с этим QR не найден рядом. Ничего не сохранено.")
            Mode.Near -> if (state is PairState.Searching) {
                link.stop()
                state = PairState.Idle
            }
            Mode.None -> Unit
        }
    }

    private fun backToSearch() {
        target = null
        state = PairState.Searching(phones.values.toList())
    }

    private fun done(name: String, verified: Boolean) {
        link.stop()
        main.removeCallbacks(expire)
        started.clear()
        accepted = null
        secret?.fill(0)
        secret = null
        state = PairState.Done(name, verified)
    }

    /** Неудача, но Bluetooth не закрываем сразу: только что поставленный отказ должен уйти. */
    private fun failSoon(text: String) {
        main.removeCallbacks(expire)
        state = PairState.Failed(text)
    }

    private fun fail(text: String) {
        link.stop()
        main.removeCallbacks(expire)
        state = PairState.Failed(text)
    }

    /**
     * Работа с ядром в отдельном потоке, итог — в главном; ошибка ядра — [onCoreError].
     * [block] получает номер обмена (для [ifLive]). Обмен сменился, пока шла работа, — итог
     * не применяется, а [stale] (в фоновом потоке) убирает созданное им (начатую сессию).
     */
    private fun <T> work(block: (Long) -> T, stale: (T) -> Unit = {}, then: (T) -> Unit) {
        if (worker.isShutdown) return
        val g = generation
        worker.execute {
            val r = try {
                Result.success(block(g))
            } catch (_: Cancelled) {
                return@execute
            } catch (e: SignalFfiException) {
                Result.failure(e)
            } catch (e: android.database.SQLException) {
                Result.failure(e)
            } catch (e: java.security.GeneralSecurityException) {
                Result.failure(e)
            }
            if (generation != g) {
                r.onSuccess { runCatching { stale(it) } }
                return@execute
            }
            main.post {
                if (generation != g) {
                    r.onSuccess { v -> if (!worker.isShutdown) worker.execute { runCatching { stale(v) } } }
                    return@post
                }
                r.fold(then, ::onCoreError)
            }
        }
    }

    /** Записать контакт ([save]), только если обмен [g] не отменён; атомарно с [reset]. */
    private fun <T> ifLive(g: Long, save: () -> T): T = synchronized(gate) {
        if (generation != g) throw Cancelled()
        save()
    }

    /** Фоновый поток: контакт уже в книге? */
    private fun known(contact: String): Boolean = book.find(contact) != null

    /** Фоновый поток: стереть незаконченную сессию, если это не добавленный контакт. */
    private fun forgetIfNew(contact: String) {
        if (!known(contact)) sessions.forget(contact)
    }

    private fun forgetLater(contact: String) {
        if (!worker.isShutdown) worker.execute { runCatching { forgetIfNew(contact) } }
    }

    /**
     * Шлём последний кадр (`Reply`) и записываем контакт, когда другой телефон его принял.
     * Соединение пропало раньше — ничего не записано ([onLost]); сессию сотрёт [reset].
     */
    private fun sendReply(address: String, reply: ByteArray, a: AcceptedSession, verified: Boolean) {
        accepted = address to a
        val g = generation
        link.sendToServer(address, reply) {
            if (generation != g) return@sendToServer
            work({ ifLive(it) { book.save(a.contact, a.hello.name, a.identityKey, a.hello.beaconKey, verified) } }) {
                accepted = null
                done(a.hello.name, verified)
            }
        }
    }

    private fun onCoreError(e: Throwable) {
        val text = when (e) {
            is SignalFfiException.Mismatch -> "Ключи не совпали с QR — контакт не добавлен."
            is SignalFfiException.Protocol, is SignalFfiException.BadInput -> "Телефон рядом прислал неверные ключи. Ничего не сохранено."
            else -> "Не удалось сохранить ключи на этом телефоне."
        }
        if (mode == Mode.Show) {
            // Чужая или испорченная карточка не ломает показ QR.
            if (started.isEmpty()) claimed = null
            notice = text
        } else {
            fail(text)
        }
    }

    // ================= Bluetooth =================

    override fun onPeerSeen(address: String, rssi: Int) {
        this.rssi[address] = rssi
        when (mode) {
            Mode.Scan -> if (target == null) {
                target = address
                link.connect(address)
            }
            Mode.Near -> {
                link.connect(address)
                phones[address]?.let { phones[address] = it.copy(strong = rssi >= STRONG_RSSI) }
            }
            else -> Unit
        }
    }

    override fun onReady(address: String) {
        when (mode) {
            Mode.Scan -> if (address == target) {
                val s = scanned?.secret ?: return
                work({ sessions.cardFrame(s) }) { frame -> link.sendToServer(address, frame) }
            }
            Mode.Near -> link.sendToServer(address, pairFrameIntro(myName))
            else -> Unit
        }
    }

    /** Мы — клиент: ответ сервера, к которому подключились. */
    override fun onFromServer(address: String, frame: ByteArray) {
        val msg = decode(frame) ?: return
        when {
            mode == Mode.Near && msg is PairMessage.Intro -> {
                phones[address] = NearbyPhone(address, msg.name, (rssi[address] ?: -100) >= STRONG_RSSI)
                if (state is PairState.Searching) state = PairState.Searching(phones.values.toList())
            }
            mode == Mode.Scan && address == target && msg is PairMessage.Start -> {
                val expected = scanned?.identityKey ?: return
                if (accepted != null) return // ответ уже отправлен
                work(
                    {
                        if (known(contactOfStart(msg.ciphertext.direct()))) {
                            null
                        } else {
                            val a = sessions.acceptStart(msg.ciphertext.direct(), expected)
                            a to sessions.replyFrame(a.contact, hello())
                        }
                    },
                    stale = { r -> r?.let { forgetIfNew(it.first.contact) } },
                ) { r ->
                    if (r == null) return@work fail(KNOWN_FAILED)
                    sendReply(address, r.second, r.first, verified = true)
                    state = PairState.Exchanging
                }
            }
            mode == Mode.Scan && address == target && msg is PairMessage.Decline ->
                fail("Другой телефон отказал: вы уже есть в его контактах. Ничего не сохранено.")
            mode == Mode.Near && address == target && msg is PairMessage.Start -> {
                if (accepted != null) return
                work(
                    { if (known(contactOfStart(msg.ciphertext.direct()))) null else sessions.acceptStart(msg.ciphertext.direct(), null) },
                    stale = { a -> a?.let { forgetIfNew(it.contact) } },
                ) { a ->
                    if (a == null) {
                        link.sendToServer(address, pairFrameDecline())
                        return@work failSoon(KNOWN_FAILED)
                    }
                    accepted = address to a
                    state = PairState.Ask(a.hello.name)
                }
            }
            mode == Mode.Near && address == target && msg is PairMessage.Decline ->
                state = PairState.Failed("${phones[address]?.name ?: UNNAMED} отклонил(а) запрос. Ничего не сохранено.")
            else -> Unit
        }
    }

    /** Мы — сервер: кадр от подключившегося клиента. */
    override fun onFromClient(device: String, frame: ByteArray) {
        val msg = decode(frame) ?: return
        when {
            mode == Mode.Show && msg is PairMessage.Card && msg.proof != null -> {
                val s = secret ?: return
                // Одно добавление на один QR: место занимается до фоновой работы.
                if (claimed != null) return
                claimed = device
                work(
                    { if (known(contactOfCard(msg.card.direct()))) null else sessions.acceptCard(msg.card.direct(), msg.proof, s, hello()) },
                    stale = { st -> st?.let { forgetIfNew(it.contact) } },
                ) { st ->
                    if (st == null) {
                        claimed = null
                        link.sendToClient(device, pairFrameDecline())
                        notice = KNOWN
                        return@work
                    }
                    started[device] = st
                    link.sendToClient(device, st.frame)
                    state = PairState.Exchanging
                }
            }
            mode == Mode.Show && msg is PairMessage.Reply -> {
                val st = started[device] ?: return
                work({ g ->
                    val h = sessions.openReply(st.contact, msg.ciphertext.direct())
                    ifLive(g) { book.save(st.contact, h.name, st.identityKey, h.beaconKey, verified = true) }
                    h
                }) { h -> done(h.name, verified = true) }
            }
            mode == Mode.Near && msg is PairMessage.Intro -> {
                introNames[device] = msg.name
                link.sendToClient(device, pairFrameIntro(myName))
            }
            mode == Mode.Near && msg is PairMessage.Card && msg.proof == null -> {
                // Одно предложение за раз; пока ждём своё — чужие не показываем.
                if (state !is PairState.Searching || askCard != null) return
                askCard = device to msg.card
                work({ known(contactOfCard(msg.card.direct())) }) { isKnown ->
                    if (askCard?.first != device) return@work
                    if (isKnown || state !is PairState.Searching) {
                        // Уже в контактах или пока проверяли, выбрали другой телефон.
                        askCard = null
                        link.sendToClient(device, pairFrameDecline())
                        if (isKnown) notice = "${introNames[device] ?: UNNAMED} уже в ваших контактах"
                        return@work
                    }
                    state = PairState.Ask(introNames[device] ?: UNNAMED)
                }
            }
            mode == Mode.Near && msg is PairMessage.Reply -> {
                val st = started[device] ?: return
                work({ g ->
                    val h = sessions.openReply(st.contact, msg.ciphertext.direct())
                    ifLive(g) { book.save(st.contact, h.name, st.identityKey, h.beaconKey, verified = false) }
                    h
                }) { h -> done(h.name, verified = false) }
            }
            mode == Mode.Near && msg is PairMessage.Decline -> {
                val st = started.remove(device) ?: return
                forgetLater(st.contact)
                state = PairState.Failed("${introNames[device] ?: UNNAMED} отклонил(а) запрос. Ничего не сохранено.")
            }
            else -> Unit
        }
    }

    override fun onLost(address: String) {
        if (mode == Mode.Near) {
            phones.remove(address)
            when {
                state is PairState.Searching -> state = PairState.Searching(phones.values.toList())
                address == target && (state is PairState.Waiting || state is PairState.Ask) ->
                    state = PairState.Failed("Телефон отошёл слишком далеко. Ничего не сохранено.")
            }
        }
        if (mode == Mode.Scan && address == target && state == PairState.Exchanging) {
            if (accepted != null) {
                // Ответ ушёл, но не подтверждён: контакт не записан, второй телефон его тоже не запишет.
                fail("Связь прервалась до конца обмена. Ничего не сохранено — попробуйте ещё раз.")
            } else {
                target = null // найдём снова и подключимся заново
            }
        }
    }

    override fun onError(text: String) {
        notice = text
    }

    private fun decode(frame: ByteArray): PairMessage? =
        try {
            pairFrameDecode(frame.direct())
        } catch (_: SignalFfiException) {
            null
        }

    companion object {
        /** Секрет QR живёт не дольше 10 минут (ADR-62 п.2); показываем 5, затем новый. */
        const val QR_LIFETIME_MS = 5 * 60 * 1000L
        private const val EXCHANGE_TIMEOUT_MS = 60 * 1000L
        private const val NEARBY_LIFETIME_MS = 10 * 60 * 1000L
        private const val STRONG_RSSI = -70
        private const val UNNAMED = "Без имени"
        private const val KNOWN = "Этот контакт уже добавлен"
        private const val KNOWN_FAILED = "Этот контакт уже добавлен. Ничего не изменено."
    }
}
