package dev.sm.messenger.ble

import android.Manifest
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.database.sqlite.SQLiteException
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.store.SecureDb
import java.security.GeneralSecurityException
import java.util.UUID

/** Состояние экрана «Bluetooth рядом» (UX-23, макет ui-v6). */
enum class BleMode { Off, Scan, Conn, Lost }

/**
 * Телефон рядом. Адрес — случайный адрес Bluetooth LE, который Android периодически меняет;
 * [contact] и [name] — контакт, чья метка в объявлении (ADR-61), `null` — метку не узнали.
 */
data class NearPeer(
    val address: String,
    val rssi: Int,
    val seenAt: Long,
    val name: String? = null,
    val contact: String? = null,
) {
    val label: String get() = name ?: ("Телефон " + address.takeLast(LABEL_CHARS))

    val strong: Boolean get() = rssi >= STRONG_RSSI

    private companion object {
        const val LABEL_CHARS = 5
        const val STRONG_RSSI = -70
    }
}

/**
 * Связывает [BleLink] с интерфейсом, сообщениями ([ChatHub]) и фоновой службой. Один на процесс
 * ([get]): живёт, пока жив процесс, а не активность, — связь не рвётся при закрытии экрана
 * (ADR-59, UX-18). Пока связь включена, работает [BleService] с уведомлением «Соединение
 * активно»; она и держит процесс.
 *
 * Состояние меняется только в главном потоке (обратные вызовы [BleLink] приходят туда).
 *
 * Доставка (ADR-40): увидели метку контакта, которому есть что отправить, — подключаемся
 * клиентом GATT; после подключения [ChatHub.collect] заново шифрует всё из очереди и
 * подтверждения. Пока подключены — раз в [TICK_MS] досылаем то, чему подошёл срок повтора.
 * Клиентом подключаемся к одному телефону за раз; принимать кадры (сервер GATT) можем от всех.
 */
class BleHub private constructor(private val app: Application) : BleLink.Listener {
    private val link = BleLink(app, this, ContactBeacon)
    private val chat = ChatHub.get(app)
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (mode == BleMode.Off) return
            val c = current?.contact
            if (mode == BleMode.Conn && c != null) flush(c, reconnect = false)
            main.postDelayed(this, TICK_MS)
        }
    }

    /** Служба подписывается, чтобы обновлять текст уведомления. */
    var onChanged: (() -> Unit)? = null

    private var modeState by mutableStateOf(BleMode.Off)
    var mode: BleMode
        get() = modeState
        private set(value) {
            modeState = value
            onChanged?.invoke()
        }
    var peers by mutableStateOf(emptyList<NearPeer>())
        private set
    var current by mutableStateOf<NearPeer?>(null)
        private set
    var permissionDenied by mutableStateOf(false)
        private set

    /** Короткое сообщение для всплывающей подсказки; интерфейс показывает и сбрасывает. */
    var notice by mutableStateOf<String?>(null)

    init {
        chat.onWork = { kick(it) }
    }

    fun hasPermissions(): Boolean = PERMISSIONS.all {
        app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapterEnabled(): Boolean = link.adapterEnabled()

    /** Итог системного окна разрешений. */
    fun onPermissionResult(granted: Boolean) {
        permissionDenied = !granted
        if (granted) turnOn()
    }

    /** После возврата из настроек Android разрешение могло появиться. */
    fun refreshPermission() {
        if (permissionDenied && hasPermissions()) permissionDenied = false
        if (mode != BleMode.Off && !hasPermissions()) {
            turnOff()
            permissionDenied = true
        }
    }

    /** Контакт [address] сейчас виден рядом. */
    fun isNear(address: String): Boolean = peers.any { it.contact == address }

    /**
     * Включает связь и фоновую службу. `false` — нет разрешений или Bluetooth выключен.
     * [fromService] — вызов из самой службы (перезапуск системой): службу второй раз не запускаем.
     */
    fun turnOn(fromService: Boolean = false): Boolean {
        if (!ContactBeacon.ready && !loadContacts()) {
            notice = "Ключи контактов не открываются"
            return false
        }
        if (!hasPermissions()) {
            permissionDenied = true
            return false
        }
        if (!link.start()) {
            notice = "Включите Bluetooth в системе"
            return false
        }
        if (mode == BleMode.Off) {
            mode = BleMode.Scan
            main.removeCallbacks(tick)
            main.postDelayed(tick, TICK_MS)
        }
        if (!fromService) startService()
        return true
    }

    /** Запуск из фона Android запрещает — тогда связь работает, пока открыто приложение. */
    private fun startService() {
        try {
            app.startForegroundService(Intent(app, BleService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException — подкласс IllegalStateException.
            notice = "Связь в фоне не запустилась: работает, пока приложение открыто"
        }
    }

    fun turnOff() {
        main.removeCallbacks(tick)
        link.stop()
        mode = BleMode.Off
        peers = emptyList()
        current = null
        app.stopService(Intent(app, BleService::class.java))
    }

    /** Текст уведомления службы: что со связью сейчас. */
    fun statusLine(): String {
        val name = current?.label
        return when {
            mode == BleMode.Conn && name != null -> "Bluetooth рядом · подключено: $name"
            mode == BleMode.Lost && name != null -> "Bluetooth рядом · $name вне досягаемости"
            else -> "Bluetooth рядом · поиск телефонов"
        }
    }

    fun connect(peer: NearPeer) {
        current = peer
        link.connect(peer.address)
    }

    /** Убирает телефоны, которых давно не видно; интерфейс вызывает раз в несколько секунд. */
    fun prune() {
        val now = BleLink.now()
        val fresh = peers.filter { now - it.seenAt < BleLink.PEER_TIMEOUT_MS }
        if (fresh.size != peers.size) peers = fresh
    }

    override fun onPeerSeen(address: String, rssi: Int, service: UUID?) {
        val now = BleLink.now()
        val known = service?.let { ContactBeacon.contactOf(it, epochNow()) }
        val peer = NearPeer(address, rssi, now, known?.name, known?.address)
        peers = (peers.filter { it.address != address && now - it.seenAt < BleLink.PEER_TIMEOUT_MS } + peer)
            .sortedBy { it.label }
        autoConnect(peer)
    }

    /**
     * Подключиться ли к [peer]: тот же контакт после разрыва (адрес мог смениться) — да; другой —
     * только если ему есть что отправить, а текущему — нечего.
     */
    private fun autoConnect(peer: NearPeer) {
        val who = peer.contact ?: return
        if (mode == BleMode.Off) return
        val cur = current
        if (cur?.contact == who) {
            if (mode == BleMode.Lost || (mode == BleMode.Scan && cur.address != peer.address)) connect(peer)
            return
        }
        val busy = mode == BleMode.Conn && cur?.contact != null && cur.contact in chat.waiting
        if (!busy && who in chat.waiting) connect(peer)
    }

    /** Для контакта появилось что отправить (новое сообщение или подтверждение). */
    private fun kick(address: String) {
        if (mode == BleMode.Off) return
        val cur = current
        if (cur?.contact == address && mode == BleMode.Conn) {
            flush(address, reconnect = false)
            return
        }
        peers.firstOrNull { it.contact == address }?.let(::autoConnect)
    }

    private fun flush(address: String, reconnect: Boolean) {
        chat.collect(address, reconnect) { frames ->
            if (mode == BleMode.Conn && current?.contact == address) frames.forEach(link::send)
        }
    }

    override fun onConnected(address: String) {
        mode = BleMode.Conn
        // Кадры, зашифрованные для прошлого подключения, не досылаем — очередь зашифрует заново.
        link.clearQueue()
        current?.contact?.let { flush(it, reconnect = true) }
    }

    override fun onDisconnected(address: String) {
        if (mode != BleMode.Off && current?.address == address) mode = BleMode.Lost
    }

    override fun onFrameSent() = Unit

    override fun onFrameReceived(mailbox: ByteArray, blob: ByteArray) {
        chat.onFrame(blob, peers.mapNotNull { it.contact })
    }

    override fun onError(text: String) {
        notice = text
    }

    /**
     * Ключи маяка: свой и контактов — из базы (Keystore, SQLite). Вызывается при включении, если
     * ещё не загружены (в том числе при перезапуске службы системой), и после добавления контакта.
     */
    fun loadContacts(): Boolean {
        val book = chat.book
        val ok = try {
            val known = book.list().map { ContactBeacon.Known(it.address, it.name, it.beaconKey) }
            ContactBeacon.update(book.ownBeaconKey(), known)
            true
        } catch (_: SQLiteException) {
            false
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: SecureDb.CorruptRow) {
            false
        }
        // Поиск и реклама живут в главном потоке; загрузка может идти и из фонового.
        if (ok) main.post { link.refreshSearch() }
        return ok
    }

    companion object {
        @Volatile private var instance: BleHub? = null

        /** Единственный [BleHub] процесса. */
        fun get(app: Application): BleHub =
            instance ?: synchronized(this) { instance ?: BleHub(app).also { instance = it } }

        /** Разрешения «Устройства поблизости» (API 31+, ADR-45). */
        val PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )

        /** Как часто досылать очередь подключённому контакту. */
        private const val TICK_MS = 30_000L
    }
}
