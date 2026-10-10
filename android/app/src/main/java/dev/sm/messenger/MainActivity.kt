package dev.sm.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.sm_ffi.coreSelfCheck
import uniffi.sm_ffi.coreVersion

/** Итог проверки ядра при запуске. */
private sealed interface CoreState {
    data class Ready(val version: String) : CoreState

    /** Ядро не загрузилось или не прошло самопроверку: приложение дальше не работает. */
    data object Broken : CoreState
}

/**
 * Проверяет ядро: библиотека загружается и самопроверка проходит. Любая ошибка — [CoreState.Broken]
 * (отказ в закрытую сторону, находка ревью PR #18).
 */
@Suppress("TooGenericExceptionCaught")
private fun checkCore(): CoreState =
    try {
        if (coreSelfCheck()) CoreState.Ready(coreVersion()) else CoreState.Broken
    } catch (e: Throwable) {
        // UnsatisfiedLinkError (нет .so) и любые ошибки привязок — тоже отказ.
        CoreState.Broken
    }

/**
 * Каркас клиента (шаг 3): проверяет, что ядро на Rust загружается и отвечает.
 * Экраны по макету (`design/mockups/ui-v6.html`) открываются только при [CoreState.Ready].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = checkCore()
        setContent {
            // Тема — как в системе, без своего переключателя (UX-10).
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (state) {
                        is CoreState.Ready -> CoreStatus(state.version)
                        CoreState.Broken -> CoreBroken()
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreStatus(version: String) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Ядро: $version", style = MaterialTheme.typography.titleMedium)
        Text("Самопроверка ядра: пройдена")
    }
}

/** Экран отказа: других экранов и действий нет. */
@Composable
private fun CoreBroken() {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Приложение остановлено", style = MaterialTheme.typography.titleMedium)
        Text("Ядро не прошло самопроверку. Переустановите приложение из надёжного источника.")
    }
}
