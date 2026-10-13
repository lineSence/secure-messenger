package dev.sm.messenger.diagnostics

import java.io.IOException
import java.io.OutputStream

/** Никакого успеха до закрытия выходного потока; журнал при этом не очищается. */
internal object JournalExport {
    fun write(bytes: ByteArray, open: () -> OutputStream?): Boolean {
        return try {
            val out = open() ?: return false
            out.use { it.write(bytes) }
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
