package dev.sm.messenger.diagnostics

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import dev.sm.messenger.BuildConfig
import dev.sm.messenger.store.SnapshotCipher
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.CompletableFuture

internal data class JournalView(
    val ready: Boolean = false,
    val enabled: Boolean = false,
    val unavailable: Boolean = false,
    val busy: Boolean = false,
    val entries: List<JournalEntry> = emptyList(),
    val lost: Long = 0,
)

/** Только debug. Все Keystore/файлы — в отдельном ограниченном потоке, никогда в потоке обмена. */
internal object Diagnostics {
    @Volatile private var writer: JournalWriter? = null

    fun initialize(context: Context) {
        if (writer != null) return
        synchronized(this) {
            if (writer != null) return
            try {
                val app = context.applicationContext
                writer = runCatching {
                    JournalWriter(EncryptedJournal(File(app.noBackupFilesDir, "debug-journal.bin")),
                        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode)
                }.getOrNull()
            } catch (_: SecurityException) {
                // Запрет доступа к каталогу журнала не влияет на работу приложения.
            }
        }
    }

    fun begin(event: DiagnosticEvent): Long {
        val w = writer ?: return 0
        if (!w.collecting) return 0
        val attempt = w.attempt()
        record(event, DiagnosticResult.Start, attempt)
        return attempt
    }

    fun record(event: DiagnosticEvent, result: DiagnosticResult = DiagnosticResult.Ok, attempt: Long = 0L, value: Int = 0) {
        writer?.record(event, result, attempt, value)
    }

    fun snapshot(): CompletableFuture<JournalView> = writer?.snapshot() ?: CompletableFuture.completedFuture(JournalView(ready = true, unavailable = true))
    fun toggle(enabled: Boolean): CompletableFuture<JournalView> = writer?.toggle(enabled) ?: snapshot()
    fun clear(): CompletableFuture<JournalView> = writer?.clear() ?: snapshot()
    fun report(view: JournalView): ByteArray = JournalReport.text(
        view.entries, view.lost, writer?.versionCode ?: 0, Build.VERSION.SDK_INT,
        BuildConfig.SOURCE_SHA, System.currentTimeMillis(), BuildConfig.VERSION_NAME,
    ).toByteArray(Charsets.UTF_8)
}

/** AtomicFile — только ciphertext. Старый снимок остаётся при сбое записи, подмена — отказ. */
internal class EncryptedJournal(file: File) : JournalStorage {
    private val file = AtomicFile(file)
    private val cipher = SnapshotCipher(SnapshotCipher.STORE_KEY)

    override fun load(): ByteArray? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        try {
            return file.openRead().use { input ->
                val buffer = ByteArray(JournalCore.MAX_BYTES + 30)
                var size = 0
                while (size < buffer.size) {
                    val n = input.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    if (n == 0) throw IOException("journal read")
                    size += n
                }
                if (size > JournalCore.MAX_BYTES + 29) throw IOException("journal size")
                val sealed = buffer.copyOf(size)
                cipher.open(AAD, sealed) ?: throw IOException("journal authentication")
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("journal key", e)
        } catch (e: SecurityException) {
            throw IOException("journal access", e)
        }
    }

    override fun save(bytes: ByteArray) {
        try {
            val sealed = cipher.seal(AAD, bytes)
            val out = file.startWrite()
            try {
                out.write(sealed)
                file.finishWrite(out)
            } catch (e: IOException) {
                file.failWrite(out)
                throw e
            }
        } catch (e: GeneralSecurityException) {
            throw IOException("journal key", e)
        } catch (e: SecurityException) {
            throw IOException("journal access", e)
        }
    }

    private companion object { const val AAD = "debug-journal/v1" }
}
