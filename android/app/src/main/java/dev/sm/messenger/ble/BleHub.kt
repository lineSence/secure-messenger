package dev.sm.messenger.ble

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import uniffi.sm_ffi.encodeFrame

/** Состояние экрана «Bluetooth рядом» (UX-23, макет ui-v6). */
enum class BleMode { Off, Scan, Conn, Lost }

/** Телефон рядом. Адрес — случайный адрес Bluetooth LE, который Android периодически меняет. */
data class NearPeer(val address: String, val rssi: Int, val seenAt: Long) {
    /** Контактов пока нет (шаг libsignal): телефон называется по концу адреса. */
    val label: String get() = "Телефон " + address.takeLast(LABEL_CHARS)

    val strong: Boolean get() = rssi >= STRONG_RSSI

    private companion object {
        const val LABEL_CHARS = 5
        const val STRONG_RSSI = -70
    }
}

/**
 * Связывает [BleLink] с интерфейсом и фоновой службой. Один на процесс ([get]): живёт, пока жив
 * процесс, а не активность, — связь не рвётся при закрытии экрана (ADR-59, UX-18). Пока связь
 * включена, работает [BleService] с уведомлением «Соединение активно»; она и держит процесс.
 *
 * Состояние меняется только в главном потоке (обратные вызовы [BleLink] приходят туда).
 *
 * Проверка связи: после подключения уходит проверочный кадр [PROBE_LEN] байт — он режется на
 * части и собирается ядром на другом телефоне. Получатель сверяет его с [probeBlob].
 */
class BleHub private constructor(private val app: Application) : BleLink.Listener {
    private val link = BleLink(app, this)

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
    var received by mutableIntStateOf(0)
        private set

    /** Короткое сообщение для всплывающей подсказки; интерфейс показывает и сбрасывает. */
    var notice by mutableStateOf<String?>(null)

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

    /**
     * Включает связь и фоновую службу. `false` — нет разрешений или Bluetooth выключен.
     * [fromService] — вызов из самой службы (перезапуск системой): службу второй раз не запускаем.
     */
    fun turnOn(fromService: Boolean = false): Boolean {
        if (!hasPermissions()) {
            permissionDenied = true
            return false
        }
        if (!link.start()) {
            notice = "Включите Bluetooth в системе"
            return false
        }
        if (mode == BleMode.Off) mode = BleMode.Scan
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

    override fun onPeerSeen(address: String, rssi: Int) {
        val now = BleLink.now()
        peers = (peers.filter { it.address != address && now - it.seenAt < BleLink.PEER_TIMEOUT_MS } +
            NearPeer(address, rssi, now)).sortedBy { it.address }
        // Собеседник вернулся — подключаемся снова, очередь уйдёт сама (ADR-40).
        val c = current
        if (mode == BleMode.Lost && c != null && c.address == address) link.connect(address)
    }

    override fun onConnected(address: String) {
        mode = BleMode.Conn
        val frame = encodeFrame(ByteArray(MAILBOX_LEN).direct(), probeBlob.direct()) ?: return
        // Проверочный кадр — только если прошлый ещё не в очереди.
        if (link.pending() == 0) link.send(frame)
    }

    override fun onDisconnected(address: String) {
        if (mode != BleMode.Off && current?.address == address) mode = BleMode.Lost
    }

    override fun onFrameSent() {
        notice = "Подключено · проверочный кадр отправлен"
    }

    override fun onFrameReceived(mailbox: ByteArray, blob: ByteArray) {
        received += 1
        notice = if (blob.contentEquals(probeBlob)) "Получен проверочный кадр · собран целиком" else "Получен кадр · ${blob.size} байт"
    }

    override fun onError(text: String) {
        notice = text
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

        private const val MAILBOX_LEN = 32
        private const val PROBE_LEN = 3000
        private const val PROBE_MOD = 251

        /** Проверочный блоб: не кратен размеру части, поэтому последняя часть короче остальных. */
        val probeBlob: ByteArray = ByteArray(PROBE_LEN) { (it % PROBE_MOD).toByte() }
    }
}
