package dev.sm.messenger

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.chat.Avatar
import dev.sm.messenger.chat.ChatScreen
import dev.sm.messenger.chat.ContactInfoScreen
import dev.sm.messenger.chat.ChatsScreen
import dev.sm.messenger.chat.VerifyScreen
import dev.sm.messenger.ui.AppLogo
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.Soon
import dev.sm.messenger.ui.SoonRow
import dev.sm.messenger.ui.SoonSheet
import dev.sm.messenger.ui.SoonTag
import androidx.compose.ui.Alignment
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
import dev.sm.messenger.ble.BleMode
import dev.sm.messenger.ble.bleSubtitle
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.contacts.AddContactScreen
import dev.sm.messenger.contacts.AddTab
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.contacts.Pairing
import dev.sm.messenger.keys.DeviceKeys
import dev.sm.messenger.keys.KeysState
import dev.sm.messenger.relay.RelayHub
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
 * Ядро исправно: нижняя панель «Чаты · Связь · Настройки» (UX-1), чат, сведения о контакте, код
 * безопасности, «Новый контакт» (UX-25) и «Профиль и ключ» поверх неё (макет v10). Переход — строка
 * [route]: `chat:<адрес>`, `info:<адрес>`, `verify:<адрес>`, `add:<вкладка>`, `profile`; пусто — вкладки.
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
        if (route.isNotEmpty()) route = back(route) else tab = Tab.Chats
    }
    fun contactOf(prefix: String): Contact? = contacts?.firstOrNull { it.address == route.removePrefix(prefix) }
    when {
        route.startsWith("chat:") -> contactOf("chat:")?.let { c ->
            ChatScreen(chat, ble, c, onBack = { route = "" }, onVerify = { route = "verify:" + c.address }, onInfo = { route = "info:" + c.address }) {
                route = ""
                tab = Tab.Net
            }
        }
        route.startsWith("info:") -> contactOf("info:")?.let { c ->
            ContactInfoScreen(c, onBack = { route = "chat:" + c.address }) { route = "verify:" + c.address }
        }
        route == "profile" -> ProfileScreen(name, identityText(identity.publicKey())) { route = "" }
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
            val invites = remember { RelayHub.get(context.applicationContext as Application).invites }
            AddContactScreen(pairing, invites, start, onBack = { route = "" }) {
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
                    Tab.Net -> NetTab(ble)
                    Tab.Settings -> SettingsTab(version, name, ble, contacts, chat.book.broken, openNet = { tab = Tab.Net }) { route = "profile" }
                }
            }
        }
    }
}

/** Куда ведёт «Назад» из маршрута: код безопасности и сведения — в чат, остальное — к вкладкам. */
private fun back(route: String): String = when {
    route.startsWith("verify:") -> "chat:" + route.removePrefix("verify:")
    route.startsWith("info:") -> "chat:" + route.removePrefix("info:")
    else -> ""
}

/** Вкладка «Связь» (макет v10): Bluetooth рядом работает; остальные пути — заглушки «Скоро». */
@Composable
private fun NetTab(ble: BleHub) {
    var soon by remember { mutableStateOf<Soon?>(null) }
    BleScreen(ble, title = "Связь") {
        Text("Другие пути", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Card {
            SoonRow(SmIcons.Wifi, "Интернет", "через свой узел", Soon.Internet) { soon = it }
            SoonRow(SmIcons.Refresh, "Обходные пути", "при блокировках и белых списках", Soon.Bypass) { soon = it }
            SoonRow(SmIcons.Radio, "LoRa (Reticulum)", "на километры без интернета", Soon.Lora) { soon = it }
            SoonRow(SmIcons.Qr, "Файл или QR", "отнести сообщение без связи", Soon.FileQr) { soon = it }
            SoonRow(SmIcons.Net, "Проверка связи и история", "что работает, что проверено", Soon.Diagnostics) { soon = it }
        }
    }
    soon?.let { SoonSheet(it) { soon = null } }
}

/**
 * Настройки 0.0.1-alpha (макет v10): все разделы на своих местах. Работают профиль и ключ,
 * Bluetooth рядом, уведомления (настройки Android) и «О приложении»; остальное — «Скоро».
 */
@Composable
private fun SettingsTab(
    version: String,
    name: String,
    ble: BleHub,
    contacts: List<Contact>?,
    broken: Int,
    openNet: () -> Unit,
    openProfile: () -> Unit,
) {
    val context = LocalContext.current
    var soon by remember { mutableStateOf<Soon?>(null) }
    val open: (Soon) -> Unit = { soon = it }
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp))
        SettingRow(SmIcons.Settings, name, "Профиль и ключ · без телефона и почты", trailing = { Avatar(name, 44) }, onClick = openProfile)
        SectionTitle("Связь")
        SettingRow(
            SmIcons.Bt,
            "Bluetooth рядом",
            bleSubtitle(ble),
            trailing = if (ble.mode == BleMode.Conn) ({ SoonTag("вкл") }) else null,
            onClick = openNet,
        )
        SoonRow(SmIcons.Net, "Проверка связи", "что работает сейчас", Soon.Diagnostics, open = open)
        SoonRow(SmIcons.Radio, "LoRa (Reticulum)", "модуль не подключён", Soon.Lora, open = open)
        SectionTitle("Устройства и безопасность")
        SoonRow(SmIcons.Laptop, "Мои устройства", "1: этот телефон", Soon.Devices, open = open)
        SoonRow(SmIcons.Lock, "Блокировка приложения", "Выкл.", Soon.AppLock, open = open)
        SoonRow(SmIcons.Shield, "Конфиденциальность", "что видят контакты", Soon.Privacy, open = open)
        SectionTitle("Приложение")
        SettingRow(SmIcons.Bell, "Уведомления", "в настройках Android") {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
        SoonRow(SmIcons.Download, "Обновления", "вручную: новый файл поверх", Soon.Updates, open = open)
        SoonRow(SmIcons.Palette, "Оформление", "тема как в системе", Soon.Look, open = open)
        SectionTitle("О приложении")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppLogo(48)
            Column(Modifier.padding(start = 14.dp)) {
                Text("Uncensored $appVersion", style = MaterialTheme.typography.titleMedium)
                Text("Ядро $version · самопроверка пройдена", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Контактов: ${contacts?.size ?: "—"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (broken > 0) Text("Не открылось записей контактов: $broken", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    soon?.let { SoonSheet(it) { soon = null } }
}

/** Профиль и ключ (макет v10): имя, ключ этого телефона; изменение имени — «Скоро». */
@Composable
private fun ProfileScreen(name: String, identity: String, onBack: () -> Unit) {
    var soon by remember { mutableStateOf<Soon?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(SmIcons.Back, contentDescription = "Назад") }
            Text("Профиль и ключ", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(name, 88)
            Text(name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
        }
        SoonRow(SmIcons.Edit, "Изменить имя", "видят только контакты", Soon.Rename) { soon = it }
        SectionTitle("Ключ этого телефона")
        Text(identity, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "Ваш идентификатор — это ключ. Телефон и почта не нужны. Чтобы добавить вас, контакт сканирует QR: " +
                "Чаты → Новый контакт → Мой QR.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
    soon?.let { SoonSheet(it) { soon = null } }
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
