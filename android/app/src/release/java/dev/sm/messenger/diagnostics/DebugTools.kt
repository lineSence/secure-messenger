package dev.sm.messenger.diagnostics

import androidx.compose.runtime.Composable

/** Экрана и кнопки debug-диагностики в release нет. */
internal object DebugTools {
    @Composable fun Row(onClick: () -> Unit) = Unit
    @Composable fun Screen(onBack: () -> Unit) = Unit
}
