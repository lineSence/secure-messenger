package dev.sm.messenger.contacts

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import dev.sm.messenger.diagnostics.Diagnostics
import dev.sm.messenger.diagnostics.DiagnosticEvent
import dev.sm.messenger.diagnostics.DiagnosticResult
import dev.sm.messenger.ble.direct
import dev.sm.messenger.ble.inboxOf
import java.util.UUID
import uniffi.sm_ffi.ChunkFfiException
import uniffi.sm_ffi.ChunkReassembler
import uniffi.sm_ffi.blePartsSeal
import uniffi.sm_ffi.chunkMaxLen
import uniffi.sm_ffi.chunkMinLen

/**
 * Bluetooth на время экрана «Новый контакт» (ADR-62, UX-25). Отдельно от [dev.sm.messenger.ble.BleLink]:
 * живёт, только пока открыт экран, и метка у него своя — метка сопряжения из QR или общая метка
 * «Добавить рядом»; меток контактов он не знает.
 *
 * Один сервис GATT (UUID = метка) с характеристикой «труба» ([inboxOf] метки): подключившийся
 * клиент **пишет** в неё с подтверждением, сервер **отвечает** индикациями (тоже с
 * подтверждением) по тому же соединению. Так обмен кадрами — запрос и ответ — идёт по одному
 * соединению и не нужно сопоставлять адреса (Android объявляет и подключается с разных
 * случайных адресов). Кадр режется на части ядром (`sm_core::chunk`), части — строго по одной.
 * Части в эфире зашифрованы целиком ключом частей экрана (ADR-92): из секрета QR или общим
 * ключом «Добавить рядом»; в обе стороны — один ключ.
 *
 * Метка не меняется, пока экран открыт: секрет QR живёт не дольше 10 минут, поиск «Рядом» —
 * тоже; искатель ищет метки эпох t−1..t+1, поэтому одна граница эпохи не мешает.
 *
 * Состояние меняется только в главном потоке; обратные вызовы GATT переносятся туда.
 * Содержимое кадров в журнал не пишется.
 */
@SuppressLint("MissingPermission") // разрешения проверяет экран до start()
class PairLink(context: Context, private val listener: Listener) {
    /** События; вызываются в главном потоке. */
    interface Listener {
        /** Поиск нашёл телефон с нужной меткой (адрес объявления). */
        fun onPeerSeen(address: String, rssi: Int)

        /** Подключились к телефону [address] как клиент — можно писать. */
        fun onReady(address: String)

        /** Кадр от сервера телефона [address], к которому подключились мы. */
        fun onFromServer(address: String, frame: ByteArray)

        /** Кадр от клиента [device], подключившегося к нам. */
        fun onFromClient(device: String, frame: ByteArray)

        /** Соединение (любое из двух видов) с [address] пропало. */
        fun onLost(address: String)

        fun onError(text: String)
    }

    private val app = context.applicationContext
    private val manager: BluetoothManager? = app.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var server: BluetoothGattServer? = null
    private var pipe: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var wanted: List<UUID> = emptyList()
    private var running = false

    /** Кадр в очереди; [sent] — вызвать, когда другой телефон подтвердил все части. */
    private class Pending(val frame: ByteArray, val sent: (() -> Unit)?)

    /** Кадры в одну сторону: очередь, части текущего кадра, номер передачи. */
    private class Outgoing {
        val queue = ArrayDeque<Pending>()
        var parts: ArrayDeque<ByteArray>? = null
        var chunkLen = MIN_CHUNK
    }

    private class Client(val gatt: BluetoothGatt, val reassembler: ChunkReassembler) {
        var pipe: BluetoothGattCharacteristic? = null
        var ready = false
        val out = Outgoing()
    }

    private class Peer(val device: BluetoothDevice, val reassembler: ChunkReassembler) {
        var subscribed = false
        val out = Outgoing()
    }

    /** Ключ частей текущего экрана ([start]). */
    private var key = ByteArray(0)

    private val clients = HashMap<String, Client>()
    private val peers = HashMap<String, Peer>()

    /**
     * [own] — метка, под которой нас видно (объявление и сервис); `null` — только ищем.
     * [search] — метки, которые ищем; пусто — только ждём подключений.
     * [partKey] — ключ частей (ADR-92): `blePartKeyPairing` по секрету QR или `blePartKeyNearby`.
     * `false` — Bluetooth выключен или нет объявлений LE.
     */
    fun start(own: UUID?, search: List<UUID>, partKey: ByteArray): Boolean {
        if (running) stop()
        Diagnostics.record(DiagnosticEvent.PairSearch, DiagnosticResult.Start)
        key = partKey
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false
        running = true
        wanted = search
        if (own != null) {
            val adv = adapter.bluetoothLeAdvertiser
            val s = bt { manager?.openGattServer(app, serverCallback) }
            if (adv == null || s == null) return fail("Телефон не поддерживает объявления Bluetooth LE")
            server = s
            val service = BluetoothGattService(own, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val c = BluetoothGattCharacteristic(
                inboxOf(own),
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            c.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            service.addCharacteristic(c)
            if (bt { s.addService(service) } != true) return fail("Не удалось открыть сервис Bluetooth")
            pipe = c
            advertiser = adv
            bt { adv.startAdvertising(advertiseSettings(), advertiseData(own), advertiseCallback) }
        }
        if (search.isNotEmpty()) {
            val scn = adapter.bluetoothLeScanner ?: return fail("Поиск Bluetooth LE недоступен")
            scanner = scn
            val filters = search.map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
            bt { scn.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback) }
        }
        return true
    }

    private fun fail(text: String): Boolean {
        stop()
        listener.onError(text)
        return false
    }

    /** Всё закрывается, очереди очищаются. */
    fun stop() {
        running = false
        bt { scanner?.stopScan(scanCallback) }
        bt { advertiser?.stopAdvertising(advertiseCallback) }
        scanner = null
        advertiser = null
        clients.values.forEach { closeClient(it) }
        clients.clear()
        peers.values.forEach { it.reassembler.close() }
        peers.clear()
        bt { server?.close() }
        server = null
        pipe = null
    }

    /** Подключиться к найденному телефону; повторный вызов для того же адреса ничего не делает. */
    fun connect(address: String) {
        val adapter = manager?.adapter ?: return
        if (!running || address in clients || clients.size >= MAX_LINKS) return
        val device = bt { adapter.getRemoteDevice(address) } ?: return
        val g = bt { device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE) } ?: return
        val r = reassembler()
        if (r == null) {
            bt { g.close() }
            return
        }
        clients[address] = Client(g, r)
    }

    fun disconnect(address: String) {
        clients.remove(address)?.let { closeClient(it) }
    }

    /**
     * Кадр серверу телефона [address] (мы — клиент). Части пишутся с ответом, поэтому [sent]
     * вызывается, когда сервер принял последнюю часть; не вызывается, если соединение пропало
     * ([Listener.onLost]) или кадр не встал в очередь ([Listener.onError]).
     */
    fun sendToServer(address: String, frame: ByteArray, sent: (() -> Unit)? = null) {
        val c = clients[address] ?: return listener.onError("Телефон рядом отключился")
        if (c.out.queue.size >= MAX_QUEUE) return listener.onError("Очередь Bluetooth переполнена")
        c.out.queue.addLast(Pending(frame, sent))
        pumpClient(c)
    }

    /** Кадр клиенту [device], подключившемуся к нам (мы — сервер). */
    fun sendToClient(device: String, frame: ByteArray, sent: (() -> Unit)? = null) {
        val p = peers[device] ?: return listener.onError("Телефон рядом отключился")
        if (p.out.queue.size >= MAX_QUEUE) return listener.onError("Очередь Bluetooth переполнена")
        p.out.queue.addLast(Pending(frame, sent))
        pumpPeer(p)
    }

    // ================= клиент =================

    private fun clientOf(g: BluetoothGatt): Pair<String, Client>? =
        clients.entries.firstOrNull { it.value.gatt === g }?.let { it.key to it.value }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                val (address, _) = clientOf(g) ?: return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                        if (bt { g.requestMtu(REQUEST_MTU) } != true) bt { g.discoverServices() }
                    else -> lostClient(address)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                val (address, c) = clientOf(g) ?: return@post
                if (status == BluetoothGatt.GATT_SUCCESS) c.out.chunkLen = chunkLenFor(mtu)
                if (bt { g.discoverServices() } != true) lostClient(address)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val (address, c) = clientOf(g) ?: return@post
                Diagnostics.record(DiagnosticEvent.PairGatt, if (status == BluetoothGatt.GATT_SUCCESS) DiagnosticResult.Ok else DiagnosticResult.Failed, value = status.coerceIn(0, 1024))
                val svc = g.services.firstOrNull { it.uuid in wanted }
                val ch = svc?.getCharacteristic(inboxOf(svc.uuid))
                val d = ch?.getDescriptor(CCCD)
                if (status != BluetoothGatt.GATT_SUCCESS || ch == null || d == null) {
                    Diagnostics.record(DiagnosticEvent.PairGatt, DiagnosticResult.Protocol)
                    lostClient(address)
                    return@post
                }
                c.pipe = ch
                if (bt { g.setCharacteristicNotification(ch, true) } != true || !writeCccd(g, d)) lostClient(address)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post {
                val (address, c) = clientOf(g) ?: return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    lostClient(address)
                    return@post
                }
                c.ready = true
                listener.onReady(address)
                pumpClient(c)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            main.post {
                val (address, c) = clientOf(g) ?: return@post
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    nextClient(c)
                } else {
                    // Сервер не принял часть — соединение больше не годится, начинаем заново.
                    lostClient(address)
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { fromServer(g, copy) }
        }

        @Deprecated("До Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val copy = ch.value?.copyOf() ?: return
            main.post { fromServer(g, copy) }
        }
    }

    private fun fromServer(g: BluetoothGatt, part: ByteArray) {
        val (address, c) = clientOf(g) ?: return
        val frame = push(c.reassembler, part) ?: return
        if (frame.isEmpty()) lostClient(address) else listener.onFromServer(address, frame)
    }

    private fun writeCccd(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean {
        val v = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bt { g.writeDescriptor(d, v) } == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            bt {
                d.value = v
                g.writeDescriptor(d)
            } == true
        }
    }

    private fun pumpClient(c: Client) {
        if (!c.ready || c.out.parts != null) return
        if (!startFrame(c.out)) return
        nextClient(c)
    }

    private fun nextClient(c: Client) {
        val parts = c.out.parts ?: return
        val next = parts.removeFirstOrNull()
        if (next == null) {
            c.out.parts = null
            val finished = c.out.queue.removeFirstOrNull()
            pumpClient(c)
            // Последним: обработчик может закрыть Bluetooth ([stop]).
            finished?.sent?.invoke()
            return
        }
        val ch = c.pipe ?: return
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bt { c.gatt.writeCharacteristic(ch, next, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) } == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            bt {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ch.value = next
                c.gatt.writeCharacteristic(ch)
            } == true
        }
        if (!ok) clientOf(c.gatt)?.let { lostClient(it.first) }
    }

    private fun lostClient(address: String) {
        val c = clients.remove(address) ?: return
        closeClient(c)
        listener.onLost(address)
    }

    private fun closeClient(c: Client) {
        bt { c.gatt.disconnect() }
        bt { c.gatt.close() }
        c.reassembler.close()
    }

    // ================= сервер =================

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (running && device.address !in peers && peers.size < MAX_LINKS) {
                        reassembler()?.let { peers[device.address] = Peer(device, it) }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    peers.remove(device.address)?.let {
                        it.reassembler.close()
                        listener.onLost(device.address)
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            main.post { peers[device.address]?.out?.chunkLen = chunkLenFor(mtu) }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            main.post {
                val p = peers[device.address]
                val ok = p != null && descriptor.uuid == CCCD && !preparedWrite && offset == 0 && value != null
                if (ok) {
                    p.subscribed = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                    pumpPeer(p)
                }
                if (responseNeeded) {
                    val status = if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    bt { server?.sendResponse(device, requestId, status, 0, null) }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val copy = value?.copyOf()
            main.post {
                val p = peers[device.address]
                val status = when {
                    p == null || characteristic.uuid != pipe?.uuid -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                    preparedWrite || offset != 0 || copy == null -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    else -> {
                        val frame = push(p.reassembler, copy)
                        when {
                            frame == null -> BluetoothGatt.GATT_SUCCESS
                            frame.isEmpty() -> BluetoothGatt.GATT_FAILURE
                            else -> {
                                // Ответ GATT — до обработки кадра: обработка может сразу отправить ответный кадр.
                                main.post { listener.onFromClient(device.address, frame) }
                                BluetoothGatt.GATT_SUCCESS
                            }
                        }
                    }
                }
                if (responseNeeded) bt { server?.sendResponse(device, requestId, status, 0, null) }
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            main.post { bt { server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null) } }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                val p = peers[device.address] ?: return@post
                if (status == BluetoothGatt.GATT_SUCCESS) nextPeer(p) else dropPeer(p)
            }
        }
    }

    private fun pumpPeer(p: Peer) {
        if (!p.subscribed || p.out.parts != null) return
        if (!startFrame(p.out)) return
        nextPeer(p)
    }

    private fun nextPeer(p: Peer) {
        val parts = p.out.parts ?: return
        val next = parts.removeFirstOrNull()
        if (next == null) {
            p.out.parts = null
            val finished = p.out.queue.removeFirstOrNull()
            pumpPeer(p)
            finished?.sent?.invoke()
            return
        }
        val s = server ?: return
        val ch = pipe ?: return
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bt { s.notifyCharacteristicChanged(p.device, ch, true, next) } == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            bt {
                ch.value = next
                s.notifyCharacteristicChanged(p.device, ch, true)
            } == true
        }
        if (!ok) dropPeer(p)
    }

    /** Клиент не подтвердил часть — разрываем соединение, он подключится заново. */
    private fun dropPeer(p: Peer) {
        peers.remove(p.device.address)
        p.reassembler.close()
        bt { server?.cancelConnection(p.device) }
        listener.onLost(p.device.address)
    }

    // ================= общее =================

    /** Режет первый кадр очереди на части; `false` — очередь пуста. */
    private fun startFrame(out: Outgoing): Boolean {
        while (true) {
            val frame = out.queue.firstOrNull()?.frame ?: return false
            if (out.chunkLen < chunkMinLen().toInt()) {
                out.queue.clear()
                listener.onError("Bluetooth телефона рядом не принимает части нужного размера (MTU)")
                return false
            }
            val parts = blePartsSeal(key.direct(), frame.direct(), out.chunkLen.toUInt())
            if (parts != null) {
                out.parts = ArrayDeque(parts)
                return true
            }
            out.queue.removeFirst()
            listener.onError("Кадр слишком большой для Bluetooth")
        }
    }

    /** Сборщик частей на ключе экрана; `null` — ключ не задан или не той длины. */
    private fun reassembler(): ChunkReassembler? =
        try {
            ChunkReassembler(key.direct())
        } catch (_: ChunkFfiException) {
            null
        }

    /** Часть в сборщик: кадр целиком, `null` — ждём ещё, пустой массив — ошибка разбора. */
    private fun push(r: ChunkReassembler, part: ByteArray): ByteArray? =
        try {
            r.push(part.direct())
        } catch (_: ChunkFfiException) {
            ByteArray(0)
        }

    private fun chunkLenFor(mtu: Int) = (mtu - ATT_OVERHEAD).coerceIn(MIN_CHUNK, chunkMaxLen().toInt())

    private fun advertiseSettings() = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(true)
        .build()

    // Без имени устройства и мощности: в объявлении только метка (UX-24).
    private fun advertiseData(own: UUID) = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(own))
        .build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Diagnostics.record(DiagnosticEvent.PairAdvertise, DiagnosticResult.Failed, value = errorCode.coerceIn(0, 255))
            main.post { listener.onError("Не удалось включить видимость (код $errorCode)") }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val rssi = result.rssi
            main.post {
                if (running) {
                    Diagnostics.record(DiagnosticEvent.PairSearch, DiagnosticResult.Ok)
                    listener.onPeerSeen(address, rssi)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Diagnostics.record(DiagnosticEvent.PairSearch, DiagnosticResult.Failed, value = errorCode.coerceIn(0, 255))
            main.post { listener.onError("Поиск телефонов не запустился (код $errorCode)") }
        }
    }

    /** Стек Bluetooth бросает исключения при выключении адаптера или отзыве разрешения. */
    private inline fun <T> bt(block: () -> T): T? =
        try {
            block()
        } catch (_: SecurityException) {
            main.post { listener.onError("Нет разрешения «Устройства поблизости»") }
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        /** Client Characteristic Configuration — стандартный дескриптор подписки. */
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val REQUEST_MTU = 517
        const val ATT_OVERHEAD = 3
        const val MIN_CHUNK = 20

        /** Соединений каждого вида: в «Рядом» список короткий. */
        const val MAX_LINKS = 6
        const val MAX_QUEUE = 8
    }
}
