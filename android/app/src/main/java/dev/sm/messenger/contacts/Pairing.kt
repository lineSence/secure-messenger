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
import uniffi.sm_ffi.SignalSessions
import uniffi.sm_ffi.StartedSession
import uniffi.sm_ffi.AcceptedSession
import uniffi.sm_ffi.blePairingToken
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
 * Добавление контакта при встрече (ADR-62, UX-25). Криптография — в ядре ([SignalSessions]);
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
 */
class Pairing(
    context: Context,
    private val sessions: SignalSessions,
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
    private var askCard: Pair<String, ByteArray>? = null
    private val introNames = HashMap<String, String>()
    private val phones = LinkedHashMap<String, NearbyPhone>()
    private val rssi = HashMap<String, Int>()
    private val expire = Runnable { onExpired() }

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
        if (!link.start(own = token, search = emptyList())) return fail("Включите Bluetooth в системе")
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
        val e = epochNow()
        val tokens = (e - 1..e + 1).mapNotNull { uuidOf(blePairingToken(content.secret.direct(), it.toULong())) }
        scanned = content
        if (!link.start(own = null, search = tokens)) return fail("Включите Bluetooth в системе")
        state = PairState.Exchanging
        main.postDelayed(expire, EXCHANGE_TIMEOUT_MS)
    }

    /** Рядом: объявляем общую метку и ищем такие же. */
    fun startNearby() {
        reset(Mode.Near)
        val e = epochNow()
        val own = NearbyBeacon.own(e) ?: return fail("Не удалось вычислить метку Bluetooth")
        if (!link.start(own = own, search = NearbyBeacon.wanted(e))) return fail("Включите Bluetooth в системе")
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
                work({ sessions.acceptCard(bytes.direct(), null, null, hello()) }) { st ->
                    started[device] = st
                    link.sendToClient(device, st.frame)
                }
            }
            // Выбравший телефон: второй подтвердил и начал сессию.
            acc != null -> {
                accepted = null
                val (address, a) = acc
                if (!add) {
                    worker.execute { runCatching { sessions.forget(a.contact) } }
                    link.sendToServer(address, pairFrameDecline())
                    backToSearch()
                    return
                }
                work({
                    val reply = sessions.replyFrame(a.contact, hello())
                    book.save(a.contact, a.hello.name, a.identityKey, a.hello.beaconKey, verified = false)
                    reply
                }) { reply ->
                    link.sendToServer(address, reply)
                    done(a.hello.name, verified = false)
                }
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
        link.stop()
        main.removeCallbacks(expire)
        // Начатые, но не законченные сессии — не контакты.
        val unfinished = started.values.map { it.contact } + listOfNotNull(accepted?.second?.contact)
        if (unfinished.isNotEmpty() && !worker.isShutdown) {
            worker.execute { unfinished.forEach { runCatching { sessions.forget(it) } } }
        }
        secret?.fill(0)
        secret = null
        scanned = null
        target = null
        started = HashMap()
        accepted = null
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
        secret?.fill(0)
        secret = null
        state = PairState.Done(name, verified)
    }

    private fun fail(text: String) {
        link.stop()
        main.removeCallbacks(expire)
        state = PairState.Failed(text)
    }

    /** Работа с ядром в отдельном потоке, итог — в главном; ошибка ядра — [onCoreError]. */
    private fun <T> work(block: () -> T, then: (T) -> Unit) {
        if (worker.isShutdown) return
        val m = mode
        worker.execute {
            val r = try {
                Result.success(block())
            } catch (e: SignalFfiException) {
                Result.failure(e)
            } catch (e: android.database.SQLException) {
                Result.failure(e)
            } catch (e: java.security.GeneralSecurityException) {
                Result.failure(e)
            }
            main.post {
                if (mode != m) return@post
                r.fold(then, ::onCoreError)
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
                work({
                    val a = sessions.acceptStart(msg.ciphertext.direct(), expected)
                    val reply = sessions.replyFrame(a.contact, hello())
                    book.save(a.contact, a.hello.name, a.identityKey, a.hello.beaconKey, verified = true)
                    a to reply
                }) { (a, reply) ->
                    link.sendToServer(address, reply)
                    // Даём ответу уйти до закрытия Bluetooth.
                    main.postDelayed({ done(a.hello.name, verified = true) }, SEND_GRACE_MS)
                    state = PairState.Exchanging
                }
            }
            mode == Mode.Near && address == target && msg is PairMessage.Start -> {
                work({ sessions.acceptStart(msg.ciphertext.direct(), null) }) { a ->
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
                if (started.isNotEmpty()) return // одно добавление на один QR
                work({ sessions.acceptCard(msg.card.direct(), msg.proof, s, hello()) }) { st ->
                    started[device] = st
                    link.sendToClient(device, st.frame)
                    state = PairState.Exchanging
                }
            }
            mode == Mode.Show && msg is PairMessage.Reply -> {
                val st = started[device] ?: return
                work({
                    val h = sessions.openReply(st.contact, msg.ciphertext.direct())
                    book.save(st.contact, h.name, st.identityKey, h.beaconKey, verified = true)
                    h
                }) { h -> done(h.name, verified = true) }
            }
            mode == Mode.Near && msg is PairMessage.Intro -> {
                introNames[device] = msg.name
                link.sendToClient(device, pairFrameIntro(myName))
            }
            mode == Mode.Near && msg is PairMessage.Card && msg.proof == null -> {
                // Одно предложение за раз; пока ждём своё — чужие не показываем.
                if (state !is PairState.Searching) return
                askCard = device to msg.card
                state = PairState.Ask(introNames[device] ?: UNNAMED)
            }
            mode == Mode.Near && msg is PairMessage.Reply -> {
                val st = started[device] ?: return
                work({
                    val h = sessions.openReply(st.contact, msg.ciphertext.direct())
                    book.save(st.contact, h.name, st.identityKey, h.beaconKey, verified = false)
                    h
                }) { h -> done(h.name, verified = false) }
            }
            mode == Mode.Near && msg is PairMessage.Decline -> {
                val st = started.remove(device) ?: return
                worker.execute { runCatching { sessions.forget(st.contact) } }
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
            target = null // найдём снова и подключимся заново
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
        private const val SEND_GRACE_MS = 1500L
        private const val STRONG_RSSI = -70
        private const val UNNAMED = "Без имени"
    }
}
