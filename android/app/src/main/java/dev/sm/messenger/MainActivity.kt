package dev.sm.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Bundle
import android.provider.Settings
import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.ui.Avatar
import dev.sm.messenger.ui.NavItem
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmHeader
import dev.sm.messenger.ui.SmNavBar
import dev.sm.messenger.ui.SmTheme
import dev.sm.messenger.ui.SmCard
import dev.sm.messenger.ui.Muted
import dev.sm.messenger.chat.rememberNetStatus
import dev.sm.messenger.chat.Tone
import dev.sm.messenger.chat.ChatScreen
import dev.sm.messenger.chat.ContactInfoScreen
import dev.sm.messenger.chat.ChatsScreen
import dev.sm.messenger.chat.VerifyScreen
import dev.sm.messenger.ui.AppLogo
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.Soon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.sm.messenger.ui.BottomSpace
import dev.sm.messenger.ui.Tag
import dev.sm.messenger.ui.SoonRow
import dev.sm.messenger.ui.SoonSheet
import dev.sm.messenger.ui.WhoSeesScreen
import androidx.compose.ui.Alignment
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.sm.messenger.relay.NodeRow
import dev.sm.messenger.relay.NodeScreen
import dev.sm.messenger.relay.RelayHub
import dev.sm.messenger.onboarding.Onboarding
import dev.sm.messenger.onboarding.Profile
import dev.sm.messenger.onboarding.identityText
import dev.sm.messenger.store.StoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sm_ffi.DeviceIdentity
import dev.sm.messenger.diagnostics.DebugTools

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
        // Рисуем под системными полосами: отступы берут шапки и нижние панели экранов (UX, макет v14).
        enableEdgeToEdge()
        val state = checkCore()
        // Очередь, окно и ключи — только при исправном ядре (ADR-56).
        if (state is CoreState.Ready) store.open()
        val deviceKeys = DeviceKeys(this)
        val profile = Profile(this)
        val loaded = if (state is CoreState.Ready) deviceKeys.load() else KeysState.Broken
        setContent {
            var keys by remember { mutableStateOf(loaded) }
            var onboarded by remember { mutableStateOf(profile.onboarded) }
            // Куда идти после первого запуска и черновик приглашения v16.
            var firstRoute by remember { mutableStateOf("") }
            var firstInvite by rememberSaveable { mutableStateOf("") }
            // Тема — как в системе, без своего переключателя (UX-10).
            SmTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Sm.colors.bg) {
                    val k = keys
                    when {
                        state !is CoreState.Ready -> CoreBroken()
                        k is KeysState.Broken -> KeysBroken()
                        k is KeysState.Missing || !onboarded -> Onboarding(profile, k, deviceKeys::create) { next, route, invitation ->
                            keys = next
                            firstRoute = route
                            firstInvite = invitation
                            onboarded = profile.onboarded
                        }
                        k is KeysState.Ready -> Ready(
                            state.version,
                            profile.name.orEmpty(),
                            k.identity,
                            ble,
                            firstRoute,
                            firstInvite,
                            onInviteChange = { firstInvite = it },
                        )
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
 * [route]: `chat:<адрес>`, `info:<адрес>`, `verify:<адрес>`, `add:<вкладка>`, `profile`, `node` (из профиля —
 * `node:profile`, из ссылки — `node:add`, макет ui-v11), `see` (из ссылки — `see:add`, макет ui-v13); пусто — вкладки.
 */
@Composable
private fun Ready(
    version: String,
    name: String,
    identity: DeviceIdentity,
    ble: BleHub,
    firstRoute: String,
    initialInvite: String,
    onInviteChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val chat = remember { ChatHub.get(context.applicationContext as Application).also { it.attach(identity) } }
    val sessions = chat.signal ?: return
    var tab by rememberSaveable { mutableStateOf(Tab.Chats) }
    var route by rememberSaveable { mutableStateOf(firstRoute) }
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
            val removal = remember { RelayHub.get(context.applicationContext as Application).removal }
            ContactInfoScreen(
                c,
                onBack = { route = "chat:" + c.address },
                onVerify = { route = "verify:" + c.address },
                onRemove = removal::remove,
                onRemoved = {
                    // Метка Bluetooth удалённого контакта больше не нужна (ADR-61).
                    scope.launch(Dispatchers.IO) { ble.loadContacts() }
                    route = ""
                },
            )
        }
        route == "debug-logs" -> DebugTools.Screen { route = "" }
        route == "profile" -> ProfileScreen(name, identityText(identity.publicKey()), onNode = { route = "node:profile" }) { route = "" }
        route.startsWith("node") -> NodeScreen { route = back(route) }
        route.startsWith("see") -> WhoSeesScreen { route = back(route) }
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
            AddContactScreen(
                pairing,
                invites,
                start,
                onBack = { route = "" },
                onSee = { route = "see:add" },
                onNode = { route = "node:add" },
                initialLink = initialInvite,
                onLinkChange = onInviteChange,
            ) {
                // Новый контакт сразу должен видеть метку телефона (ADR-61).
                scope.launch(Dispatchers.IO) { ble.loadContacts() }
                chat.touch()
                onInviteChange("")
                route = ""
            }
        }
        else -> {
            val net = rememberNetStatus(ble)
            val navItems = listOf(
                NavItem(Tab.Chats.title, SmIcons.Chats),
                NavItem(Tab.Net.title, SmIcons.Net, dot = if (net.tone == Tone.Ok) null else tone(net.tone)),
                NavItem(Tab.Settings.title, SmIcons.Settings),
            )
            Column(Modifier.fillMaxSize().background(Sm.colors.bg)) {
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.Chats -> ChatsScreen(chat, ble, openChat = { route = "chat:" + it.address }, openAdd = { route = "add:" + it.name }) {
                            tab = Tab.Net
                        }
                        Tab.Net -> NetTab(ble) { route = "node" }
                        Tab.Settings -> SettingsTab(
                            version,
                            name,
                            ble,
                            contacts,
                            chat.book.broken,
                            openNet = { tab = Tab.Net },
                            openNode = { route = "node" },
                            openSee = { route = "see" },
                            openDebug = { route = "debug-logs" },
                        ) { route = "profile" }
                    }
                }
                SmNavBar(navItems, Tab.entries.indexOf(tab)) { tab = Tab.entries[it] }
            }
        }
    }
}

@Composable
private fun tone(t: Tone): Color = when (t) {
    Tone.Ok -> Sm.colors.ok
    Tone.Warn -> Sm.colors.warn
    Tone.Bad -> Sm.colors.bad
}

/** Куда ведёт «Назад» из маршрута: код безопасности и сведения — в чат, остальное — к вкладкам. */
private fun back(route: String): String = when {
    route.startsWith("verify:") -> "chat:" + route.removePrefix("verify:")
    route.startsWith("info:") -> "chat:" + route.removePrefix("info:")
    route == "node:profile" -> "profile"
    route == "see:add" || route == "node:add" -> "add:" + AddTab.Link.name
    else -> ""
}

/** Вкладка «Связь» (макет v14): узел и Bluetooth рядом работают; остальные пути — «Скоро». */
@Composable
private fun NetTab(ble: BleHub, openNode: () -> Unit) {
    var soon by remember { mutableStateOf<Soon?>(null) }
    BleScreen(ble, node = { NodeRow("Интернет · свой узел", withTag = true, onClick = openNode) }) {
        SectionTitle("Другие пути")
        SoonRow(SmIcons.Refresh, "Обходные пути", "при блокировках и белых списках", Soon.Bypass) { soon = it }
        SoonRow(SmIcons.Radio, "LoRa (MeshCore)", "на километры без интернета", Soon.Lora) { soon = it }
        SoonRow(SmIcons.Qr, "Файл или QR", "отнести сообщение без связи", Soon.FileQr) { soon = it }
        SoonRow(SmIcons.Net, "Проверка связи и история", "что работает, что проверено", Soon.Diagnostics) { soon = it }
    }
    soon?.let { SoonSheet(it) { soon = null } }
}

/**
 * Настройки 0.0.1-alpha (макет v14): все разделы на своих местах. Работают профиль и ключ,
 * Bluetooth рядом, уведомления (настройки Android) и «О приложении»; остальное — «Скоро».
 */
@SuppressLint("BatteryLife")
@Composable
private fun SettingsTab(
    version: String,
    name: String,
    ble: BleHub,
    contacts: List<Contact>?,
    broken: Int,
    openNet: () -> Unit,
    openNode: () -> Unit,
    openSee: () -> Unit,
    openDebug: () -> Unit,
    openProfile: () -> Unit,
) {
    val context = LocalContext.current
    var soon by remember { mutableStateOf<Soon?>(null) }
    val appVersion = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var notificationsRequested by rememberSaveable { mutableStateOf(false) }
    fun openAppNotificationSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsRequested = true
        if (!granted) openAppNotificationSettings()
    }
    SettingsContent(
        name = name,
        appVersion = appVersion,
        coreVersion = version,
        contacts = contacts?.size?.toString() ?: "—",
        broken = broken,
        bleSub = bleSubtitle(ble),
        bleOn = ble.mode == BleMode.Conn,
        node = { NodeRow(onClick = openNode) },
        openProfile = openProfile,
        openSee = openSee,
        openNet = openNet,
        openNotifications = {
            val notGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (notGranted && !notificationsRequested) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openAppNotificationSettings()
            }
        },
        openBackground = {
            val power = context.getSystemService(PowerManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !power.isIgnoringBatteryOptimizations(context.packageName)
            ) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + context.packageName),
                    ),
                )
            } else {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        },
        open = { soon = it },
        debugRow = { DebugTools.Row(openDebug) },
    )
    soon?.let { SoonSheet(it) { soon = null } }
}

@Composable
internal fun SettingsContent(
    name: String,
    appVersion: String,
    coreVersion: String,
    contacts: String,
    broken: Int,
    bleSub: String,
    bleOn: Boolean,
    node: @Composable () -> Unit,
    openProfile: () -> Unit,
    openSee: () -> Unit,
    openNet: () -> Unit,
    openNotifications: () -> Unit,
    open: (Soon) -> Unit,
    debugRow: @Composable () -> Unit = {},
    openBackground: () -> Unit = {},
) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        SmHeader("Настройки")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SettingRow(null, name, "Профиль и ключ · без телефона и почты", leading = { Avatar(name, 54) }, minHeight = 84.dp, onClick = openProfile)
            SectionTitle("Связь")
            SettingRow(SmIcons.Shield, "Кто что видит", "что знают оператор, хостер узла, Telegram", onClick = openSee)
            node()
            SettingRow(SmIcons.Bt, "Bluetooth рядом", bleSub, trailing = if (bleOn) ({ Tag("вкл", c.ok) }) else null, onClick = openNet)
            SoonRow(SmIcons.Net, "Проверка связи", "что работает сейчас", Soon.Diagnostics, open = open)
            SoonRow(SmIcons.Radio, "LoRa (MeshCore)", "модуль не подключён", Soon.Lora, open = open)
            SectionTitle("Устройства и безопасность")
            SoonRow(SmIcons.Laptop, "Мои устройства", "1: этот телефон", Soon.Devices, open = open)
            SoonRow(SmIcons.Lock, "Блокировка приложения", "Выкл.", Soon.AppLock, open = open)
            SoonRow(SmIcons.Shield, "Конфиденциальность", "что видят контакты", Soon.Privacy, open = open)
            SectionTitle("Приложение")
            SettingRow(SmIcons.Bell, "Уведомления", "Разрешить показ новых сообщений", onClick = openNotifications)
            SettingRow(SmIcons.Battery, "Работа в фоне", "Разрешить работу без ограничений батареи", onClick = openBackground)
            SoonRow(SmIcons.Download, "Обновления", "вручную: новый файл поверх", Soon.Updates, open = open)
            SoonRow(SmIcons.Palette, "Оформление", "тема как в системе", Soon.Look, open = open)
            debugRow()
            SectionTitle("О приложении")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppLogo(48)
                Column(Modifier.padding(start = 14.dp)) {
                    Text("Uncensored $appVersion", color = c.tx, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Ядро $coreVersion · самопроверка пройдена", color = c.tx2, fontSize = 13.sp, lineHeight = 18.sp)
                    Text("Контактов: $contacts", color = c.tx2, fontSize = 13.sp, lineHeight = 18.sp)
                    if (broken > 0) Text("Не открылось записей контактов: $broken", color = c.bad, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
            BottomSpace()
        }
    }
}

/** Профиль и ключ (макет v14): имя, ключ этого телефона; изменение имени — «Скоро». */
@Composable
private fun ProfileScreen(name: String, identity: String, onNode: () -> Unit, onBack: () -> Unit) {
    var soon by remember { mutableStateOf<Soon?>(null) }
    ProfileContent(name, identity, node = { NodeRow(onClick = onNode) }, onBack = onBack, onSoon = { soon = it })
    soon?.let { SoonSheet(it) { soon = null } }
}

@Composable
internal fun ProfileContent(name: String, identity: String, node: @Composable () -> Unit, onBack: () -> Unit, onSoon: (Soon) -> Unit) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        SmHeader("Профиль и ключ", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(name, 88)
                Text(name, color = c.tx, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
            }
            SoonRow(SmIcons.Edit, "Изменить имя", "видят только контакты", Soon.Rename) { onSoon(it) }
            node()
            SectionTitle("Ключ этого телефона")
            SmCard {
                Text(
                    identity,
                    color = c.tx,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    lineHeight = 24.sp,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Muted(
                "Ваш идентификатор — это ключ. Телефон и почта не нужны. Чтобы добавить вас, контакт сканирует QR: " +
                    "Чаты → Новый контакт → Мой QR.",
                center = true,
            )
        }
    }
}

/**
 * Ключ личности есть, но не открывается: новый молча не создаём (контакты увидели бы чужой ключ),
 * других экранов нет.
 */
@Composable
private fun KeysBroken() {
    Column(modifier = Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ключ шифрования не открывается", color = Sm.colors.tx, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            "Хранилище ключей Android не отдаёт ключ этого телефона. Переписку на нём продолжить нельзя. " +
                "Начать заново можно, удалив данные приложения в настройках Android — контакты увидят новый ключ.",
            color = Sm.colors.tx2,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

/** Экран отказа: других экранов и действий нет. */
@Composable
private fun CoreBroken() {
    Column(modifier = Modifier.statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Приложение остановлено", color = Sm.colors.tx, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Ядро не прошло самопроверку. Переустановите приложение из надёжного источника.", color = Sm.colors.tx2, fontSize = 14.sp, lineHeight = 20.sp)
    }
}
