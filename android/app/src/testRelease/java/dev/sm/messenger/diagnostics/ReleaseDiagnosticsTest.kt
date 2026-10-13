package dev.sm.messenger.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseDiagnosticsTest {
    @Test fun releaseCollectorIsNoOp() {
        for (event in DiagnosticEvent.entries) {
            assertEquals(0L, Diagnostics.begin(event))
            Diagnostics.record(event, DiagnosticResult.Failed, Long.MAX_VALUE, Int.MAX_VALUE)
        }
    }
    @Test fun releaseContainsNoStorageWriterReportOrExport() {
        for (name in listOf("JournalCore", "JournalWriter", "EncryptedJournal", "JournalReport", "JournalExport")) {
            assertThrows(ClassNotFoundException::class.java) { Class.forName("dev.sm.messenger.diagnostics.$name") }
        }
    }
}
