package dev.sm.messenger.relay

import uniffi.sm_ffi.NodeInfo
import uniffi.sm_ffi.nodeRecordOpen
import uniffi.sm_ffi.queuePassOpen

internal interface AccountValidation {
    fun node(text: String, now: Long): NodeInfo
    fun pass(text: String, now: Long): ByteArray
}
internal object NativeAccountValidation : AccountValidation {
    override fun node(text: String, now: Long): NodeInfo = nodeRecordOpen(text, now.toULong())
    override fun pass(text: String, now: Long): ByteArray = queuePassOpen(text, now.toULong()).bytes
}

enum class AccountImport { Saved, OlderThanCurrent, Expired, Invalid, StorageFailure, Revoked }
