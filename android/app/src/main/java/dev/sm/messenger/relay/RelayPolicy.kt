package dev.sm.messenger.relay

/** Почему не вышло обратиться к узлу; от этого зависит, менять ли домен (ADR-86). */
enum class RelayFailure {
    /** Похоже на блокировку: сброс, подмена сертификата или ответа, чужой HTTP-ответ. */
    Blocked,

    /** Нет сети, имя не разрешилось, соединение не установилось. */
    Unreachable,

    /** Не уложились во время. */
    Timeout,

    /** Узел отказал (предел, неверный запрос, временно недоступен): домен ни при чём. */
    Rejected,

    /** Очереди нет — узел перезапускался (ADR-82 п.4): создать очередь заново. */
    NoQueue,
}

/** Ошибка обращения к узлу. */
class RelayException(val failure: RelayFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/** Источник случайности для джиттера: число из `[0, 1)`. Не секрет, но берётся из `SecureRandom`. */
fun interface Jitter {
    fun next(): Double
}

/**
 * Перебор доменов узла (ADR-86): по порядку со случайного места; при блокировке или
 * недоступности — сразу следующий, при тайм-ауте — если успешного ответа не было [FREEZE_MS]
 * (признак заморозки, `overview.md`). Время — монотонное, в миллисекундах.
 */
class DomainRotation(private val size: Int, start: Int, now: Long) {
    init {
        require(size > 0 && start in 0 until size)
    }

    var current: Int = start
        private set
    private var lastOk = now

    fun onSuccess(now: Long) {
        lastOk = now
    }

    /** `true` — перешли к следующему домену. */
    fun onFailure(failure: RelayFailure, now: Long): Boolean {
        val next = when (failure) {
            RelayFailure.Blocked, RelayFailure.Unreachable -> true
            RelayFailure.Timeout -> now - lastOk >= FREEZE_MS
            RelayFailure.Rejected, RelayFailure.NoQueue -> false
        }
        if (next) {
            current = (current + 1) % size
            lastOk = now
        }
        return next
    }

    companion object {
        const val FREEZE_MS = 120_000L
    }
}

/**
 * Сроки обращений к узлу (ADR-82 п.3, ADR-85, ADR-93):
 * - мобильная сеть — опрос с интервалом [POLL_MIN_MS]…[POLL_MAX_MS]: при тишине удваивается,
 *   после почты — снова минимальный; джиттер ±[JITTER] от интервала;
 * - повтор после ошибки — [RETRY_MIN_MS]…[RETRY_MAX_MS], удвоение, джиттер «полный» (0…пауза);
 * - канал по Wi-Fi живёт случайные [CHANNEL_MIN_MS]…[CHANNEL_MAX_MS], потом замена.
 */
class RelaySchedule(private val jitter: Jitter) {
    private var poll = POLL_MIN_MS
    private var retry = 0

    /** Пауза до следующего опроса на мобильной сети. */
    fun pollDelayMs(): Long = spread(poll)

    /** Опрос принёс почту: снова часто. */
    fun onMail() {
        poll = POLL_MIN_MS
    }

    /** Опрос пустой: реже, до [POLL_MAX_MS]. */
    fun onQuiet() {
        poll = (poll * 2).coerceAtMost(POLL_MAX_MS)
    }

    /** Пауза перед повтором после очередной ошибки подряд. */
    fun retryDelayMs(): Long {
        val cap = (RETRY_MIN_MS shl retry.coerceAtMost(RETRY_SHIFT_MAX)).coerceAtMost(RETRY_MAX_MS)
        retry++
        return RETRY_MIN_MS + (jitter.next() * (cap - RETRY_MIN_MS)).toLong()
    }

    /** Обращение удалось: ошибки подряд кончились. */
    fun onSuccess() {
        retry = 0
    }

    /** Срок жизни нового канала по Wi-Fi (ADR-93). */
    fun channelLifetimeMs(): Long =
        CHANNEL_MIN_MS + (jitter.next() * (CHANNEL_MAX_MS - CHANNEL_MIN_MS)).toLong()

    private fun spread(base: Long): Long = (base * (1 - JITTER + 2 * JITTER * jitter.next())).toLong()

    companion object {
        const val POLL_MIN_MS = 2 * 60_000L
        const val POLL_MAX_MS = 15 * 60_000L
        const val JITTER = 0.25
        const val RETRY_MIN_MS = 5_000L
        const val RETRY_MAX_MS = 5 * 60_000L
        private const val RETRY_SHIFT_MAX = 10
        const val CHANNEL_MIN_MS = 5 * 60_000L
        const val CHANNEL_MAX_MS = 15 * 60_000L
    }
}
