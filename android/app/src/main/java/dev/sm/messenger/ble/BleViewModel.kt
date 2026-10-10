package dev.sm.messenger.ble

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
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
 * Связывает [BleLink] с интерфейсом. Живёт, пока жива активность (поворот экрана не рвёт связь);
 * при уничтожении — всё останавливается.
 *
 * Проверка связи: после подключения уходит проверочный кадр [PROBE_LEN] байт — он режется на
 * части и собирается ядром на другом телефоне. Получатель сверяет его с [probeBlob].
 */
class BleViewModel(app: Application) : AndroidViewModel(app), BleLink.Listener {
    private val link = BleLink(app, this)

    var mode by mutableStateOf(BleMode.Off)
        private set
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
        getApplication<Application>().checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
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

    fun turnOn() {
        if (!hasPermissions()) {
            permissionDenied = true
            return
        }
        if (!link.start()) {
            notice = "Включите Bluetooth в системе"
            return
        }
        mode = BleMode.Scan
    }

    fun turnOff() {
        link.stop()
        mode = BleMode.Off
        peers = emptyList()
        current = null
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

    override fun onCleared() {
        link.stop()
    }

    companion object {
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
