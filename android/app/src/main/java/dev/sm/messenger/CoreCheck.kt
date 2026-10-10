package dev.sm.messenger

import uniffi.sm_ffi.coreSelfCheck
import uniffi.sm_ffi.coreVersion

/** Итог проверки ядра при запуске. */
internal sealed interface CoreState {
    data class Ready(val version: String) : CoreState

    /** Ядро не загрузилось или не прошло самопроверку: приложение дальше не работает. */
    data object Broken : CoreState
}

/**
 * Проверяет ядро: библиотека загружается и самопроверка проходит. Любая ошибка — [CoreState.Broken]
 * (отказ в закрытую сторону, находка ревью PR #18).
 */
@Suppress("TooGenericExceptionCaught")
internal fun checkCore(): CoreState =
    try {
        if (coreSelfCheck()) CoreState.Ready(coreVersion()) else CoreState.Broken
    } catch (e: Throwable) {
        // UnsatisfiedLinkError (нет .so) и любые ошибки привязок — тоже отказ.
        CoreState.Broken
    }
