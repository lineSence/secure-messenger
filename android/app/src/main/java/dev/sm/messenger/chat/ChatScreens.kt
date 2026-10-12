package dev.sm.messenger.chat

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.ble.BleMode
import dev.sm.messenger.contacts.AddTab
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.relay.NodeLink
import dev.sm.messenger.ui.Avatar
import dev.sm.messenger.ui.Banner
import dev.sm.messenger.ui.BtnKind
import dev.sm.messenger.ui.Folders
import dev.sm.messenger.ui.HeroIcon
import dev.sm.messenger.ui.Muted
import dev.sm.messenger.ui.NotVerifiedTag
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.SheetTitle
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmButton
import dev.sm.messenger.ui.SmCard
import dev.sm.messenger.ui.SmFab
import dev.sm.messenger.ui.SmHeader
import dev.sm.messenger.ui.SmIconButton
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.SmSheet
import dev.sm.messenger.ui.Soon
import dev.sm.messenger.ui.SoonRow
import dev.sm.messenger.ui.SoonSheet
import dev.sm.messenger.ui.SoonTag
import dev.sm.messenger.ui.Tag
import dev.sm.messenger.ui.bottomLine
import dev.sm.messenger.ui.topLine
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
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

/** Нейтральное название канала (UX-27, макет v14: «Bluetooth»). */
private const val CHANNEL = "Bluetooth"
private const val INTERNET = "Интернет"

/**
 * Состояние полосы связи (UX-2, UX-28, макет v14): узел в интернете и Bluetooth рядом вместе ([netStatus]).
 * Новое состояние показываем, только если оно держится ≥ 2 с (против мелькания).
 */
@Composable
internal fun rememberNetStatus(ble: BleHub): NetStatus {
    val link by rememberNodeLink()
    val want = netStatus(link, ble.mode)
    var status by remember { mutableStateOf(want) }
    LaunchedEffect(want) {
        if (want != status) {
            delay(NET_HOLD_MS)
            status = want
        }
    }
    return status
}

/** Полоса связи (`.netbar`); нажатие — вкладка «Связь». */
@Composable
fun NetBar(ble: BleHub, onClick: () -> Unit) = NetBarView(rememberNetStatus(ble), onClick)

/**
 * Полоса связи макета v14: когда всё хорошо — тихая (высота 30, «Связь есть» серым), иначе обычная (36) с пояснением;
 * лампа с ореолом, жёлтая мигает; справа «Связь >».
 */
@Composable
internal fun NetBarView(status: NetStatus, onClick: () -> Unit) {
    val c = Sm.colors
    val tone = when (status.tone) {
        Tone.Ok -> c.ok
        Tone.Warn -> c.warn
        Tone.Bad -> c.bad
    }
    val calm = status.tone == Tone.Ok
    val lampAlpha = if (status.tone == Tone.Warn) {
        val t = rememberInfiniteTransition(label = "lamp")
        t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "lampAlpha").value
    } else {
        1f
    }
    Row(
        Modifier.fillMaxWidth().background(c.sur).bottomLine(c.line).clickable(onClick = onClick)
            .heightIn(min = if (calm) 30.dp else 36.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(16.dp).alpha(lampAlpha), contentAlignment = Alignment.Center) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(tone.copy(alpha = 0.25f)))
            Box(Modifier.size(10.dp).clip(CircleShape).background(tone))
        }
        Text(
            status.title,
            color = if (calm) c.tx2 else c.tx,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = if (calm) FontWeight.Medium else FontWeight.SemiBold,
        )
        Text(status.sub, color = c.tx2, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (status.sub.isEmpty()) Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Связь", color = c.acc, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
            Icon(SmIcons.Chev, contentDescription = null, tint = c.acc, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Список чатов (макеты v14; пустой — UX-22). Поиск, папки «Личные»/«Группы» и «Новая группа» —
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
    ChatsContent(
        list = list,
        failed = failed && list == null,
        status = rememberNetStatus(ble),
        onSearch = { soon = Soon.Search },
        onFolder = { soon = Soon.Folders },
        onOpen = { openChat(it.contact) },
        openAdd = openAdd,
        onNew = { newChat = true },
        openNet = openNet,
    )
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

/** Экран «Чаты» без хранилища и связи: шапка, папки, полоса связи, список или пустой экран, кнопка «Новый чат». */
@Composable
internal fun ChatsContent(
    list: List<ChatSummary>?,
    failed: Boolean,
    status: NetStatus,
    onSearch: () -> Unit,
    onFolder: () -> Unit,
    onOpen: (ChatSummary) -> Unit,
    openAdd: (AddTab) -> Unit,
    onNew: () -> Unit,
    openNet: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Sm.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            SmHeader("Чаты", line = false) { SmIconButton(SmIcons.Search, "Поиск", onSearch) }
            if (!list.isNullOrEmpty()) {
                Folders(listOf("Все", "Личные", "Группы"), selected = 0, dim = setOf(1, 2)) { if (it != 0) onFolder() }
            }
            NetBarView(status, openNet)
            when {
                failed -> Muted("Чаты не открываются: хранилище на этом телефоне не отвечает.")
                list == null -> Unit
                list.isEmpty() -> EmptyChats(openAdd)
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.contact.rowId }) { s -> ChatRow(s) { onOpen(s) } }
                }
            }
        }
        SmFab(SmIcons.Edit, "Новый чат", onNew, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

/** «Новый чат» (макет v14): новый контакт; новая группа — заглушка 0.2.0. */
@Composable
private fun NewChatSheet(onDismiss: () -> Unit, addContact: () -> Unit, newGroup: () -> Unit) {
    SmSheet(onDismiss) {
        SheetTitle("Новый чат")
        SettingRow(SmIcons.UserPlus, "Новый контакт", "QR при встрече или Bluetooth рядом", onClick = addContact)
        SoonRow(SmIcons.Group, "Новая группа", Soon.Group.versionText.replaceFirstChar { it.lowercase() }, Soon.Group) { newGroup() }
    }
}

@Composable
internal fun NewChatSheetContent() {
    SheetTitle("Новый чат")
    SettingRow(SmIcons.UserPlus, "Новый контакт", "QR при встрече или Bluetooth рядом", onClick = {})
    SoonRow(SmIcons.Group, "Новая группа", Soon.Group.versionText.replaceFirstChar { it.lowercase() }, Soon.Group) {}
}

@Composable
internal fun EmptyChats(openAdd: (AddTab) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 44.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeroIcon(SmIcons.thin(SmIcons.Chats))
        Text("Пока нет чатов", color = Sm.colors.tx, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        Text(
            "Добавьте человека при встрече: покажите свой QR или найдите его телефон по Bluetooth рядом.",
            textAlign = TextAlign.Center,
            color = Sm.colors.tx2,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmButton("Показать мой QR", { openAdd(AddTab.My) }, Modifier.fillMaxWidth(), icon = SmIcons.Qr)
            SmButton("Добавить по Bluetooth рядом", { openAdd(AddTab.Near) }, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Bt)
        }
    }
}

@Composable
internal fun ChatRow(s: ChatSummary, onClick: () -> Unit) {
    val c = Sm.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(c.sur).clickable(onClick = onClick).heightIn(min = 72.dp)
            .drawBehind {
                val h = 1.dp.toPx()
                drawLine(c.line, Offset(80.dp.toPx(), size.height - h / 2), Offset(size.width, size.height - h / 2), h)
            }.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(s.contact.name, 54)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    s.contact.name,
                    color = c.tx,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!s.contact.verified) NotVerifiedTag()
                Spacer(Modifier.weight(1f))
                val last = s.last
                if (last != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (last.mine) {
                            // Как в макете (`lastIcon`): часы — ещё не доставлено, галочки — доставлено.
                            val sent = last.state == MessageState.Delivered
                            Icon(
                                if (sent) SmIcons.Delivered else SmIcons.Clock,
                                contentDescription = if (sent) "доставлено" else "ещё не доставлено",
                                modifier = Modifier.size(14.dp),
                                tint = c.acc2,
                            )
                        }
                        Text(shortTime(last.at), color = c.tx2, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                Text(
                    s.last?.text ?: "Нет сообщений",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = c.tx2,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.weight(1f),
                )
                if (s.unread > 0) {
                    Box(Modifier.heightIn(min = 22.dp).widthIn(min = 22.dp).clip(RoundedCornerShape(11.dp)).background(c.acc).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(s.unread.toString(), color = c.acct, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/**
 * Чат (макет v14): шапка с меткой «ключ не сверен», плашка «Сверить», пузыри со статусом
 * часы «на телефоне, ждёт связи» → галочка в круге «доставлено» (UX-26; шага «на узле» по
 * Bluetooth нет), канал — «Bluetooth» (UX-27), подсказка, когда собеседника нет рядом.
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
    val link by rememberNodeLink()
    ChatContent(
        contact = contact,
        sub = when {
            !contact.verified -> "ключ не сверен"
            near && ble.mode == BleMode.Conn && ble.current?.contact == contact.address -> "рядом · подключено"
            near -> "рядом"
            else -> "не рядом"
        },
        status = rememberNetStatus(ble),
        hint = chatHint(link, ble.mode, near),
        hintNode = link == NodeLink.None,
        lines = lines,
        broken = broken,
        draft = draft,
        onDraft = { draft = it },
        listState = listState,
        onBack = onBack,
        onVerify = onVerify,
        onInfo = onInfo,
        openNet = openNet,
        onSend = {
            chat.send(contact, draft.trim())
            draft = ""
        },
        onSoon = { soon = it },
        onMessageInfo = { info = it },
    )
    soon?.let { SoonSheet(it) { soon = null } }
    info?.let { id -> lines.firstOrNull { it.rowId == id }?.let { MessageInfoSheet(it) { info = null } } }
}

/** Чат без хранилища и связи: всё, что нужно для рисования, приходит параметрами. */
@Composable
internal fun ChatContent(
    contact: Contact,
    sub: String,
    status: NetStatus,
    hint: String?,
    hintNode: Boolean,
    lines: List<ChatLine>,
    broken: Int,
    draft: String,
    onDraft: (String) -> Unit,
    listState: LazyListState,
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onInfo: () -> Unit,
    openNet: () -> Unit,
    onSend: () -> Unit,
    onSoon: (Soon) -> Unit,
    onMessageInfo: (Long) -> Unit,
) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding().imePadding()) {
        SmHeader(contact.name, onBack = onBack, sub = sub, avatar = { Avatar(contact.name, 40) }, line = false) {
            SmIconButton(SmIcons.More, "Сведения о контакте", onInfo)
        }
        NetBarView(status, openNet)
        if (!contact.verified) {
            Banner("Контакт добавлен по Bluetooth рядом и ещё не сверен", c.warn, SmIcons.Shield) {
                SmButton("Сверить", onVerify, small = true)
            }
        }
        if (hint != null) {
            Banner(hint, c.warn, if (hintNode) SmIcons.Server else SmIcons.Wifi) {
                if (hintNode) SmButton("Узел", openNet, kind = BtnKind.Tonal, small = true)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().drawBehind { drawWall(c.wallA, c.wallB) },
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (broken > 0) item { Muted("Не открылось сообщений: $broken") }
            if (lines.isEmpty()) item { Muted("Сообщений пока нет. Напишите первым — сообщение уйдёт, когда появится связь.") }
            items(lines.size, key = { lines[it].rowId }) { i ->
                val m = lines[i]
                val prev = lines.getOrNull(i - 1)
                val next = lines.getOrNull(i + 1)
                if (prev == null || dayOf(prev.at) != dayOf(m.at)) DayChip(m.at)
                val tail = next == null || next.mine != m.mine || dayOf(next.at) != dayOf(m.at)
                Bubble(m, tail, onInfo = { onMessageInfo(m.rowId) }, soon = onSoon)
            }
        }
        val tooLong = draft.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES
        // Текст, который ядро не примет (невидимые, bidi, управляющие символы), — подсказка, а не молчаливый отказ.
        val badText = !tooLong && draft.isNotBlank() && !textOk(draft.trim())
        val canSend = draft.isNotBlank() && !tooLong && !badText
        Column(Modifier.fillMaxWidth().background(c.sur).topLine(c.line)) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SmIconButton(SmIcons.Smile, "Эмодзи", { onSoon(Soon.Emoji) }, tint = c.tx2)
                BasicTextField(
                    value = draft,
                    onValueChange = onDraft,
                    textStyle = TextStyle(color = c.tx, fontSize = 15.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(c.acc),
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 6.dp, vertical = 14.dp), contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) Text("Сообщение", color = c.tx2, fontSize = 15.sp, lineHeight = 20.sp)
                            inner()
                        }
                    },
                )
                SmIconButton(SmIcons.Attach, "Вложение", { onSoon(Soon.Attach) }, tint = c.tx2)
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (canSend) c.acc else c.acc.copy(alpha = 0.4f)).clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) { Icon(SmIcons.Send, contentDescription = "Отправить", tint = c.acct, modifier = Modifier.size(24.dp)) }
            }
            val err = when {
                tooLong -> "Слишком длинное сообщение"
                badText -> "Уберите невидимые и служебные символы"
                else -> null
            }
            if (err != null) Text(err, color = c.bad, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 64.dp, end = 16.dp, bottom = 6.dp))
        }
    }
}

/** Обои чата (`--wall`): градиент под углом 160°. */
private fun DrawScope.drawWall(a: Color, b: Color) {
    val rad = Math.toRadians(160.0)
    val dx = sin(rad).toFloat()
    val dy = -cos(rad).toFloat()
    val len = abs(size.width * dx) + abs(size.height * dy)
    val center = Offset(size.width / 2, size.height / 2)
    drawRect(Brush.linearGradient(listOf(a, b), start = center - Offset(dx, dy) * (len / 2), end = center + Offset(dx, dy) * (len / 2)))
}

@Composable
private fun DayChip(at: Long) {
    val c = Sm.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            dayLabel(at),
            color = c.sysct,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.sysc).padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** Что показать в подписи под пузырём: текст, значок, цвет, нажатие на время открывает сведения. */
private class Meta(val text: String, val icon: ImageVector?, val color: Color, val bold: Boolean = false)

@Composable
private fun metaOf(m: ChatLine): Meta {
    val c = Sm.colors
    return when {
        !m.mine -> Meta(clock(m.at), null, c.metac)
        m.state == MessageState.Delivered ->
            Meta("${clock(m.at)} · ${if (m.via == MessagePath.Node) INTERNET else CHANNEL}", SmIcons.Delivered, c.metaout)
        m.state == MessageState.Queued -> {
            // UX-29: долго не доставлено — не ошибка, продолжаем пробовать (ADR-40).
            val long = System.currentTimeMillis() - m.at > LONG_WAIT_MS
            // UX-26: часы — на телефоне, галочка — уже на узле (ждёт, пока получатель заберёт).
            val icon = if (m.onNode && !long) SmIcons.Check else SmIcons.Clock
            if (long) Meta("${clock(m.at)} · ещё не доставлено", icon, c.warn, bold = true)
            else Meta("${clock(m.at)} · ${if (m.onNode) INTERNET else CHANNEL}", icon, c.metaout)
        }
        // Отправка невозможна (очередь переполнена) — единственный красный статус (UX-29).
        else -> Meta("${clock(m.at)} · не доставлено", SmIcons.Warn, c.bad, bold = true)
    }
}

/**
 * Пузырь (`.m`): время внутри, справа в конце последней строки; долгое нажатие — меню сообщения («Скоро»),
 * нажатие на время своего — сведения о сообщении.
 */
@Composable
private fun Bubble(m: ChatLine, tail: Boolean, onInfo: () -> Unit, soon: (Soon) -> Unit) {
    val c = Sm.colors
    val mine = m.mine
    val meta = metaOf(m)
    val r = 16.dp
    val small = 4.dp
    val shape = RoundedCornerShape(
        topStart = r,
        topEnd = r,
        bottomStart = if (tail && !mine) small else r,
        bottomEnd = if (tail && mine) small else r,
    )
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.widthIn(max = maxWidth * 0.8f)
                .shadow(1.dp, shape, ambientColor = Color(0x1A000000), spotColor = Color(0x1A000000))
                .clip(shape).background(if (mine) c.bout else c.bin)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { soon(Soon.Menu) }) }
                .padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val metaStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (meta.bold) FontWeight.SemiBold else FontWeight.Normal)
            val metaW = with(density) {
                measurer.measure(meta.text, metaStyle).size.width.toDp() + (if (meta.icon != null) 17.dp else 0.dp) + 10.dp
            }
            val slot = with(density) { metaW.toSp() }
            Text(
                buildAnnotatedString {
                    append(m.text)
                    appendInlineContent("meta", "  ")
                },
                color = if (mine) c.boutt else c.tx,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                inlineContent = mapOf("meta" to InlineTextContent(Placeholder(slot, 14.sp, PlaceholderVerticalAlign.TextBottom)) {}),
            )
            val tap = if (mine) Modifier.clickable(onClick = onInfo) else Modifier
            Row(
                Modifier.align(Alignment.BottomEnd).offset(y = 2.dp).then(tap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(meta.text, color = meta.color, style = metaStyle)
                if (meta.icon != null) Icon(meta.icon, contentDescription = null, tint = meta.color, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * Код безопасности (макет v14, UX-4): 60 цифр, одинаковые на обоих телефонах; совпали — контакт
 * «сверен». Сверка по QR — заглушка «Скоро» (макет v10): пока сверка только глазами.
 */
@Composable
fun VerifyScreen(chat: ChatHub, contact: Contact, onBack: () -> Unit, onVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var number by remember { mutableStateOf<String?>(null) }
    var soon by remember { mutableStateOf<Soon?>(null) }
    LaunchedEffect(contact.address) { number = withContext(Dispatchers.IO) { chat.safetyNumber(contact) } }
    VerifyContent(
        contact = contact,
        number = number,
        onBack = onBack,
        onMatch = {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { chat.book.markVerified(contact.address) }.getOrNull() != null }
                Toast.makeText(context, if (ok) "Контакт сверен" else "Не сохранилось — попробуйте ещё раз", Toast.LENGTH_SHORT).show()
                if (ok) onVerified()
            }
        },
        onQr = { soon = Soon.VerifyQr },
    )
    soon?.let { SoonSheet(it) { soon = null } }
}

@Composable
internal fun VerifyContent(contact: Contact, number: String?, onBack: () -> Unit, onMatch: () -> Unit, onQr: () -> Unit) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        SmHeader("Код безопасности", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                "Сравните цифры с экраном собеседника — они должны совпасть. Сверяйте при встрече или по звонку, где узнаёте голос.",
                textAlign = TextAlign.Center,
                color = c.tx2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            )
            if (number == null) {
                Muted("Код не посчитался: ключ контакта не читается.")
            } else {
                SmCard {
                    Text(
                        number.chunked(DIGITS_PER_GROUP).chunked(GROUPS_PER_ROW).joinToString("\n") { it.joinToString(" ") },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        lineHeight = 31.sp,
                        letterSpacing = 1.sp,
                        color = c.tx,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (contact.verified) {
                    Muted("Контакт уже сверен.")
                } else if (number != null) {
                    SmButton("Совпадает — отметить сверенным", onMatch, Modifier.fillMaxWidth(), icon = SmIcons.Check)
                }
                Box(Modifier.fillMaxWidth()) {
                    SmButton("Сканировать QR", onQr, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Scan)
                    Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp)) { SoonTag() }
                }
            }
        }
    }
}

/**
 * Сведения о контакте (макет v14): имя, сверен ли, «Код безопасности»; уведомления чата и
 * исчезающие сообщения — заглушки «Скоро». «Удалить контакт» — лист с тем, что исчезнет и что
 * останется, затем [onRemove] и [onRemoved].
 */
@Composable
fun ContactInfoScreen(
    contact: Contact,
    onBack: () -> Unit,
    onVerify: () -> Unit,
    onRemove: (Contact, (Boolean) -> Unit) -> Unit,
    onRemoved: () -> Unit,
) {
    val context = LocalContext.current
    var soon by remember { mutableStateOf<Soon?>(null) }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    ContactInfoContent(contact, onBack, onVerify, onSoon = { soon = it }, onDelete = { confirm = true })
    soon?.let { SoonSheet(it) { soon = null } }
    if (confirm) {
        SmSheet(onDismiss = { if (!busy) confirm = false }) {
            DeleteContactContent(
                name = contact.name,
                busy = busy,
                onDismiss = { confirm = false },
                onConfirm = {
                    busy = true
                    onRemove(contact) { ok ->
                        busy = false
                        confirm = false
                        if (ok) {
                            Toast.makeText(context, "Контакт «${contact.name}» удалён с этого телефона", Toast.LENGTH_LONG).show()
                            onRemoved()
                        } else {
                            Toast.makeText(context, "Не удалось удалить контакт. Ничего не изменено.", Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
    }
}

@Composable
internal fun ContactInfoContent(contact: Contact, onBack: () -> Unit, onVerify: () -> Unit, onSoon: (Soon) -> Unit, onDelete: () -> Unit) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        SmHeader("Контакт", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(contact.name, 88)
                Text(contact.name, color = c.tx, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
                if (contact.verified) Tag("Код сверен", c.ok, big = true, icon = SmIcons.Shield) else Tag("Не сверен", c.warn, big = true, icon = SmIcons.Alert)
            }
            SectionTitle("Безопасность")
            SettingRow(SmIcons.Qr, "Код безопасности", "Сверить цифрами", onClick = onVerify)
            SectionTitle("Чат")
            SoonRow(SmIcons.Bell, "Уведомления", "Включены", Soon.ChatNotifications) { onSoon(it) }
            SoonRow(SmIcons.Timer, "Исчезающие сообщения", "Выкл.", Soon.Vanishing) { onSoon(it) }
            SettingRow(SmIcons.Exit, "Удалить контакт", null, danger = true, chevron = false, onClick = onDelete)
        }
    }
}

/** Содержимое листа «Удалить «…»?» (макет v14): что исчезнет, что останется; во время удаления — прогресс. */
@Composable
internal fun DeleteContactContent(name: String, busy: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Sm.colors
    if (busy) {
        SheetTitle("Удаляем «$name»…")
        Muted("Стираем переписку и ключи, закрываем очередь на узле.")
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
            Box(Modifier.fillMaxWidth(0.7f).height(6.dp).background(c.acc))
        }
        return
    }
    SheetTitle("Удалить «$name»?")
    Muted("Это нельзя отменить.")
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp)) {
        Text("С этого телефона исчезнет", color = c.tx, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        Bullets(
            "переписка, вложения и черновик",
            "ключи шифрования: новая переписка с этим контактом возможна только после нового обмена — по QR, Bluetooth или ссылке",
            "неотправленные сообщения и очередь доставки",
            "адрес почты контакта: ваша очередь для него на узле закрывается",
        )
        Text("Останется", color = c.tx, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
        Bullets(
            "у собеседника — его копия переписки; ему ничего не сообщается",
            "очередь на узле, если закрыть её не удалось, — до перезапуска узла; прочесть её уже некому; на телефоне удаление пройдёт сразу",
        )
    }
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmButton("Удалить контакт", onConfirm, Modifier.fillMaxWidth(), kind = BtnKind.Danger, icon = SmIcons.Exit)
        SmButton("Отмена", onDismiss, Modifier.fillMaxWidth(), kind = BtnKind.Tonal)
    }
}

/** Список с точками (`ul.muted`): 13 sp, отступ слева 18, между пунктами 4 dp сверху и снизу. */
@Composable
private fun Bullets(vararg lines: String) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        lines.forEach {
            Row(Modifier.padding(vertical = 4.dp)) {
                Text("•", color = Sm.colors.tx2, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.width(18.dp).padding(start = 4.dp))
                Text(it, color = Sm.colors.tx2, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
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
        else -> if (withinWeek(at)) {
            SimpleDateFormat("EEEE", RU).format(Date(at)).replaceFirstChar { it.uppercase() }
        } else {
            SimpleDateFormat("d MMMM", RU).format(Date(at))
        }
    }
}

internal fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("ru")).format(Date(at))

private fun shortTime(at: Long): String = when (dayOf(at)) {
    dayOf(System.currentTimeMillis()) -> clock(at)
    dayOf(System.currentTimeMillis() - DAY_MS) -> "вчера"
    else -> if (withinWeek(at)) SimpleDateFormat("EEE", RU).format(Date(at)) else SimpleDateFormat("dd.MM", RU).format(Date(at))
}

private val RU: Locale = Locale.forLanguageTag("ru")

/** Меньше недели назад — день недели вместо даты (макет v14: «Воскресенье», «вс»). */
private fun withinWeek(at: Long): Boolean = System.currentTimeMillis() - at < WEEK_MS

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val WEEK_MS = 6 * DAY_MS

/** Ядро примет такой текст (`wire::text`): та же проверка, что при отправке. */
private fun textOk(text: String): Boolean =
    try {
        chatEncode(ChatMessage(ByteArray(16), 0u, ChatBody.Text(text)))
        true
    } catch (_: SignalFfiException) {
        false
    }
