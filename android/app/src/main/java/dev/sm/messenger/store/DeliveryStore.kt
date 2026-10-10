package dev.sm.messenger.store

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import dev.sm.messenger.ble.direct
import uniffi.sm_ffi.OutboxQueue
import uniffi.sm_ffi.SeenWindow
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Очередь отправки и окно полученных между запусками (ADR-40, ADR-56, п. 4–5).
 *
 * Снимки — в `noBackupFilesDir/sm/` (хранилище CE, в резервные копии не попадает), каждый
 * зашифрован [SnapshotCipher] и записан через [AtomicFile]: при сбое остаётся прежний файл.
 * Повреждённый или подменённый снимок отбрасывается — начинаем с пустой очереди, без обхода.
 * Запись — в отдельном потоке, не чаще раза в секунду после изменения ([changed]) и сразу
 * в [flush] (`onStop`).
 */
class DeliveryStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "sm").apply { mkdirs() }
    private val cipher = SnapshotCipher()
    private val io = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sm-store").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null

    /**
     * Номер загрузки для `Outbox`: при его смене ядро переносит отметки времени на новый
     * отсчёт. Если `BOOT_COUNT` не прочитался, номера для записи и чтения различаются —
     * перенос будет при каждом запуске (безопасно, только лишние повторы раньше срока).
     */
    private val bootCount: Long? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toLong()
    } catch (_: Settings.SettingNotFoundException) {
        null
    }
    private val encodeBoot: ULong = bootCount?.toULong() ?: UNKNOWN_BOOT_WRITE
    private val restoreBoot: ULong = bootCount?.toULong() ?: UNKNOWN_BOOT_READ

    val outbox: OutboxQueue = load(OUTBOX)
        ?.let { runCatching { OutboxQueue.restore(it.direct(), restoreBoot, now()) }.getOrNull() }
        ?: OutboxQueue()

    val seen: SeenWindow = load(SEEN)
        ?.let { runCatching { SeenWindow.restore(it.direct()) }.getOrNull() }
        ?: SeenWindow()

    /** Очередь или окно изменились: записать снимки не позже чем через секунду. */
    @Synchronized
    fun changed() {
        if (pending?.isDone == false) return
        pending = io.schedule({ save() }, 1, TimeUnit.SECONDS)
    }

    /**
     * Записать сейчас (`onStop`). Главный поток не ждёт: снимок до 32 МиБ на медленной памяти
     * мог бы вызвать ANR; запись идёт в потоке `sm-store` сразу.
     */
    fun flush() {
        synchronized(this) { pending?.cancel(false) }
        io.execute { save() }
    }

    private fun save() {
        // Снимок не получился (состояние сломано паникой) — прежний файл не трогаем.
        runCatching { outbox.encode(encodeBoot) }.getOrNull()?.let { write(OUTBOX, it) }
        runCatching { seen.encode() }.getOrNull()?.let { write(SEEN, it) }
    }

    private fun load(name: String): ByteArray? {
        val data = try {
            AtomicFile(File(dir, name)).readFully()
        } catch (_: FileNotFoundException) {
            return null
        } catch (_: IOException) {
            return null
        }
        return cipher.open(name, data)
    }

    private fun write(name: String, plain: ByteArray) {
        val sealed = runCatching { cipher.seal(name, plain) }.getOrNull() ?: return
        val f = AtomicFile(File(dir, name))
        val out = try {
            f.startWrite()
        } catch (_: IOException) {
            return
        }
        try {
            out.write(sealed)
            f.finishWrite(out)
        } catch (_: IOException) {
            f.failWrite(out)
        }
    }

    private fun now(): ULong = SystemClock.elapsedRealtime().toULong()

    private companion object {
        const val OUTBOX = "outbox.v1"
        const val SEEN = "seen.v1"
        // BOOT_COUNT — Int, эти значения с ним не совпадут.
        val UNKNOWN_BOOT_WRITE: ULong = 1uL shl 63
        val UNKNOWN_BOOT_READ: ULong = (1uL shl 63) + 1uL
    }
}
