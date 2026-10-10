package dev.sm.messenger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.UUID
import uniffi.sm_ffi.ChunkFfiException
import uniffi.sm_ffi.ChunkReassembler
import uniffi.sm_ffi.chunkMaxLen
import uniffi.sm_ffi.chunkSplit
import uniffi.sm_ffi.parseFrame

/**
 * Bluetooth LE телефон-телефон (ADR-49, тестовый транспорт).
 *
 * Каждый телефон одновременно:
 * - **сервер GATT** — одна характеристика «входящие» только на запись; части кадра собирает
 *   [ChunkReassembler] из ядра, один на соединение (удаляется при разрыве);
 * - **объявляет** себя по метке [Beacon.own] (без имени устройства и мощности передатчика);
 * - **ищет** другие телефоны по меткам [Beacon.wanted] и по «Подключить» становится клиентом GATT.
 *
 * Метки меняются каждую эпоху (15 минут, ADR-61): на границе эпохи объявление и поиск
 * перезапускаются, на сервере добавляется сервис с новой меткой, а сервис прошлой эпохи
 * остаётся ещё на одну — подключённые и опоздавшие по часам клиенты не теряют связь.
 * UUID характеристики выводится из UUID сервиса ([inboxOf]).
 *
 * Клиент пишет части **с подтверждением** (`WRITE_TYPE_DEFAULT`), строго по одной: следующая —
 * только после ответа на предыдущую, поэтому части приходят по порядку. Части не длиннее
 * `MTU − 3`: длинные (prepared) записи сервер отклоняет. Логика разбора — в ядре (`sm_core::chunk`).
 *
 * Все поля клиента меняются только в главном потоке ([main]); обратные вызовы GATT приходят из
 * потоков Binder и переносятся туда. Сборщики сервера — под замком [reassemblers].
 *
 * Содержимое кадров не пишется в журнал: это сообщения, зашифрованные Signal.
 */
@SuppressLint("MissingPermission") // разрешения проверяет BleHub до start()/connect()
class BleLink(context: Context, private val listener: Listener, private val beacon: Beacon) {
    /** События для интерфейса; вызываются в главном потоке. */
    interface Listener {
        /** [service] — метка из объявления, по которой телефон найден. */
        fun onPeerSeen(address: String, rssi: Int, service: UUID?)
        fun onConnected(address: String)
        fun onDisconnected(address: String)
        fun onFrameSent()
        fun onFrameReceived(mailbox: ByteArray, blob: ByteArray)
        fun onError(text: String)
    }

    private val app = context.applicationContext
    private val manager: BluetoothManager? = app.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    // --- сервер ---
    private var server: BluetoothGattServer? = null

    /** Метки сервисов на сервере, старшая — последняя (не больше двух; только главный поток). */
    private val services = ArrayDeque<UUID>()

    /** Характеристики «входящие» живых сервисов — читаются из потоков Binder. */
    @Volatile private var inboxes: Set<UUID> = emptySet()
    private var epoch = 0L
    private val rotateTask = Runnable { rotate() }
    private val reassemblers = HashMap<String, ChunkReassembler>()

    // --- объявление и поиск ---
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    // --- клиент (только главный поток) ---
    private var gatt: BluetoothGatt? = null
    private var target: String? = null
    private var inbox: BluetoothGattCharacteristic? = null
    private var chunkLen = MIN_CHUNK
    private var transfer: UShort = 0u
    private val queue = ArrayDeque<ByteArray>()
    private var sending: ArrayDeque<ByteArray>? = null
    private var attempts = 0

    var running = false
        private set

    /** Bluetooth включён в системе. */
    fun adapterEnabled(): Boolean = manager?.adapter?.isEnabled == true

    /** Запускает сервер, объявление и поиск. `false` — Bluetooth выключен или недоступен. */
    fun start(): Boolean {
        val adapter = manager?.adapter
        if (running) return true
        if (adapter == null || !adapter.isEnabled) return false
        if (!openServer()) return false
        val adv = adapter.bluetoothLeAdvertiser
        val scn = adapter.bluetoothLeScanner
        if (adv == null || scn == null) {
            closeServer()
            listener.onError("Телефон не поддерживает объявления Bluetooth LE")
            return false
        }
        running = true
        advertiser = adv
        scanner = scn
        startBeacon()
        main.postDelayed(rotateTask, msToNextEpoch() + EPOCH_SLACK_MS)
        return true
    }

    private fun startBeacon() {
        val own = services.lastOrNull() ?: return
        runCatchingBt { advertiser?.startAdvertising(advertiseSettings(), advertiseData(own), advertiseCallback) }
        val filters = beacon.wanted(epoch).map { ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build() }
        // Пустой список фильтров — это «все устройства рядом»: искать некого — не ищем.
        if (filters.isNotEmpty()) runCatchingBt { scanner?.startScan(filters, scanSettings(), scanCallback) }
    }

    private fun stopBeacon() {
        runCatchingBt { scanner?.stopScan(scanCallback) }
        runCatchingBt { advertiser?.stopAdvertising(advertiseCallback) }
    }

    /** Граница эпохи: новая метка в объявлении, поиске и на сервере. */
    private fun rotate() {
        if (!running) return
        val e = epochNow()
        if (e != epoch) {
            val own = beacon.own(e)
            if (own == null) {
                listener.onError("Не удалось вычислить метку Bluetooth")
            } else {
                epoch = e
                stopBeacon()
                while (services.size >= MAX_SERVICES) dropService(services.removeFirst())
                if (addService(own)) startBeacon() else listener.onError("Не удалось обновить сервис Bluetooth")
            }
        }
        main.postDelayed(rotateTask, msToNextEpoch() + EPOCH_SLACK_MS)
    }

    /** Список меток для поиска изменился (добавлен контакт): поиск и объявление — заново. */
    fun refreshSearch() {
        if (!running) return
        stopBeacon()
        startBeacon()
    }

    /** Останавливает всё; очередь отправки очищается. */
    fun stop() {
        running = false
        main.removeCallbacks(rotateTask)
        stopBeacon()
        scanner = null
        advertiser = null
        closeClient()
        queue.clear()
        closeServer()
    }

    /**
     * Подключается к телефону как клиент GATT. Прошлое подключение закрывается; повторный вызов
     * для того же телефона, пока подключение идёт или есть, ничего не делает (поиск сообщает о
     * телефоне много раз в секунду).
     */
    fun connect(address: String) {
        val adapter = manager?.adapter ?: return
        if (!running || (target == address && gatt != null)) return
        closeClient()
        target = address
        val device = runCatchingBt { adapter.getRemoteDevice(address) } ?: return
        gatt = device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Ставит кадр в очередь; уходит, когда есть подключение. */
    fun send(frame: ByteArray) {
        if (queue.size >= MAX_QUEUE) {
            listener.onError("Очередь Bluetooth переполнена")
            return
        }
        queue.addLast(frame)
        pump()
    }

    /** Сколько кадров ждут отправки. */
    fun pending(): Int = queue.size

    /**
     * Убирает неотправленные кадры: они зашифрованы для прежнего подключения. Очередь сообщений
     * ([dev.sm.messenger.chat.ChatHub]) после подключения шифрует их заново (ADR-40).
     * Только из [Listener.onConnected]: на новом соединении ещё нет части в полёте.
     */
    fun clearQueue() {
        queue.clear()
        sending = null
        attempts = 0
    }

    // ================= сервер GATT =================

    private fun openServer(): Boolean {
        val s = manager?.openGattServer(app, serverCallback) ?: return false
        server = s
        epoch = epochNow()
        val own = beacon.own(epoch)
        if (own == null || !addService(own)) {
            closeServer()
            return false
        }
        return true
    }

    private fun addService(uuid: UUID): Boolean {
        val s = server ?: return false
        val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                inboxOf(uuid),
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        if (runCatchingBt { s.addService(service) } != true) return false
        services.addLast(uuid)
        inboxes = services.map(::inboxOf).toSet()
        return true
    }

    private fun dropService(uuid: UUID) {
        val s = server ?: return
        runCatchingBt { s.getService(uuid)?.let(s::removeService) }
        inboxes = services.map(::inboxOf).toSet()
    }

    private fun closeServer() {
        runCatchingBt { server?.close() }
        server = null
        services.clear()
        inboxes = emptySet()
        synchronized(reassemblers) {
            reassemblers.values.forEach { it.close() }
            reassemblers.clear()
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // Разрыв — незаконченный кадр уходит вместе со сборщиком.
                synchronized(reassemblers) { reassemblers.remove(device.address)?.close() }
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
            val status = when {
                characteristic.uuid !in inboxes -> BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                preparedWrite || offset != 0 || value == null -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else -> accept(device.address, value)
            }
            if (responseNeeded) runCatchingBt { server?.sendResponse(device, requestId, status, 0, null) }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            // Длинные (prepared) записи не принимаем: часть всегда помещается в одну запись.
            runCatchingBt {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }
    }

    /** Часть от соседа (поток Binder). Ответ GATT: успех — часть принята. */
    private fun accept(address: String, value: ByteArray): Int {
        val r = synchronized(reassemblers) {
            reassemblers[address]
                ?: if (reassemblers.size < MAX_PEERS) ChunkReassembler().also { reassemblers[address] = it } else null
        } ?: return BluetoothGatt.GATT_FAILURE
        val frame = try {
            r.push(value.direct())
        } catch (e: ChunkFfiException) {
            // Сборщик уже отбросил незаконченный кадр; отправитель начнёт с части 0.
            return BluetoothGatt.GATT_FAILURE
        } ?: return BluetoothGatt.GATT_SUCCESS
        val parsed = parseFrame(frame.direct()) ?: return BluetoothGatt.GATT_FAILURE
        // Предел необработанных кадров (InboundBudget); место освобождает ChatHub.onFrame.
        if (!InboundBudget.tryAcquire(parsed.blob.size)) return BluetoothGatt.GATT_FAILURE
        main.post { listener.onFrameReceived(parsed.mailbox, parsed.blob) }
        return BluetoothGatt.GATT_SUCCESS
    }

    // ================= объявление и поиск =================

    private fun advertiseSettings() = AdvertiseSettings.Builder()
        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
        .setConnectable(true)
        .build()

    // Без имени устройства и мощности: в объявлении только UUID сервиса (UX-24).
    private fun advertiseData(own: UUID) = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addServiceUuid(ParcelUuid(own))
        .build()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            main.post { listener.onError("Не удалось включить видимость (код $errorCode)") }
        }
    }

    private fun scanSettings() = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val rssi = result.rssi
            val uuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
            main.post {
                if (!running) return@post
                val wanted = beacon.wanted(epoch)
                listener.onPeerSeen(address, rssi, uuids.firstOrNull { it in wanted })
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { listener.onError("Поиск телефонов не запустился (код $errorCode)") }
        }
    }

    // ================= клиент GATT =================

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                when {
                    newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS ->
                        if (!g.requestMtu(REQUEST_MTU)) g.discoverServices()
                    newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS ->
                        lost()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val max = chunkMaxLen().toInt()
                chunkLen = if (status == BluetoothGatt.GATT_SUCCESS) (mtu - ATT_OVERHEAD).coerceIn(MIN_CHUNK, max) else MIN_CHUNK
                if (!g.discoverServices()) lost()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val wanted = beacon.wanted(epochNow())
                val c = g.services.firstOrNull { it.uuid in wanted }?.let { it.getCharacteristic(inboxOf(it.uuid)) }
                if (status != BluetoothGatt.GATT_SUCCESS || c == null) {
                    listener.onError("На телефоне рядом нет нужного сервиса")
                    lost()
                    return@post
                }
                inbox = c
                target?.let { listener.onConnected(it) }
                pump()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post {
                if (g !== gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) writeNext() else failFrame()
            }
        }
    }

    private fun pump() {
        if (sending != null || inbox == null) return
        val frame = queue.firstOrNull() ?: return
        val chunks = chunkSplit(frame.direct(), transfer, chunkLen.toUInt())
        if (chunks == null) {
            queue.removeFirst()
            listener.onError("Кадр слишком большой для Bluetooth")
            pump()
            return
        }
        transfer = (transfer + 1u).toUShort()
        sending = ArrayDeque(chunks)
        writeNext()
    }

    private fun writeNext() {
        val parts = sending ?: return
        val next = parts.removeFirstOrNull()
        if (next == null) {
            // Последняя часть подтверждена сервером соседа: кадр собран и принят.
            sending = null
            attempts = 0
            queue.removeFirstOrNull()
            listener.onFrameSent()
            pump()
            return
        }
        if (!write(next)) failFrame()
    }

    private fun write(chunk: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = inbox ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = chunk
                g.writeCharacteristic(c)
            }
        }
    }

    /** Кадр не ушёл: начинаем его заново с части 0 (сборщик соседа сбросится), не больше [MAX_ATTEMPTS] раз. */
    private fun failFrame() {
        sending = null
        attempts += 1
        if (attempts >= MAX_ATTEMPTS) {
            attempts = 0
            queue.removeFirstOrNull()
            listener.onError("Кадр не отправлен после $MAX_ATTEMPTS попыток")
        }
        main.postDelayed({ pump() }, RETRY_DELAY_MS)
    }

    /** Связь с соседом пропала: кадр в работе остаётся в очереди и уйдёт заново после подключения. */
    private fun lost() {
        val address = target
        closeClient()
        if (address != null) listener.onDisconnected(address)
    }

    private fun closeClient() {
        runCatchingBt { gatt?.disconnect() }
        runCatchingBt { gatt?.close() }
        gatt = null
        inbox = null
        sending = null
        attempts = 0
        target = null
        chunkLen = MIN_CHUNK
    }

    /** Стек Bluetooth бросает исключения при выключении адаптера или отзыве разрешения. */
    private inline fun <T> runCatchingBt(block: () -> T): T? =
        try {
            block()
        } catch (e: SecurityException) {
            main.post { listener.onError("Нет разрешения «Устройства поблизости»") }
            null
        } catch (e: IllegalStateException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    companion object {
        /** Сервисов на сервере: текущей эпохи и прошлой. */
        private const val MAX_SERVICES = 2

        /** Перезапуск чуть позже границы эпохи, чтобы номер эпохи уже сменился. */
        private const val EPOCH_SLACK_MS = 1_000L

        /** Запрашиваемый MTU: 512 байт данных + 3 байта ATT + запас (как в Android по умолчанию). */
        private const val REQUEST_MTU = 517
        private const val ATT_OVERHEAD = 3

        /** MTU по умолчанию 23 − 3 байта ATT. */
        private const val MIN_CHUNK = 20

        /** Одновременных соседей на сервере: память сборщиков ≤ 4 × предел кадра. */
        private const val MAX_PEERS = 4
        private const val MAX_QUEUE = 64
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 500L

        /** Сосед не виден дольше — убираем из списка. */
        const val PEER_TIMEOUT_MS = 10_000L

        fun now(): Long = SystemClock.elapsedRealtime()
    }
}

/**
 * Байты для ядра: UniFFI передаёт `&[u8]` без копирования и требует прямой (direct) буфер.
 * Копия здесь одна; ядро читает буфер только во время вызова.
 */
internal fun ByteArray.direct(): ByteBuffer {
    val b = ByteBuffer.allocateDirect(size)
    b.put(this)
    b.flip()
    return b
}
