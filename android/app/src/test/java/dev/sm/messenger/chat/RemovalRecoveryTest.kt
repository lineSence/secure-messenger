package dev.sm.messenger.chat
import org.junit.Assert.*
import org.junit.Test
class RemovalRecoveryTest {
    @Test fun snapshotFailureRetainsIntentForRestart() {
        var intent = true
        assertFalse(completeRemovalSnapshot({ false }) { intent = false })
        assertTrue(intent)
        assertTrue(completeRemovalSnapshot({ true }) { intent = false })
        assertFalse(intent)
    }
    @Test fun crashAfterPersistBeforeIntentDeleteCanRepeat() {
        var intent = true
        try { completeRemovalSnapshot({ true }) { throw IllegalStateException("simulated crash") }; fail() } catch (_: IllegalStateException) { }
        assertTrue(intent)
        completeRemovalSnapshot({ true }) { intent = false }
        assertFalse(intent)
    }
}
