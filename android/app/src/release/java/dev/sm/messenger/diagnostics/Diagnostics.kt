package dev.sm.messenger.diagnostics

import android.content.Context

/** ADR-150: release ничего не собирает, не создаёт поток, не открывает файлы. */
internal object Diagnostics {
    fun initialize(context: Context) = Unit
    fun begin(event: DiagnosticEvent): Long = 0L
    fun record(event: DiagnosticEvent, result: DiagnosticResult = DiagnosticResult.Ok, attempt: Long = 0L, value: Int = 0) = Unit
}
