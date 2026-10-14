package dev.sm.messenger.contacts

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import dev.sm.messenger.onboarding.InvitationMemory
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints


import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.relay.InStage
import dev.sm.messenger.relay.InviteView
import dev.sm.messenger.relay.InviteFlow
import dev.sm.messenger.relay.MadeLink
import dev.sm.messenger.relay.OutLink
import dev.sm.messenger.relay.OutStage
import dev.sm.messenger.ui.Avatar
import dev.sm.messenger.ui.Banner
import dev.sm.messenger.ui.BtnKind
import dev.sm.messenger.ui.CardTitle
import dev.sm.messenger.ui.HeroIcon
import dev.sm.messenger.ui.Muted
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.SheetTitle
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmButton
import dev.sm.messenger.ui.SmCard
import dev.sm.messenger.ui.SmHeader
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.SmSheet
import dev.sm.messenger.ui.SmSwitch
import dev.sm.messenger.ui.Tag
import dev.sm.messenger.ui.TrRow
import dev.sm.messenger.ui.inputBox
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Вкладки «Новый контакт» по макету `design/mockups/ui-v7.html` (UX-25). */
enum class AddTab(val title: String) { My("Мой QR"), Scan("Сканировать"), Near("Рядом"), Link("Ссылка") }

/**
 * «Новый контакт»: Мой QR, Сканировать, Рядом, Ссылка (макет v7, ADR-62, UX-25; ссылка — v11, ADR-83).
 * [onAdded] — контакт сохранён: экран закрывается, подсказка показывает итог.
 */
@Composable
fun AddContactScreen(
    pairing: Pairing,
    invites: InviteFlow,
    start: AddTab,
    onBack: () -> Unit,
    onSee: () -> Unit,
    onNode: () -> Unit,
    initialLink: String? = null,
    onLinkChange: ((String) -> Unit)? = null,
    onOpenSaved: ((String) -> Unit)? = null,
    onAdded: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(start) }
    var blePermission by remember { mutableStateOf(hasAll(context, BleHub.PERMISSIONS)) }
    var bleDenied by remember { mutableStateOf(false) }
    var cameraPermission by remember { mutableStateOf(hasAll(context, arrayOf(Manifest.permission.CAMERA))) }
    var cameraDenied by remember { mutableStateOf(false) }
    // Действие, которое ждёт разрешения «Устройства поблизости».
    var afterBle by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterCamera by remember { mutableStateOf<(() -> Unit)?>(null) }

    val askBle = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        blePermission = res.values.isNotEmpty() && res.values.all { it }
        bleDenied = !blePermission
        if (blePermission) afterBle?.invoke()
        afterBle = null
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraPermission = ok
        cameraDenied = !ok
        if (ok) afterCamera?.invoke()
        afterCamera = null
    }

    fun withBle(action: () -> Unit) {
        if (blePermission) {
            action()
        } else {
            afterBle = action
            askBle.launch(BleHub.PERMISSIONS)
        }
    }

    fun withCameraAndBle(action: () -> Unit) {
        fun requestCamera() {
            if (cameraPermission) {
                action()
            } else {
                afterCamera = action
                askCamera.launch(Manifest.permission.CAMERA)
            }
        }
        if (blePermission) {
            requestCamera()
        } else {
            afterBle = { requestCamera() }
            askBle.launch(BleHub.PERMISSIONS)
        }
    }

    LaunchedEffect(tab) { pairing.idle() }
    LaunchedEffect(pairing.notice) {
        pairing.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            pairing.notice = null
        }
    }
    val state = pairing.state
    LaunchedEffect(state) {
        if (state is PairState.Done) {
            val text = if (state.verified) "Контакт «${state.name}» добавлен и сверен" else "Контакт «${state.name}» добавлен · не сверен"
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            onAdded()
        }
    }

    AddFrame(tab, onTab = { tab = it }, onBack = onBack) {
        if (bleDenied && tab != AddTab.Link) PermissionBanner("Без разрешения «Устройства поблизости» телефоны не обменяются ключами")
        if (state is PairState.Saved) {
            SavedContactContent(state) { onOpenSaved?.invoke(state.address) ?: onAdded() }
        } else when (tab) {
            AddTab.My -> MyQr(state, retry = { withBle { pairing.showQr() } })
            AddTab.Scan -> Scan(
                pairing,
                state,
                cameraPermission,
                cameraDenied,
                start = { withCameraAndBle { pairing.startScan() } },
            )
            AddTab.Near -> Near(pairing, state, start = { withBle { pairing.startNearby() } })
            AddTab.Link -> LinkTab(invites, onSee, onNode, initialLink, onLinkChange)
        }
    }
}

/** Каркас «Нового контакта»: шапка, вкладки (`.tabs`), прокручиваемое содержимое. */
@Composable
internal fun AddFrame(tab: AddTab, onTab: (AddTab) -> Unit, onBack: () -> Unit, content: @Composable () -> Unit) {
    val c = Sm.colors
    val largeTabs = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        SmHeader("Новый контакт", onBack = onBack, line = false)
        Row(Modifier.fillMaxWidth().background(c.hd).let { if (largeTabs) it.horizontalScroll(rememberScrollState()) else it }.selectableGroup()) {
            AddTab.entries.forEach { t ->
                val on = tab == t
                val color = if (on) c.hdt else c.hdt.copy(alpha = 0.7f)
                Row(
                    Modifier.let { if (largeTabs) it else it.weight(1f) }.selectable(selected = on, role = Role.Tab) { onTab(t) }.drawBehind {
                        if (on) drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
                    }.padding(start = if (largeTabs) 10.dp else 4.dp, end = if (largeTabs) 10.dp else 4.dp, top = 12.dp, bottom = 15.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = when (t) {
                        AddTab.My -> SmIcons.Qr
                        AddTab.Scan -> SmIcons.Scan
                        AddTab.Near -> SmIcons.Bt
                        AddTab.Link -> SmIcons.Link
                    }
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                    Text(t.title, color = color, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { content() }
    }
}

/** Пояснение по центру (`.center.muted`): поля 16/20. */
@Composable
private fun CenterMuted(text: String, tight: Boolean = false) {
    Muted(text, center = true, inset = if (tight) 16.dp else 20.dp, vertical = if (tight) 0.dp else 16.dp)
}

@Composable
internal fun MyQr(state: PairState, retry: () -> Unit) {
    CenterMuted(
        if (state == PairState.Idle) "Подготовьте Bluetooth, затем покажите QR собеседнику."
        else "Покажите код собеседнику при встрече — контакт будет сразу сверен.",
    )
    when (state) {
        is PairState.Showing -> {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.Center) {
                Box(
                    Modifier.size(208.dp)
                        .shadow(2.dp, RoundedCornerShape(16.dp), ambientColor = Color(0x33000000), spotColor = Color(0x33000000))
                        .clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp),
                ) { QrImage(state.qr) }
            }
            var now by remember { mutableLongStateOf(dev.sm.messenger.ui.UiClock.millis()) }
            LaunchedEffect(state.expiresAt) {
                while (true) {
                    now = dev.sm.messenger.ui.UiClock.millis()
                    delay(1_000)
                }
            }
            val left = ((state.expiresAt - now) / 1000).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(SmIcons.Timer, contentDescription = null, tint = Sm.colors.tx2, modifier = Modifier.size(14.dp))
                Text(
                    "  Код одноразовый, обновится через ${left / 60}:${"%02d".format(left % 60)}",
                    color = Sm.colors.tx2,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        PairState.Exchanging -> Progress("Обмениваемся ключами с телефоном рядом…")
        is PairState.Failed -> FailCard(state.text, "Показать новый код", retry)
        PairState.Idle -> SmCard {
            CardTitle("Подготовка к обмену")
            Muted("Bluetooth включается только для обмена ключами с телефоном рядом.", inset = 0.dp)
            SmButton("Подготовить Bluetooth", retry, Modifier.fillMaxWidth().padding(top = 8.dp), icon = SmIcons.Bt)
        }
        else -> Unit
    }
    BluetoothHint()
}

@Composable
private fun Scan(
    pairing: Pairing,
    state: PairState,
    camera: Boolean,
    cameraDenied: Boolean,
    start: () -> Unit,
) {
    when {
        state is PairState.Failed -> FailCard(state.text, "Сканировать снова", start)
        state == PairState.Exchanging -> Progress("QR прочитан. Обмениваемся ключами с телефоном рядом…")
        !camera -> {
            if (cameraDenied) PermissionBanner("Доступ к камере отклонён. Его можно разрешить в настройках Android.")
            SmCard {
                CardTitle("Разрешите доступ к камере")
                Muted("Камера нужна только для чтения QR. Bluetooth используется для передачи ключей между телефонами.", inset = 0.dp)
                SmButton("Разрешить доступ и открыть камеру", start, Modifier.fillMaxWidth().padding(top = 8.dp), icon = SmIcons.Scan)
            }
        }
        state == PairState.Scanning -> ScanBox { QrScanner(onText = pairing::onQrText, modifier = Modifier.fillMaxSize()) }
        else -> SmCard {
            CardTitle("Сканирование QR")
            Muted("Нажмите, чтобы начать сканирование и запросить нужные разрешения.", inset = 0.dp)
            SmButton("Открыть сканер", start, Modifier.fillMaxWidth().padding(top = 8.dp), icon = SmIcons.Scan)
        }
    }
    BluetoothHint()
}

/** Поле камеры (`.cam`): 280 dp, скругление 16, рамка-прицел 170 dp и подпись. */
@Composable
internal fun ScanBox(hint: String = "Наведите на QR собеседника", camera: @Composable () -> Unit) {
    Box(Modifier.padding(12.dp).fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1D1F22))) {
        camera()
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(170.dp).border(3.dp, Color.White, RoundedCornerShape(18.dp)))
            Text(hint, color = Color(0xFFCCCCCC), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun Near(pairing: Pairing, state: PairState, start: () -> Unit) = NearContent(
    state,
    start = start,
    onPick = pairing::pick,
    onAnswer = pairing::answer,
    onRetry = pairing::retryNearby,
)

@Composable
internal fun NearContent(state: PairState, start: () -> Unit, onPick: (NearbyPhone) -> Unit, onAnswer: (Boolean) -> Unit, onRetry: () -> Unit) {
    val c = Sm.colors
    when (state) {
        is PairState.Searching -> {
            SectionTitle("Рядом открыт этот же экран")
            SmCard {
                if (state.phones.isEmpty()) TrRow("Ищем…", "Откройте «Новый контакт → Рядом» на втором телефоне", last = true)
                state.phones.forEachIndexed { i, p ->
                    TrRow(
                        p.name,
                        if (p.strong) "Рядом · сигнал хороший" else "Рядом · сигнал слабый",
                        last = i == state.phones.lastIndex,
                        trailing = { SmButton("Добавить", { onPick(p) }, small = true) },
                    )
                }
            }
        }
        is PairState.Waiting -> SmCard {
            TrRow(state.name, "Ждём подтверждения на втором телефоне…", last = true, trailing = { CircularProgressIndicator(Modifier.size(24.dp), color = c.acc, strokeWidth = 2.dp) })
        }
        is PairState.Ask -> SmCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(state.name, 56)
                Text("Добавить «${state.name}»?", color = c.tx, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            }
            Text(
                buildAnnotatedString {
                    append("Имя «${state.name}» человек указал сам. Контакт будет помечен «не сверен»: по Bluetooth нельзя убедиться, что рядом именно этот человек.")
                },
                color = c.tx2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Muted("Сверить можно позже — отсканировать его QR при встрече или сравнить код безопасности.", inset = 0.dp)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmButton("Добавить", { onAnswer(true) })
                SmButton("Отмена", { onAnswer(false) }, kind = BtnKind.Tonal)
            }
        }
        is PairState.Failed -> FailCard(state.text, "Искать снова", onRetry)
        else -> {
            CenterMuted("Если встретиться с QR неудобно: откройте этот экран на обоих телефонах — они найдут друг друга по Bluetooth.")
            Box(Modifier.padding(horizontal = 12.dp)) { SmButton("Искать рядом", start, Modifier.fillMaxWidth(), icon = SmIcons.Bt) }
        }
    }
    SmCard {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Пока открыт этот экран и идёт поиск,") }
                append(" телефон виден другим телефонам с приложением рядом. Закройте экран — и он снова виден только вашим контактам.")
            },
            color = c.tx2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}

@Composable
private fun FailCard(text: String, action: String, onAction: () -> Unit) {
    SmCard {
        CardTitle("Не получилось")
        Muted(text, inset = 0.dp)
        SmButton(action, onAction, Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun Progress(text: String) {
    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), color = Sm.colors.acc, strokeWidth = 3.dp)
        Text(text, color = Sm.colors.tx, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun BluetoothHint() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
        Text(
            buildAnnotatedString { append("После сканирования телефоны обменяются ключами по Bluetooth — держите их рядом.") },
            color = Sm.colors.tx2,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
    }
}

@Composable
private fun PermissionBanner(text: String) {
    val context = LocalContext.current
    Banner(text, Sm.colors.bad, SmIcons.Alert) {
        SmButton("Настройки", {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }, small = true)
    }
}

private fun hasAll(context: android.content.Context, perms: Array<String>) =
    perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** Действия вкладки «Ссылка»: экран только показывает состояние [InviteView], работа — в [InviteFlow]. */
internal class LinkActions(
    val onNew: () -> Unit,
    val onRevoke: (OutLink) -> Unit,
    val onOpen: (String) -> Unit,
    val onOpenCode: (String, String) -> Unit,
    val onConfirm: (String) -> Unit,
    val onCancelIn: (String) -> Unit,
    val onCancelCode: () -> Unit,
    val onNode: () -> Unit,
)

/** «Ссылка» по макету `ui-v12.html` (ADR-83; необязательный код к ссылке — ADR-142). */
@Composable
private fun LinkTab(
    invites: InviteFlow,
    onSee: () -> Unit,
    onNode: () -> Unit,
    initialLink: String? = null,
    onLinkChange: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val v = invites.view
    var newSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { invites.refresh() }
    LaunchedEffect(v.added) {
        v.added?.let { Toast.makeText(context, "Контакт «$it» добавлен · не сверен", Toast.LENGTH_LONG).show() }
        if (v.added != null) invites.clearNotice()
    }
    v.made?.let { LinkMadeSheet(it, onClose = invites::clearMade) }
    if (newSheet) {
        NewLinkSheet(
            onDismiss = { newSheet = false },
            onSee = {
                newSheet = false
                onSee()
            },
            onCreate = { coded ->
                newSheet = false
                invites.create(coded)
            },
        )
    }
    LinkContent(
        v,
        LinkActions(
            onNew = { newSheet = true },
            onRevoke = { invites.revoke(it.secret) },
            onOpen = invites::open,
            onOpenCode = invites::openCoded,
            onConfirm = invites::confirm,
            onCancelIn = invites::cancel,
            onCancelCode = invites::cancelCode,
            onNode = onNode,
        ),
        initialText = initialLink,
        onTextChange = onLinkChange,
    )
}

@Composable
internal fun LinkContent(
    v: InviteView,
    act: LinkActions,
    initialText: String? = null,
    onTextChange: ((String) -> Unit)? = null,
) {
    var localText by remember { mutableStateOf("") }
    val activity = androidx.activity.compose.LocalActivity.current as? ComponentActivity
    val memory = remember(activity) { activity?.let { ViewModelProvider(it)[InvitationMemory::class.java] } }
    var localCode by remember { mutableStateOf("") }
    val code = memory?.code ?: localCode
    fun updateCode(value: String) { if (memory != null) memory.code = value else localCode = value }
    val controlled = initialText != null && onTextChange != null
    val text = if (controlled) initialText.orEmpty() else localText
    fun updateText(value: String) {
        if (controlled) onTextChange?.invoke(value) else localText = value
    }
    LaunchedEffect(v.added) {
        if (v.added != null) { updateText(""); updateCode("") }
    }
    if (v.problem != null) {
        dev.sm.messenger.ui.Banner(v.problem.text, Sm.colors.bad, SmIcons.Alert) {}
    }
    if (!v.loaded) {
        Progress("Загружаем ссылки…")
        return
    }
    val asking = v.ins.firstOrNull { it.stage == InStage.Ask }
    if (asking != null) {
        LinkConfirm(asking.name, v.busy, onAdd = { act.onConfirm(asking.inviter) }, onCancel = { act.onCancelIn(asking.inviter) })
        return
    }
    val askCode = v.askCode
    if (askCode != null) {
        CodeAsk(
            code = code,
            onCode = { updateCode(it.take(MAX_CODE_CHARS)) },
            wrong = v.codeWrong,
            busy = v.busy,
            onOpen = { act.onOpenCode(askCode, code) },
            onCancel = {
                updateCode("")
                act.onCancelCode()
            },
        )
        return
    }
    if (v.nodeReady) {
        SmCard {
            CardTitle("Пригласить по ссылке")
            Muted(
                "Ссылка одноразовая и действует 24 часа. Отправьте её любым способом — по ней человек добавится " +
                    "один раз. Пока вы не сверите код безопасности, контакт будет «не сверен».",
                inset = 0.dp,
            )
            SmButton("Создать ссылку", act.onNew, Modifier.fillMaxWidth().padding(top = 6.dp), icon = SmIcons.Link, enabled = !v.busy && v.problem != dev.sm.messenger.relay.LinkProblem.Full)
        }
    } else {
        SmCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                HeroIcon(SmIcons.Server)
                Text(
                    "Чтобы создавать ссылки, нужен узел",
                    color = Sm.colors.tx,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Muted(
                    "Ссылка работает только через узел в интернете. Ссылку от другого человека можно открыть и сейчас: " +
                        "запись узла и пропуск придут из неё. Если пропуска в ссылке нет, его даёт тот, кто настроил узел.",
                    center = true,
                    inset = 0.dp,
                )
                SmButton("Подключить узел", act.onNode, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
    if (v.outs.isNotEmpty()) {
        SectionTitle("Действующие ссылки")
        v.outs.forEach { l -> OutLinkRow(l, enabled = !v.busy) { act.onRevoke(l) } }
    }
    v.ins.filter { it.stage == InStage.Sent }.forEach { l ->
        SmCard {
            Progress("Проверяем ссылку…")
            Muted(
                "Ждём ответа пригласившего — его телефон должен быть в сети. Экран можно закрыть. " +
                    "Ответа нет долго — ссылку могли уже использовать: попросите новую.",
                inset = 0.dp,
            )
            SmButton("Отменить", { act.onCancelIn(l.inviter) }, Modifier.padding(top = 6.dp), kind = BtnKind.Tonal, enabled = !v.busy)
        }
    }
    SmCard {
        CardTitle("Получили ссылку?")
        Muted("Скопируйте текст ссылки из сообщения и вставьте сюда.", inset = 0.dp)
        TextInput(text, { updateText(it.take(MAX_LINK_CHARS)) }, "Текст ссылки")
        SmButton(
            "Открыть",
            { act.onOpen(text) },
            Modifier.padding(top = 10.dp),
            enabled = !v.busy && text.isNotBlank(),
        )
    }
}

/** Поле ввода (`.inp`): 46 dp, рамка, подсказка серым. */
@Composable
private fun TextInput(value: String, onChange: (String) -> Unit, hint: String, error: Boolean = false) {
    val c = Sm.colors
    Box(Modifier.fillMaxWidth().padding(top = 4.dp).inputBox().let { if (error) it.border(1.dp, c.bad, RoundedCornerShape(12.dp)) else it }.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            value,
            onChange,
            Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = c.tx, fontSize = 15.sp),
            cursorBrush = SolidColor(c.acc),
        )
        if (value.isEmpty()) Text(hint, color = c.tx2, fontSize = 15.sp)
    }
}

@Composable
private fun OutLinkRow(l: OutLink, enabled: Boolean, onRevoke: () -> Unit) {
    val made = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(l.createdAt))
    val minutes = ((l.expires - dev.sm.messenger.ui.UiClock.millis() / MS_PER_S) / SECS_PER_MIN).coerceAtLeast(0)
    val left = if (minutes >= MIN_PER_HOUR) "ещё ${minutes / MIN_PER_HOUR} ч ${minutes % MIN_PER_HOUR} мин" else "меньше часа"
    val state = if (l.stage == OutStage.Active) "не использована" else "обмениваемся ключами"
    SettingRow(
        SmIcons.Link,
        if (l.coded) "Ссылка от $made · с кодом" else "Ссылка от $made",
        "$left · $state",
        trailing = if (l.stage == OutStage.Active) {
            { SmButton("Отозвать", onRevoke, kind = BtnKind.Tonal, small = true, enabled = enabled) }
        } else {
            null
        },
        onClick = null,
    )
}

@Composable
private fun CenterHero(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        HeroIcon(icon)
        Text(title, color = Sm.colors.tx, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        Muted(text, center = true, inset = 0.dp)
    }
}

@Composable
private fun LinkConfirm(name: String, busy: Boolean, onAdd: () -> Unit, onCancel: () -> Unit) {
    SmCard {
        if (busy) {
            CenterHero(SmIcons.Refresh, "Обмениваемся ключами…", "Через узел — ключи шифрования видят только ваши телефоны.")
            return@SmCard
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(name, 72)
            Text("Добавить «$name»?", color = Sm.colors.tx, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            Tag("не сверен", Sm.colors.warn, big = true, icon = SmIcons.Alert)
            Muted(
                "Имя указал сам пригласивший. Ссылку мог переслать кто угодно — сверьте код безопасности при встрече или голосом.",
                center = true,
                inset = 0.dp,
                vertical = 10.dp,
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmButton("Добавить", onAdd, Modifier.fillMaxWidth(), icon = SmIcons.UserPlus)
                SmButton("Отмена", onCancel, Modifier.fillMaxWidth(), kind = BtnKind.Tonal)
            }
        }
    }
}

/** Лист «Новая ссылка» (макет `ui-v12.html`): по умолчанию без кода; код — для каналов, где переписку хранят открытой. */
@Composable
private fun NewLinkSheet(onDismiss: () -> Unit, onSee: () -> Unit, onCreate: (Boolean) -> Unit) {
    var coded by rememberSaveable { mutableStateOf(false) }
    SmSheet(onDismiss) { NewLinkContent(coded, { coded = it }, onSee, onCreate) }
}

@Composable
internal fun NewLinkContent(coded: Boolean, onCoded: (Boolean) -> Unit, onSee: () -> Unit, onCreate: (Boolean) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    Column(Modifier.heightIn(max = maxHeight)) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
    SheetTitle("Новая ссылка")
    Muted("Одноразовая, действует 24 часа.")
    Row(
        Modifier.fillMaxWidth().toggleable(value = coded, role = Role.Switch, onValueChange = onCoded).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Защитить кодом", color = Sm.colors.tx, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(
                buildAnnotatedString {
                    append("Для MAX и других каналов, где переписку хранят в открытом виде. Без кода тот, кто читает канал, узнаёт адрес вашего узла. ")
                    withLink(LinkAnnotation.Clickable("see", TextLinkStyles(SpanStyle(color = Sm.colors.acc2, fontWeight = FontWeight.Medium))) { onSee() }) {
                        append("Кто что видит")
                    }
                },
                color = Sm.colors.tx2,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        SmSwitch(coded, null)
    }
    }
    Box(Modifier.padding(horizontal = 16.dp)) {
        SmButton("Создать ссылку", { onCreate(coded) }, Modifier.fillMaxWidth(), icon = SmIcons.Link)
    }
    }
    }
}

/** Ввод 4 слов кода к ссылке (макет `ui-v12.html`). Проверка — на телефоне, узел о попытке не знает. */
@Composable
private fun CodeAsk(
    code: String,
    onCode: (String) -> Unit,
    wrong: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
) {
    SmCard {
        if (busy) {
            CenterHero(SmIcons.Refresh, "Проверяем код…", "Проверка идёт на этом телефоне.")
            return@SmCard
        }
        CardTitle("Ссылка защищена кодом")
        Muted(
            "Код — 4 слова. Его должен был сказать тот, кто прислал ссылку: голосом или при встрече. " +
                "Без кода ссылку не открыть — даже нам.",
            inset = 0.dp,
        )
        TextInput(code, onCode, "4 слова через пробел", error = wrong)
        if (wrong) {
            Text(
                "Код не подходит. Проверьте слова у того, кто прислал ссылку. " +
                    "Узел об этой попытке не узнаёт — проверка на телефоне.",
                color = Sm.colors.bad,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmButton("Открыть", onOpen, enabled = code.isNotBlank())
            SmButton("Отмена", onCancel, kind = BtnKind.Tonal)
        }
    }
}

@Composable
private fun LinkMadeSheet(made: MadeLink, onClose: () -> Unit) {
    val context = LocalContext.current
    val link = made.text
    SmSheet(onClose) {
        LinkMadeContent(
            link.take(LINK_PREVIEW) + "…",
            made.code,
            guestAccess = made.guestAccess,
            onShare = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, null))
                onClose()
            },
            onCopy = {
                val clip = ClipData.newPlainText("Ссылка-приглашение", link)
                // Android 13+: не показывать текст ссылки во всплывающем предпросмотре буфера.
                clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                onClose()
            },
        )
    }
}

@Composable
internal fun LinkMadeContent(preview: String, words: String?, onShare: () -> Unit, onCopy: () -> Unit, guestAccess: Boolean = true) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    Column(Modifier.heightIn(max = maxHeight)) {
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
    val c = Sm.colors
    SheetTitle("Ссылка создана")
    Muted("Одноразовая, действует 24 часа. Кто откроет её первым — тот и добавится; потом она перестанет работать.")
    if (!guestAccess) dev.sm.messenger.ui.Banner("Ссылка без гостевого доступа. Получателю нужен собственный пропуск узла. Получите гостевой доступ или объясните это до передачи ссылки.", c.warn, SmIcons.Alert) {}
    Text(
        preview,
        color = c.tx,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.hov).padding(10.dp),
    )
    if (words != null) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
            Muted("Код к ссылке — скажите его голосом или при встрече, не тем же мессенджером:", inset = 0.dp, vertical = 0.dp, modifier = Modifier.padding(bottom = 6.dp))
            Box(
                Modifier.fillMaxWidth().drawBehind {
                    drawRoundRect(c.line, cornerRadius = CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                }.padding(10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(words, color = c.tx, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
            Muted("Код больше не покажем. Без него ссылка не откроется.", inset = 0.dp, vertical = 0.dp, modifier = Modifier.padding(top = 6.dp))
        }
    }
    }
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    if (large) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmButton("Поделиться ссылкой", onShare, Modifier.fillMaxWidth(), icon = SmIcons.Share)
        SmButton("Копировать", onCopy, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Copy)
    } else Row(Modifier.padding(horizontal = 16.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmButton("Поделиться ссылкой", onShare, Modifier.weight(1f).fillMaxHeight(), icon = SmIcons.Share)
        SmButton("Копировать", onCopy, Modifier.weight(1f).fillMaxHeight(), kind = BtnKind.Tonal, icon = SmIcons.Copy)
    }
    }
    }
}

/** Самая длинная ссылка — около 5,7 тыс. знаков (запись узла до 4 КБ); с запасом. */
private const val MAX_LINK_CHARS = 8192
private const val LINK_PREVIEW = 32

/** 4 слова с запасом на разделители. */
private const val MAX_CODE_CHARS = 120
private const val MS_PER_S = 1000L
private const val SECS_PER_MIN = 60L
private const val MIN_PER_HOUR = 60L

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); на Android 12 флаг просто не читается. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

@Composable
internal fun SavedContactContent(saved: PairState.Saved, onOpen: () -> Unit) {
    SmCard {
        CenterHero(SmIcons.Check, "Контакт «${saved.name}» добавлен", if (saved.ready == true) "Контакт и адреса доставки сохранены." else "Контакт и ключи сохранены. Обмен адресами для интернет-доставки ещё не завершён. По Bluetooth можно переписываться рядом.")
        SmButton("Открыть чат", onOpen, Modifier.fillMaxWidth(), icon = SmIcons.Chats)
    }
}
