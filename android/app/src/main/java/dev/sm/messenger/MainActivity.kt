package dev.sm.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sm.messenger.ble.BleScreen
import dev.sm.messenger.ble.BleViewModel
import dev.sm.messenger.ble.bleSubtitle
import dev.sm.messenger.store.StoreViewModel
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
    private val ble: BleViewModel by viewModels()
    private val store: StoreViewModel by viewModels()

    override fun onResume() {
        super.onResume()
        ble.refreshPermission()
    }

    override fun onStop() {
        super.onStop()
        store.flush()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = checkCore()
        // Очередь и окно — только при исправном ядре (ADR-56).
        if (state is CoreState.Ready) store.open()
        setContent {
            // Тема — как в системе, без своего переключателя (UX-10).
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when (state) {
                        is CoreState.Ready -> Ready(state.version, ble)
                        CoreState.Broken -> CoreBroken()
                    }
                }
            }
        }
    }
}

/** Ядро исправно: стартовый экран и «Bluetooth рядом (тест)» (UX-23). */
@Composable
private fun Ready(version: String, ble: BleViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    when (screen) {
        Screen.Home -> Home(version, ble) { screen = Screen.Ble }
        Screen.Ble -> BleScreen(ble) { screen = Screen.Home }
    }
}

private enum class Screen { Home, Ble }

@Composable
private fun Home(version: String, ble: BleViewModel, openBle: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Ядро: $version", style = MaterialTheme.typography.titleMedium)
        Text("Самопроверка ядра: пройдена")
        Text("Связь", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = openBle).padding(vertical = 8.dp)) {
            Text("Bluetooth рядом (тест)", style = MaterialTheme.typography.titleMedium)
            Text(bleSubtitle(ble))
        }
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
