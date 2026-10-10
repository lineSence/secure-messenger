package dev.sm.messenger

import android.os.Bundle
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.chat.ChatScreen
import dev.sm.messenger.chat.ChatsScreen
import dev.sm.messenger.chat.VerifyScreen
import dev.sm.messenger.ui.SmIcons
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sm.messenger.ble.BleScreen
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.contacts.AddContactScreen
import dev.sm.messenger.contacts.AddTab
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.contacts.Pairing
import dev.sm.messenger.keys.DeviceKeys
import dev.sm.messenger.keys.KeysState
import dev.sm.messenger.onboarding.Onboarding
import dev.sm.messenger.onboarding.Profile
import dev.sm.messenger.onboarding.identityText
import dev.sm.messenger.store.StoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sm_ffi.DeviceIdentity

/**
 * Каркас клиента: проверяет, что ядро на Rust загружается и отвечает; экраны открываются только
 * при [CoreState.Ready]. Стартовый экран — чаты по макету v7 (пункт 7).
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
                        k is KeysState.Ready -> Ready(state.version, profile.name.orEmpty(), k.identity, ble)
                    }
                }
            }
        }
    }
}

/** Вкладки нижней панели (UX-1). */
private enum class Tab(val title: String) { Chats("Чаты"), Net("Связь"), Settings("Настройки") }

/**
 * Ядро исправно: нижняя панель «Чаты · Связь · Настройки» (UX-1), чат, код безопасности и
 * «Новый контакт» (UX-25) поверх неё. Переход — строка [route]: `chat:<адрес>`, `verify:<адрес>`,
 * `add:<вкладка>`; пусто — вкладки.
 */
@Composable
private fun Ready(version: String, name: String, identity: DeviceIdentity, ble: BleHub) {
    val context = LocalContext.current
    val chat = remember { ChatHub.get(context.applicationContext as Application).also { it.attach(identity) } }
    val sessions = chat.signal ?: return
    var tab by rememberSaveable { mutableStateOf(Tab.Chats) }
    var route by rememberSaveable { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<Contact>?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(chat.revision) {
        // Строки расшифровывает Keystore — не в главном потоке.
        contacts = withContext(Dispatchers.IO) { runCatching { chat.book.list() }.getOrNull() }
    }
    BackHandler(enabled = route.isNotEmpty() || tab != Tab.Chats) {
        if (route.isNotEmpty()) route = if (route.startsWith("verify:")) "chat:" + route.removePrefix("verify:") else "" else tab = Tab.Chats
    }
    fun contactOf(prefix: String): Contact? = contacts?.firstOrNull { it.address == route.removePrefix(prefix) }
    when {
        route.startsWith("chat:") -> contactOf("chat:")?.let { c ->
            ChatScreen(chat, ble, c, onBack = { route = "" }, onVerify = { route = "verify:" + c.address }) {
                route = ""
                tab = Tab.Net
            }
        }
        route.startsWith("verify:") -> contactOf("verify:")?.let { c ->
            VerifyScreen(chat, c, onBack = { route = "chat:" + c.address }) {
                chat.touch()
                route = "chat:" + c.address
            }
        }
        route.startsWith("add:") -> {
            val start = AddTab.entries.firstOrNull { it.name == route.removePrefix("add:") } ?: AddTab.My
            val pairing = remember { Pairing(context, sessions, chat.book, name, identity.publicKey()) }
            DisposableEffect(pairing) { onDispose { pairing.close() } }
            AddContactScreen(pairing, start, onBack = { route = "" }) {
                // Новый контакт сразу должен видеть метку телефона (ADR-61).
                scope.launch(Dispatchers.IO) { ble.loadContacts() }
                chat.touch()
                route = ""
            }
        }
        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                Icon(
                                    when (t) {
                                        Tab.Chats -> SmIcons.Chats
                                        Tab.Net -> SmIcons.Net
                                        Tab.Settings -> SmIcons.Settings
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.Chats -> ChatsScreen(chat, ble, openChat = { route = "chat:" + it.address }, openAdd = { route = "add:" + it.name }) {
                        tab = Tab.Net
                    }
                    Tab.Net -> BleScreen(ble)
                    Tab.Settings -> SettingsTab(version, name, identityText(identity.publicKey()), contacts, chat.book.broken)
                }
            }
        }
    }
}

/** Настройки 0.0.1-alpha: имя, ключ устройства, версия ядра. */
@Composable
private fun SettingsTab(version: String, name: String, identity: String, contacts: List<Contact>?, broken: Int) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge)
        Text("Имя", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 20.dp))
        Text(name, style = MaterialTheme.typography.titleMedium)
        Text("Ключ этого телефона", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
        Text(identity, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("Контактов: ${contacts?.size ?: "—"}", modifier = Modifier.padding(top = 16.dp))
        if (broken > 0) Text("Не открылось записей контактов: $broken", color = MaterialTheme.colorScheme.error)
        Text("Ядро: $version · самопроверка пройдена", modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
