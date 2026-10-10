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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sm.messenger.ble.BleScreen
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.ble.bleSubtitle
import dev.sm.messenger.keys.DeviceKeys
import dev.sm.messenger.keys.KeysState
import dev.sm.messenger.onboarding.Onboarding
import dev.sm.messenger.onboarding.Profile
import dev.sm.messenger.onboarding.identityText
import dev.sm.messenger.store.StoreViewModel

/**
 * Каркас клиента (шаг 3): проверяет, что ядро на Rust загружается и отвечает.
 * Экраны по макету (`design/mockups/ui-v6.html`) открываются только при [CoreState.Ready].
 */
class MainActivity : ComponentActivity() {
    private val ble: BleHub by lazy { BleHub.get(application) }
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
        // Очередь, окно и ключи — только при исправном ядре (ADR-56).
        if (state is CoreState.Ready) store.open()
        val deviceKeys = DeviceKeys(this)
        val profile = Profile(this)
        val loaded = if (state is CoreState.Ready) deviceKeys.load() else KeysState.Broken
        setContent {
            var keys by remember { mutableStateOf(loaded) }
            var onboarded by remember { mutableStateOf(profile.onboarded) }
            // Тема — как в системе, без своего переключателя (UX-10).
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val k = keys
                    when {
                        state !is CoreState.Ready -> CoreBroken()
                        k is KeysState.Broken -> KeysBroken()
                        k is KeysState.Missing || !onboarded -> Onboarding(profile, k, deviceKeys::create) {
                            keys = it
                            onboarded = profile.onboarded
                        }
                        k is KeysState.Ready -> Ready(state.version, profile.name.orEmpty(), identityText(k.identity.publicKey()), ble)
                    }
                }
            }
        }
    }
}

/** Ядро исправно: стартовый экран и «Bluetooth рядом (тест)» (UX-23). */
@Composable
private fun Ready(version: String, name: String, identity: String, ble: BleHub) {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
    when (screen) {
        Screen.Home -> Home(version, name, identity, ble) { screen = Screen.Ble }
        Screen.Ble -> BleScreen(ble) { screen = Screen.Home }
    }
}

private enum class Screen { Home, Ble }

@Composable
private fun Home(version: String, name: String, identity: String, ble: BleHub, openBle: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text(name, style = MaterialTheme.typography.titleLarge)
        Text(identity, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("Ядро: $version · самопроверка пройдена", modifier = Modifier.padding(top = 8.dp))
        Text("Связь", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
        Column(modifier = Modifier.fillMaxWidth().clickable(onClick = openBle).padding(vertical = 8.dp)) {
            Text("Bluetooth рядом (тест)", style = MaterialTheme.typography.titleMedium)
            Text(bleSubtitle(ble))
        }
    }
}

/**
 * Ключ личности есть, но не открывается: новый молча не создаём (контакты увидели бы чужой ключ),
 * других экранов нет.
 */
@Composable
private fun KeysBroken() {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Ключ шифрования не открывается", style = MaterialTheme.typography.titleMedium)
        Text(
            "Хранилище ключей Android не отдаёт ключ этого телефона. Переписку на нём продолжить нельзя. " +
                "Начать заново можно, удалив данные приложения в настройках Android — контакты увидят новый ключ.",
        )
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
