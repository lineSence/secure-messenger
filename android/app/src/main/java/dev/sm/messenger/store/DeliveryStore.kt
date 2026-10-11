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
 * Запись — в отдельном потоке, не чаще раза в секунду после изменения ([changed]), сразу
 * в [flush] (`onStop`) и в вызывающем потоке — [saveOutboxNow] (новое сообщение).
 *
 * Снимки читаются при первом обращении к [outbox] или [seen] — не в главном потоке (снимок
 * до 32 МиБ). Повреждённый или подменённый снимок очереди не заменяется молча: файл
 * откладывается (`.broken`), а [outboxLost] сообщает, что прежняя очередь пропала, — свои
 * сообщения «в очереди» тогда становятся «не доставлено» ([dev.sm.messenger.chat.ChatHub]).
 */
class DeliveryStore private constructor(context: Context) {
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

    /** Снимок очереди был, но не открылся — прежняя очередь пропала (известно после [outbox]). */
    @Volatile var outboxLost = false
        private set

    /** Запись снимков — по одной: снимок берётся и пишется под замком, старый не затрёт новый. */
    private val writeLock = Any()

    val outbox: OutboxQueue by lazy {
        val restored = load(OUTBOX)?.let { data ->
            data.getOrNull()?.let { runCatching { OutboxQueue.restore(it.direct(), restoreBoot, now()) }.getOrNull() }
                ?: run {
                    outboxLost = true
                    setAside(OUTBOX)
                    null
                }
        }
        restored ?: OutboxQueue()
    }

    val seen: SeenWindow by lazy {
        // Окно — только защита от повторов (история отсекает дубли по id): испорченное — пустое.
        load(SEEN)?.getOrNull()?.let { runCatching { SeenWindow.restore(it.direct()) }.getOrNull() } ?: SeenWindow()
    }

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

    /**
     * Записать снимок очереди сейчас, в вызывающем потоке (не главном). `true` — файл записан
     * и в нём всё, что было в очереди до вызова.
     */
    fun saveOutboxNow(): Boolean = synchronized(writeLock) {
        val plain = runCatching { outbox.encode(encodeBoot) }.getOrNull() ?: return@synchronized false
        write(OUTBOX, plain)
    }

    private fun save() {
        // Снимок не получился (состояние сломано паникой) — прежний файл не трогаем.
        synchronized(writeLock) {
            runCatching { outbox.encode(encodeBoot) }.getOrNull()?.let { write(OUTBOX, it) }
            runCatching { seen.encode() }.getOrNull()?.let { write(SEEN, it) }
        }
    }

    /** `null` — файла нет; [Result.failure] — есть, но не прочитался или не расшифровался. */
    private fun load(name: String): Result<ByteArray>? {
        val data = try {
            AtomicFile(File(dir, name)).readFully()
        } catch (_: FileNotFoundException) {
            return null
        } catch (e: IOException) {
            return Result.failure(e)
        }
        return cipher.open(name, data)?.let { Result.success(it) } ?: Result.failure(IOException("снимок $name не открылся"))
    }

    /** Испорченный снимок — в сторону (`<имя>.broken`, прежний такой же заменяется), не под новую запись. */
    private fun setAside(name: String) {
        val f = File(dir, name)
        f.renameTo(File(dir, "$name.broken"))
        File(dir, "$name.bak").delete() // резервная копия AtomicFile, иначе она вернулась бы как снимок
    }

    private fun write(name: String, plain: ByteArray): Boolean {
        val sealed = runCatching { cipher.seal(name, plain) }.getOrNull() ?: return false
        val f = AtomicFile(File(dir, name))
        val out = try {
            f.startWrite()
        } catch (_: IOException) {
            return false
        }
        return try {
            out.write(sealed)
            f.finishWrite(out)
            true
        } catch (_: IOException) {
            f.failWrite(out)
            false
        }
    }

    fun now(): ULong = SystemClock.elapsedRealtime().toULong()

    companion object {
        @Volatile private var instance: DeliveryStore? = null

        /** Один на процесс: очередь нужна и экранам, и фоновой связи ([dev.sm.messenger.chat.ChatHub]). */
        fun get(context: Context): DeliveryStore =
            instance ?: synchronized(this) { instance ?: DeliveryStore(context.applicationContext).also { instance = it } }

        private const val OUTBOX = "outbox.v1"
        private const val SEEN = "seen.v1"
        // BOOT_COUNT — Int, эти значения с ним не совпадут.
        private val UNKNOWN_BOOT_WRITE: ULong = 1uL shl 63
        private val UNKNOWN_BOOT_READ: ULong = (1uL shl 63) + 1uL
    }
}
