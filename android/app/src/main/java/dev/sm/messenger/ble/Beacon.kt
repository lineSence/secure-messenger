package dev.sm.messenger.ble

import java.nio.ByteBuffer
import java.util.UUID
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.bleAddNearbyToken
import uniffi.sm_ffi.bleContactToken
import uniffi.sm_ffi.bleEpochMs
import uniffi.sm_ffi.blePartKeyContact
import uniffi.sm_ffi.blePartKeyNearby

/**
 * Откуда [BleLink] берёт метки поиска (ADR-61): UUID в объявлении и UUID сервиса GATT меняются
 * каждую эпоху (15 минут), постоянного UUID, по которому сканер узнал бы приложение, нет.
 */
interface Beacon {
    /** Метка этого телефона в эпоху [epoch] — её объявляем и под ней держим сервис GATT. */
    fun own(epoch: Long): UUID?

    /** Метки, которые ищем в эпоху [epoch] (с соседними эпохами — часы телефонов расходятся). */
    fun wanted(epoch: Long): List<UUID>

    /** Ключ частей для входящих (ADR-92): ими пишут нам. `null` — ключа ещё нет. */
    fun ownPartKey(): ByteArray?

    /** Ключ частей, чтобы писать телефону с меткой [service] в эпоху около [epoch]. */
    fun partKeyOf(service: UUID, epoch: Long): ByteArray?
}

/**
 * Метка экрана «Новый контакт → Рядом»: общая для всех телефонов с приложением, меняется каждые
 * 15 минут (ADR-62 п.3). Только для [dev.sm.messenger.contacts.PairLink], пока открыт экран.
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

    /** Ключ частей экрана добавления — общий, как и метка (ADR-92). */
    private val partKey: ByteArray? by lazy { runCatching { blePartKeyNearby() }.getOrNull() }

    override fun ownPartKey(): ByteArray? = partKey

    override fun partKeyOf(service: UUID, epoch: Long): ByteArray? = partKey

    private const val MAX_CACHE = 8
}

/**
 * Метки контактов (ADR-61 п.1–3): свою объявляем по своему ключу маяка, ищем — по ключам маяка
 * контактов на эпохи t−1, t, t+1. Чужой телефон без ключа метку не вычислит. Нет контактов —
 * искать нечего (поиск не запускается), но своя метка объявляется: её знают только контакты.
 *
 * Ключи приходят из базы ([update]); метки считает ядро и кэширует по эпохе.
 */
object ContactBeacon : Beacon {
    /** Контакт для поиска: адрес Signal, имя и его ключ маяка. */
    class Known(val address: String, val name: String, val key: ByteArray) {
        /** Ключ частей, чтобы писать ему (ADR-92); `null` — ключ маяка испорчен. */
        val partKey: ByteArray? by lazy { partKeyFor(key) }
    }

    @Volatile private var ownKey: ByteArray? = null
    @Volatile private var ownPart: ByteArray? = null
    @Volatile private var known: List<Known> = emptyList()
    private var cacheEpoch = Long.MIN_VALUE
    private val owners = HashMap<UUID, Known>()

    /** Ключ загружен — можно объявлять себя. */
    val ready: Boolean get() = ownKey != null

    @Synchronized
    fun update(own: ByteArray, contacts: List<Known>) {
        ownKey = own
        ownPart = partKeyFor(own)
        known = contacts
        cacheEpoch = Long.MIN_VALUE
    }

    override fun own(epoch: Long): UUID? = ownKey?.let { token(it, epoch) }

    override fun wanted(epoch: Long): List<UUID> = table(epoch).keys.toList()

    override fun ownPartKey(): ByteArray? = ownPart

    override fun partKeyOf(service: UUID, epoch: Long): ByteArray? = contactOf(service, epoch)?.partKey

    /** Чья метка — для списка «Телефоны рядом» и чтобы знать, кому слать очередь. */
    fun contactOf(uuid: UUID, epoch: Long): Known? = table(epoch)[uuid]

    @Synchronized
    private fun table(epoch: Long): Map<UUID, Known> {
        if (epoch != cacheEpoch) {
            owners.clear()
            for (k in known) for (e in epoch - 1..epoch + 1) token(k.key, e)?.let { owners[it] = k }
            cacheEpoch = epoch
        }
        return HashMap(owners)
    }

    private fun partKeyFor(key: ByteArray): ByteArray? =
        try {
            blePartKeyContact(key.direct())
        } catch (_: SignalFfiException) {
            null
        }

    private fun token(key: ByteArray, epoch: Long): UUID? =
        try {
            uuidOf(bleContactToken(key.direct(), epoch.toULong()))
        } catch (_: SignalFfiException) {
            null
        }
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
