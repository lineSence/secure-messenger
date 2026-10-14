package dev.sm.messenger.relay

import android.database.sqlite.SQLiteException
import dev.sm.messenger.store.SecureDb
import java.security.GeneralSecurityException
import uniffi.sm_ffi.SignalFfiException

internal fun linkProblem(error: Exception): LinkProblem = when (error) {
    is RelayException -> LinkProblem.NoNetwork
    is SQLiteException, is SecureDb.CorruptRow, is SignalFfiException -> LinkProblem.Storage
    is GeneralSecurityException -> LinkProblem.Keystore
    else -> throw error
}
