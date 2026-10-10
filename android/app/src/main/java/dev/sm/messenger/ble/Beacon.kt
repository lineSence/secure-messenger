package dev.sm.messenger.ble

import java.nio.ByteBuffer
import java.util.UUID
import uniffi.sm_ffi.bleAddNearbyToken
import uniffi.sm_ffi.bleEpochMs

/**
 * Откуда [BleLink] берёт метки поиска (ADR-61): UUID в объявлении и UUID сервиса GATT меняются
 * каждую эпоху (15 минут), постоянного UUID, по которому сканер узнал бы приложение, нет.
 */
interface Beacon {
    /** Метка этого телефона в эпоху [epoch] — её объявляем и под ней держим сервис GATT. */
    fun own(epoch: Long): UUID?

    /** Метки, которые ищем в эпоху [epoch] (с соседними эпохами — часы телефонов расходятся). */
    fun wanted(epoch: Long): List<UUID>
}

/**
 * Пока контактов нет (тестовый экран): метка экрана добавления, общая для всех телефонов с
 * приложением и меняющаяся каждые 15 минут (ADR-62 п.3). Метки контактов — с добавлением
 * контакта (пункт 5 плана).
 */
object NearbyBeacon : Beacon {
    private val cache = HashMap<Long, UUID>()

    @Synchronized
    override fun own(epoch: Long): UUID? {
        cache[epoch]?.let { return it }
        val t = bleAddNearbyToken(epoch.toULong())?.let(::uuidOf) ?: return null
        if (cache.size > MAX_CACHE) cache.clear()
        cache[epoch] = t
        return t
    }

    override fun wanted(epoch: Long): List<UUID> = (epoch - 1..epoch + 1).mapNotNull(::own)

    private const val MAX_CACHE = 8
}

/** Номер текущей эпохи меток по часам телефона. */
fun epochNow(): Long = System.currentTimeMillis() / bleEpochMs().toLong()

/** Сколько миллисекунд до начала следующей эпохи. */
fun msToNextEpoch(): Long {
    val len = bleEpochMs().toLong()
    return len - System.currentTimeMillis() % len
}

/** 16 байт метки → UUID. */
internal fun uuidOf(bytes: ByteArray): UUID? {
    if (bytes.size != UUID_BYTES) return null
    val b = ByteBuffer.wrap(bytes)
    return UUID(b.long, b.long)
}

/**
 * Характеристика «входящие» выводится из UUID сервиса (последний бит инвертирован): постоянного
 * UUID нет и у неё — подключившийся чужой телефон не свяжет сервисы разных эпох.
 */
internal fun inboxOf(service: UUID): UUID = UUID(service.mostSignificantBits, service.leastSignificantBits xor 1L)

private const val UUID_BYTES = 16
