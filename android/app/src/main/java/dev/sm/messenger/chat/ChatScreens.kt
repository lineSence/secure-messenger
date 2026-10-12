package dev.sm.messenger.chat

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.ble.BleMode
import dev.sm.messenger.contacts.AddTab
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.Soon
import dev.sm.messenger.ui.SoonRow
import dev.sm.messenger.ui.SoonSheet
import dev.sm.messenger.ui.SoonTag
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sm_ffi.ChatBody
import uniffi.sm_ffi.ChatMessage
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.chatEncode

/** Предел текста — как в ядре (`message::MAX_TEXT`, 16 КиБ в UTF-8). */
private const val MAX_TEXT_BYTES = 16 * 1024
private const val DIGITS_PER_GROUP = 5
private const val GROUPS_PER_ROW = 3
private const val NET_HOLD_MS = 2_000L

/** Через 10 минут без доставки — «ещё не доставлено» (UX-29). */
private const val LONG_WAIT_MS = 10L * 60 * 1000

/** Нейтральное название канала (UX-27). */
private const val CHANNEL = "Bluetooth рядом"
private const val INTERNET = "Интернет"

/**
 * Полоса связи (UX-2, UX-28, макет v8): для 0.0.1-alpha путь один — Bluetooth рядом. Новое
 * состояние показываем, только если оно держится ≥ 2 с (против мелькания); нажатие — «Связь».
 */
@Composable
fun NetBar(ble: BleHub, onClick: () -> Unit) {
    var shown by remember { mutableStateOf(ble.mode) }
    LaunchedEffect(ble.mode) {
        if (ble.mode != shown) {
            delay(NET_HOLD_MS)
            shown = ble.mode
        }
    }
    val (bg, title, sub) = when (shown) {
        BleMode.Conn -> Triple(Color(0xFF2E9E4F), "Связь есть", "Bluetooth рядом · сообщения уходят сразу")
        BleMode.Scan, BleMode.Lost -> Triple(Color(0xFFB7791F), "Подключение…", "Bluetooth рядом · сообщения ждут на телефоне")
        BleMode.Off -> Triple(Color(0xFFD64545), "Нет сети", "Bluetooth рядом выключен · сообщения ждут на телефоне")
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        Text(
            sub,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
        )
        Icon(SmIcons.Chev, contentDescription = "Связь", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

/** Кружок с первой буквой имени; цвет — от имени (как в макете). */
@Composable
fun Avatar(name: String, size: Int = 48) {
    val palette = listOf(0xFFF4A261, 0xFF6CC3C9, 0xFF6FA8DC, 0xFF9D8DF1, 0xFF7DC67D, 0xFFE58FB3)
    val color = Color(palette[Math.floorMod(name.hashCode(), palette.size)])
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NotVerifiedTag() {
    Text(
        "не сверен",
        color = Color(0xFF9A6B00),
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFF1CC)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * Список чатов (макеты v7, v10; пустой — UX-22). Поиск, папки «Личные»/«Группы» и «Новая группа» —
 * заглушки «Скоро» (макет v10).
 */
@Composable
fun ChatsScreen(chat: ChatHub, ble: BleHub, openChat: (Contact) -> Unit, openAdd: (AddTab) -> Unit, openNet: () -> Unit) {
    var list by remember { mutableStateOf<List<ChatSummary>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(chat.revision) {
        val r = withContext(Dispatchers.IO) { chat.summaries() }
        failed = r == null
        if (r != null) list = r
    }
    var soon by remember { mutableStateOf<Soon?>(null) }
    var newChat by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Чаты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { soon = Soon.Search }) { Icon(SmIcons.Search, contentDescription = "Поиск") }
            }
            if (!list.isNullOrEmpty()) {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = true, onClick = {}, label = { Text("Все") })
                    FilterChip(selected = false, onClick = { soon = Soon.Folders }, label = { Text("Личные") })
                    FilterChip(selected = false, onClick = { soon = Soon.Folders }, label = { Text("Группы") })
                }
            }
            NetBar(ble, openNet)
            val l = list
            when {
                failed && l == null -> Muted("Чаты не открываются: хранилище на этом телефоне не отвечает.")
                l == null -> Unit
                l.isEmpty() -> EmptyChats(openAdd)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(l, key = { it.contact.rowId }) { s -> ChatRow(s) { openChat(s.contact) } }
                }
            }
        }
        FloatingActionButton(
            onClick = { newChat = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(SmIcons.Edit, contentDescription = "Новый чат") }
    }
    if (newChat) {
        NewChatSheet(
            onDismiss = { newChat = false },
            addContact = {
                newChat = false
                openAdd(AddTab.My)
            },
        ) {
            newChat = false
            soon = Soon.Group
        }
    }
    soon?.let { SoonSheet(it) { soon = null } }
}

/** «Новый чат» (макет v10): новый контакт; новая группа — заглушка 0.2.0. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewChatSheet(onDismiss: () -> Unit, addContact: () -> Unit, newGroup: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SectionTitle("Новый чат")
            SettingRow(SmIcons.UserPlus, "Новый контакт", "QR при встрече или Bluetooth рядом", onClick = addContact)
            SoonRow(SmIcons.Group, "Новая группа", Soon.Group.versionText, Soon.Group) { newGroup() }
        }
    }
}

@Composable
private fun EmptyChats(openAdd: (AddTab) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(SmIcons.Chats, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary) }
        Text("Пока нет чатов", style = MaterialTheme.typography.titleLarge)
        Text(
            "Добавьте человека при встрече: по QR или по Bluetooth рядом.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = { openAdd(AddTab.My) }, modifier = Modifier.fillMaxWidth()) {
            Icon(SmIcons.Qr, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Показать мой QR", modifier = Modifier.padding(start = 8.dp))
        }
        FilledTonalButton(onClick = { openAdd(AddTab.Near) }, modifier = Modifier.fillMaxWidth()) {
            Icon(SmIcons.Bt, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Добавить по Bluetooth рядом", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ChatRow(s: ChatSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(s.contact.name, 54)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (!s.contact.verified) NotVerifiedTag()
                Spacer(Modifier.weight(1f))
                val last = s.last
                if (last != null) {
                    if (last.mine && last.state == MessageState.Delivered) {
                        Icon(SmIcons.Delivered, contentDescription = "доставлено", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(shortTime(last.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.last?.text ?: "Нет сообщений",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (s.unread > 0) Badge(modifier = Modifier.padding(start = 6.dp)) { Text(s.unread.toString()) }
            }
        }
    }
}

/**
 * Чат (макеты v7, v8, v10): шапка с меткой «ключ не сверен», плашка «Сверить», пузыри со статусом
 * часы «на телефоне, ждёт связи» → галочка в круге «доставлено» (UX-26; шага «на узле» по
 * Bluetooth нет), канал — «Bluetooth рядом» (UX-27), подсказка, когда собеседника нет рядом.
 * Эмодзи-панель, вложения, меню сообщения (долгое нажатие) и «Сведения о сообщении» (нажатие на
 * время) — заглушки «Скоро» (ADR-14: в 0.0.1-alpha только текст).
 */
@Composable
fun ChatScreen(
    chat: ChatHub,
    ble: BleHub,
    contact: Contact,
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onInfo: () -> Unit,
    openNet: () -> Unit,
) {
    var soon by remember { mutableStateOf<Soon?>(null) }
    var info by remember { mutableStateOf<Long?>(null) }
    var lines by remember { mutableStateOf<List<ChatLine>>(emptyList()) }
    var broken by remember { mutableStateOf(0) }
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    DisposableEffect(contact.address) {
        chat.visibleChat = contact.address
        onDispose { if (chat.visibleChat == contact.address) chat.visibleChat = null }
    }
    LaunchedEffect(chat.revision, contact.address) {
        val h = withContext(Dispatchers.IO) {
            chat.markRead(contact)
            chat.history(contact)
        }
        if (h != null) {
            lines = h.first
            broken = h.second
            if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
        }
    }
    val near = ble.isNear(contact.address)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(SmIcons.Back, contentDescription = "Назад") }
            Avatar(contact.name, 40)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !contact.verified -> "ключ не сверен"
                        near && ble.mode == BleMode.Conn && ble.current?.contact == contact.address -> "рядом · подключено"
                        near -> "рядом"
                        else -> "не рядом"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onInfo) { Icon(SmIcons.More, contentDescription = "Сведения о контакте") }
        }
        NetBar(ble, openNet)
        if (!contact.verified) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFFFFF4DC)).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(SmIcons.Shield, contentDescription = null, tint = Color(0xFFB7791F))
                Text(
                    "Контакт добавлен по Bluetooth рядом и ещё не сверен",
                    color = Color(0xFF3A3A3A),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                )
                Button(onClick = onVerify) { Text("Сверить") }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (broken > 0) item { Muted("Не открылось сообщений: $broken") }
            if (lines.isEmpty()) item { Muted("Сообщений пока нет. Напишите первым — сообщение уйдёт, когда телефоны окажутся рядом.") }
            items(lines.size, key = { lines[it].rowId }) { i ->
                val m = lines[i]
                val prev = lines.getOrNull(i - 1)
                if (prev == null || dayOf(prev.at) != dayOf(m.at)) DayChip(m.at)
                Bubble(m, onInfo = { info = m.rowId }) { soon = it }
            }
        }
        if (ble.mode == BleMode.Off || !near) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (ble.mode == BleMode.Off) {
                        "Bluetooth рядом выключен — сообщения уйдут, когда вы его включите и телефоны окажутся рядом"
                    } else {
                        "${contact.name} нет рядом — сообщения уйдут сами, когда телефоны окажутся рядом"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (ble.mode == BleMode.Off) TextButton(onClick = openNet) { Text("Включить") }
            }
        }
        HorizontalDivider()
        val tooLong = draft.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES
        // Текст, который ядро не примет (невидимые, bidi, управляющие символы), — подсказка, а не молчаливый отказ.
        val badText = !tooLong && draft.isNotBlank() && !textOk(draft.trim())
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { soon = Soon.Emoji }) { Icon(SmIcons.Smile, contentDescription = "Эмодзи") }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Сообщение") },
                isError = tooLong || badText,
                supportingText = when {
                    tooLong -> ({ Text("Слишком длинное сообщение") })
                    badText -> ({ Text("Уберите невидимые и служебные символы") })
                    else -> null
                },
                maxLines = 6,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { soon = Soon.Attach }) { Icon(SmIcons.Attach, contentDescription = "Вложение") }
            val canSend = draft.isNotBlank() && !tooLong && !badText
            IconButton(
                onClick = {
                    chat.send(contact, draft.trim())
                    draft = ""
                },
                enabled = canSend,
                modifier = Modifier.padding(start = 6.dp).size(48.dp).clip(CircleShape)
                    .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(SmIcons.Send, contentDescription = "Отправить", tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    soon?.let { SoonSheet(it) { soon = null } }
    info?.let { id -> lines.firstOrNull { it.rowId == id }?.let { MessageInfoSheet(it) { info = null } } }
}

@Composable
private fun DayChip(at: Long) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            dayLabel(at),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** Пузырь: долгое нажатие — меню сообщения («Скоро»), нажатие на время своего — сведения о сообщении. */
@Composable
private fun Bubble(m: ChatLine, onInfo: () -> Unit, soon: (Soon) -> Unit) {
    val mine = m.mine
    Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp).pointerInput(Unit) { detectTapGestures(onLongPress = { soon(Soon.Menu) }) },
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(m.text)
                val metaTap = if (mine) Modifier.clickable(onClick = onInfo) else Modifier
                Row(Modifier.align(Alignment.End).then(metaTap), verticalAlignment = Alignment.CenterVertically) {
                    val meta = MaterialTheme.colorScheme.onSurfaceVariant
                    when {
                        !mine -> Text(clock(m.at), style = MaterialTheme.typography.labelSmall, color = meta)
                        m.state == MessageState.Delivered -> {
                            Text("${clock(m.at)} · ${if (m.via == MessagePath.Node) INTERNET else CHANNEL}", style = MaterialTheme.typography.labelSmall, color = meta)
                            Icon(SmIcons.Delivered, contentDescription = "доставлено", modifier = Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        m.state == MessageState.Queued -> {
                            // UX-29: долго не доставлено — не ошибка, продолжаем пробовать (ADR-40).
                            val long = System.currentTimeMillis() - m.at > LONG_WAIT_MS
                            Text(
                                if (long) "${clock(m.at)} · ещё не доставлено" else "${clock(m.at)} · ${if (m.onNode) INTERNET else CHANNEL}",
                                style = MaterialTheme.typography.labelSmall,
                                color = meta,
                            )
                            Icon(SmIcons.Clock, contentDescription = "на телефоне, ждёт связи", modifier = Modifier.padding(start = 4.dp).size(14.dp), tint = meta)
                        }
                        else -> {
                            // Отправка невозможна (очередь переполнена) — единственный красный статус (UX-29).
                            Text("${clock(m.at)} · не доставлено", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            Icon(SmIcons.Warn, contentDescription = null, modifier = Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Код безопасности (макет v7, UX-4): 60 цифр, одинаковые на обоих телефонах; совпали — контакт
 * «сверен». Сверка по QR — заглушка «Скоро» (макет v10): пока сверка только глазами.
 */
@Composable
fun VerifyScreen(chat: ChatHub, contact: Contact, onBack: () -> Unit, onVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf<String?>(null) }
    var soon by remember { mutableStateOf<Soon?>(null) }
    LaunchedEffect(contact.address) { number = withContext(Dispatchers.IO) { chat.safetyNumber(contact) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(SmIcons.Back, contentDescription = "Назад") }
            Text("Код безопасности", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
        Text(
            "Сравните с экраном у ${contact.name}: цифры должны совпасть. Сверяйте при встрече или по звонку, где узнаёте голос.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
        val n = number
        if (n == null) {
            Muted("Код не посчитался: ключ контакта не читается.")
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                n.chunked(DIGITS_PER_GROUP).chunked(GROUPS_PER_ROW).forEach { row ->
                    Text(row.joinToString("   "), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        if (contact.verified) {
            Muted("Контакт уже сверен.")
        } else if (n != null) {
            Button(
                onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { runCatching { chat.book.markVerified(contact.address) }.getOrNull() != null }
                        Toast.makeText(context, if (ok) "Контакт сверен" else "Не сохранилось — попробуйте ещё раз", Toast.LENGTH_SHORT).show()
                        if (ok) onVerified()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Icon(SmIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Совпадает — отметить сверенным", modifier = Modifier.padding(start = 8.dp))
            }
        }
        FilledTonalButton(onClick = { soon = Soon.VerifyQr }, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Icon(SmIcons.Scan, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("Сканировать QR", modifier = Modifier.padding(start = 8.dp, end = 8.dp))
            SoonTag()
        }
    }
    soon?.let { SoonSheet(it) { soon = null } }
}

/**
 * Сведения о контакте (макет v10): имя, сверен ли, «Код безопасности»; уведомления чата,
 * исчезающие сообщения и удаление контакта — заглушки «Скоро».
 */
@Composable
fun ContactInfoScreen(contact: Contact, onBack: () -> Unit, onVerify: () -> Unit) {
    var soon by remember { mutableStateOf<Soon?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(SmIcons.Back, contentDescription = "Назад") }
            Text("Контакт", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxWidth().padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(contact.name, 88)
            Text(contact.name, style = MaterialTheme.typography.headlineSmall)
            if (contact.verified) {
                Text("Код сверен", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            } else {
                NotVerifiedTag()
            }
        }
        SectionTitle("Безопасность")
        SettingRow(SmIcons.Qr, "Код безопасности", "Сверить цифрами", onClick = onVerify)
        SectionTitle("Чат")
        SoonRow(SmIcons.Bell, "Уведомления", "Включены", Soon.ChatNotifications) { soon = it }
        SoonRow(SmIcons.Timer, "Исчезающие сообщения", "Выкл.", Soon.Vanishing) { soon = it }
        SoonRow(SmIcons.Exit, "Удалить контакт", null, Soon.DeleteContact, danger = true) { soon = it }
    }
    soon?.let { SoonSheet(it) { soon = null } }
}

@Composable
private fun Muted(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
}

private fun dayOf(at: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
}

private fun dayLabel(at: Long): String {
    val today = dayOf(System.currentTimeMillis())
    val yesterday = dayOf(System.currentTimeMillis() - DAY_MS)
    return when (dayOf(at)) {
        today -> "Сегодня"
        yesterday -> "Вчера"
        else -> SimpleDateFormat("d MMMM", Locale.forLanguageTag("ru")).format(Date(at))
    }
}

internal fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(at))

private fun shortTime(at: Long): String = when (dayOf(at)) {
    dayOf(System.currentTimeMillis()) -> clock(at)
    dayOf(System.currentTimeMillis() - DAY_MS) -> "вчера"
    else -> SimpleDateFormat("dd.MM", Locale.forLanguageTag("ru")).format(Date(at))
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/** Ядро примет такой текст (`wire::text`): та же проверка, что при отправке. */
private fun textOk(text: String): Boolean =
    try {
        chatEncode(ChatMessage(ByteArray(16), 0u, ChatBody.Text(text)))
        true
    } catch (_: SignalFfiException) {
        false
    }
