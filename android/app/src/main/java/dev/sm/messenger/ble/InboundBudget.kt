package dev.sm.messenger.ble

/**
 * Предел принятых, но ещё не обработанных кадров Bluetooth (ревью v0.0.1-alpha, high: очередь
 * кадров от любого соседа по радио была неограниченной). Кадр занимает место от приёма в
 * [BleLink] до конца обработки в `ChatHub.onFrame`; места нет — часть отвергается ответом GATT
 * `GATT_FAILURE`, настоящий отправитель повторит позже. Сосед может занять предел мусором —
 * это отказ в обслуживании по радио, как и глушилка, но не рост памяти.
 */
object InboundBudget {
    const val MAX_FRAMES = 32
    const val MAX_BYTES = 1L shl 20

    private var frames = 0
    private var bytes = 0L

    /** Занять место под кадр [size] байт; `false` — предел исчерпан. */
    @Synchronized
    fun tryAcquire(size: Int): Boolean {
        if (frames >= MAX_FRAMES || bytes + size > MAX_BYTES) return false
        frames++
        bytes += size
        return true
    }

    /** Кадр [size] байт обработан (или отброшен). */
    @Synchronized
    fun release(size: Int) {
        if (frames > 0) frames--
        bytes = (bytes - size).coerceAtLeast(0L)
    }
}
