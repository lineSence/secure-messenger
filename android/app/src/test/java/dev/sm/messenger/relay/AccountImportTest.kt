package dev.sm.messenger.relay

import java.security.GeneralSecurityException
import org.junit.Assert.*
import org.junit.Test
import uniffi.sm_ffi.NodeDomainInfo
import uniffi.sm_ffi.NodeInfo
import uniffi.sm_ffi.NodeFfiException

class AccountImportTest {
    private class Kv : RelayKv {
        val rows = HashMap<String, ByteArray>()
        var fail = false
        override fun get(table: String, id: ByteArray) = rows[table + String(id)]
        override fun put(table: String, id: ByteArray, value: ByteArray) {
            if (fail) throw GeneralSecurityException("test storage failure")
            rows[table + String(id)] = value
        }
        override fun delete(table: String, id: ByteArray) { rows.remove(table + String(id)) }
    }
    private class Validation : AccountValidation {
        var revoked = emptyList<ByteArray>()
        override fun node(text: String, now: Long): NodeInfo {
            if (text == "expired") throw NodeFfiException.Expired()
            return NodeInfo(text.toULong(), 4000000000UL, 443u, "/path", listOf(NodeDomainInfo("a.example", listOf(ByteArray(32)))), revoked)
        }
        override fun pass(text: String, now: Long) = byteArrayOf(1) + ByteArray(16) { text.toByte() } + ByteArray(32)
    }
    @Test fun storageFailureIsNotOlderRecordAndDoesNotReplace() {
        val kv = Kv(); val account = RelayAccount(kv, Validation())
        assertEquals(AccountImport.Saved, account.importNode("2", 1))
        kv.fail = true
        assertEquals(AccountImport.StorageFailure, account.importNode("3", 1))
        assertEquals("2", account.nodeText())
        kv.fail = false
        assertEquals(AccountImport.OlderThanCurrent, account.importNode("1", 1))
        assertEquals(AccountImport.Expired, account.importNode("expired", 1))
        assertEquals("2", account.nodeText())
    }
    @Test fun revokedPassNeverReplacesWorkingPass() {
        val kv = Kv(); val validation = Validation(); val account = RelayAccount(kv, validation)
        account.importNode("2", 1)
        assertEquals(AccountImport.Saved, account.importPass("1", 1))
        validation.revoked = listOf(ByteArray(16) { 2 })
        assertEquals(AccountImport.Revoked, account.importPass("2", 1))
        assertArrayEquals(validation.pass("1", 1), account.pass(1))
    }
    @Test fun passStorageFailureLeavesPriorPass() {
        val kv = Kv(); val validation = Validation(); val account = RelayAccount(kv, validation)
        account.importNode("2", 1); account.importPass("1", 1); kv.fail = true
        assertEquals(AccountImport.StorageFailure, account.importPass("2", 1))
        assertArrayEquals(validation.pass("1", 1), account.pass(1))
    }
}
