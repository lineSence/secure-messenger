package dev.sm.messenger.relay

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.contacts.QrScanner
import dev.sm.messenger.contacts.ScanBox
import dev.sm.messenger.ui.BtnKind
import dev.sm.messenger.ui.CardTitle
import dev.sm.messenger.ui.HeroIcon
import dev.sm.messenger.ui.Muted
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmButton
import dev.sm.messenger.ui.SmCard
import dev.sm.messenger.ui.SmHeader
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.Tag
import dev.sm.messenger.ui.TrRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.sm_ffi.NodeFfiException
import uniffi.sm_ffi.NodeInfo
import uniffi.sm_ffi.nodeRecordOpen
import uniffi.sm_ffi.queuePassOpen

/** Свой узел сейчас: запись ([info] — верна и в сроке), была ли запись ([had]), есть ли пропуск. */
internal data class NodeNow(val info: NodeInfo?, val had: Boolean, val pass: Boolean, val link: NodeLink)

/** Что прочитано из QR или буфера: запись на проверку или отказ. */
private sealed interface Import {
    data class Preview(val text: String, val info: NodeInfo) : Import

    data class Bad(val title: String, val text: String) : Import
}

private fun loadNow(app: Application): NodeNow {
    val account = ChatHub.get(app).account
    val now = nowSecs()
    val text = account.nodeText()
    val info = text?.let {
        try {
            nodeRecordOpen(it, now.toULong())
        } catch (_: NodeFfiException) {
            null
        }
    }
    return NodeNow(info, text != null, account.pass(now) != null, RelayHub.get(app).link)
}

/**
 * «Профиль → Узел», «Связь → Узел» по макету `ui-v11.html` (`vNode`, ADR-81): принять запись узла
 * `SMN1:` из QR или текстом — после предпросмотра (подпись, число адресов, срок; сами адреса не
 * показываются); пропуск `SMP1:` (ADR-136) принимается тем же вводом без предпросмотра.
 */
@Composable
fun NodeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val scope = rememberCoroutineScope()
    var now by remember { mutableStateOf<NodeNow?>(null) }
    var imp by remember { mutableStateOf<Import?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var cameraDenied by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        while (true) {
            now = withContext(Dispatchers.IO) { runCatching { loadNow(app) }.getOrNull() }
            delay(REFRESH_MS)
        }
    }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun check(raw: String) {
        val text = raw.trim()
        if (text.startsWith(PASS_PREFIX)) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { ChatHub.get(app).account.setPass(text, nowSecs()) }.getOrNull() }
                if (ok == true) {
                    RelayHub.get(app).wake()
                    toast("Пропуск принят")
                    revision++
                } else {
                    imp = passProblem(text)
                }
            }
            return
        }
        imp = openRecord(text)
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraDenied = !ok
        scanning = ok
    }
    fun scan() {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanning = true
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }
    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isBlank()) toast("Буфер обмена пуст — скопируйте запись узла целиком") else check(text.take(MAX_TEXT))
    }
    fun accept(p: Import.Preview) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { ChatHub.get(app).account.setNode(p.text, nowSecs()) }.getOrNull() }
            imp = null
            if (ok == true) {
                RelayHub.get(app).wake()
                toast("Узел подключён · сообщения пойдут через интернет")
                revision++
            } else {
                imp = Import.Bad("Запись не принята", "На телефоне уже есть запись этого узла новее. Оставили её.")
            }
        }
    }

    NodeFrame(onBack) {
        val i = imp
        val n = now
        when {
            scanning -> {
                ScanBox("Наведите на QR записи узла") {
                    QrScanner(
                        onText = {
                            if (scanning) {
                                scanning = false
                                check(it.take(MAX_TEXT))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(Modifier.padding(horizontal = 12.dp)) { SmButton("Отмена", { scanning = false }, Modifier.fillMaxWidth(), kind = BtnKind.Tonal) }
            }
            i is Import.Preview -> NodePreview(i.info, onAccept = { accept(i) }) { imp = null }
            i is Import.Bad -> NodeProblem(i.title, i.text) { imp = null }
            n == null -> Muted("Загружаем…")
            n.info == null -> NodeNotConnected(n.had, cameraDenied, onScan = ::scan, onPaste = ::paste)
            else -> NodeConnected(n, onScan = ::scan, onPaste = ::paste) {
                toast("Запись уходит контактам сама, внутри шифрованного чата")
            }
        }
    }
}

/** Пункт «Узел» в Настройках, Профиле и на вкладке «Связь»: состояние по макету (`nodeSub`). */
@Composable
fun NodeRow(title: String = "Узел", withTag: Boolean = false, onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    var n by remember { mutableStateOf<NodeNow?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            n = withContext(Dispatchers.IO) { runCatching { loadNow(app) }.getOrNull() }
            delay(REFRESH_MS)
        }
    }
    NodeRowView(title, withTag, n, onClick)
}

@Composable
internal fun NodeRowView(title: String, withTag: Boolean, v: NodeNow?, onClick: () -> Unit) {
    if (v?.info == null) {
        val sub = if (v == null) null else "не подключён"
        SettingRow(SmIcons.Server, title, sub, trailing = { Tag("нет", Sm.colors.warn) }, onClick = onClick)
    } else {
        val c = Sm.colors
        val tag: (@Composable () -> Unit)? = if (withTag) {
            {
                when (v.link) {
                    NodeLink.Ok -> Tag("работает", c.ok)
                    NodeLink.Idle -> Tag("принят", c.warn)
                    NodeLink.Down, NodeLink.Offline -> Tag("не работает", c.bad)
                    else -> Tag("ждём", c.warn)
                }
            }
        } else {
            null
        }
        SettingRow(SmIcons.Server, title, "${linkTitle(v.link)} · адресов: ${v.info.domains.size}", trailing = tag, onClick = onClick)
    }
}

/** Каркас экрана «Узел»: шапка и прокручиваемое содержимое. */
@Composable
internal fun NodeFrame(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Sm.colors.bg).navigationBarsPadding()) {
        SmHeader("Узел", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
    }
}

/** Значок в плитке, заголовок и пояснение по центру (`.soonh`). */
@Composable
private fun HeroText(icon: ImageVector, title: String, text: String, extra: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeroIcon(SmIcons.thin(icon), Modifier.padding(vertical = 4.dp))
        Text(title, color = Sm.colors.tx, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (extra != null) Muted(extra, center = true)
        Muted(text, center = true)
    }
}

@Composable
internal fun NodeNotConnected(had: Boolean, cameraDenied: Boolean, onScan: () -> Unit, onPaste: () -> Unit) {
    SmCard {
        HeroText(
            SmIcons.Server,
            "Узел не подключён",
            "Узел — ваш сервер за рубежом: через него сообщения доходят на любом расстоянии. Запись узла даёт тот, " +
                "кто его настроил, — QR или текстом. Контакты пришлют новую запись сами, если узел сменится.",
            extra = if (had) "Срок прежней записи узла вышел — примите новую." else null,
        )
        if (cameraDenied) Muted("Без разрешения «Камера» QR не отсканировать — вставьте запись текстом.", center = true)
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmButton("Сканировать QR узла", onScan, Modifier.fillMaxWidth(), icon = SmIcons.Scan)
            SmButton("Вставить текстом", onPaste, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Copy)
        }
    }
}

@Composable
internal fun NodePreview(info: NodeInfo, onAccept: () -> Unit, onCancel: () -> Unit) {
    SmCard {
        CardTitle("Запись узла")
        TrRow("Подпись верна", "ключ владельца узла совпал с ключом в приложении", icon = SmIcons.Shield)
        TrRow("Адресов: ${info.domains.size}", if (info.domains.size > 1) "основной и запасные" else "один адрес", icon = SmIcons.Server)
        TrRow("Действует до ${day(info.expires.toLong())}", "новую запись пришлют контакты или владелец", icon = SmIcons.Timer, last = true)
        Muted("Адреса узла хранятся только на этом телефоне и не показываются: так их труднее узнать при осмотре телефона.", inset = 0.dp)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmButton("Принять", onAccept, Modifier.fillMaxWidth(), icon = SmIcons.Check)
            SmButton("Отмена", onCancel, Modifier.fillMaxWidth(), kind = BtnKind.Tonal)
        }
    }
}

@Composable
internal fun NodeProblem(title: String, text: String, onClose: () -> Unit) {
    SmCard {
        HeroText(SmIcons.Alert, title, text)
        SmButton("Понятно", onClose, Modifier.fillMaxWidth().padding(top = 14.dp))
    }
}

@Composable
internal fun NodeConnected(n: NodeNow, onScan: () -> Unit, onPaste: () -> Unit, onShare: () -> Unit) {
    val info = n.info ?: return
    val c = Sm.colors
    val lamp = when (n.link) {
        NodeLink.Ok -> c.ok
        NodeLink.Idle -> c.warn
        NodeLink.Down, NodeLink.Offline -> c.bad
        else -> c.warn
    }
    SmCard {
        TrRow(linkTitle(n.link), linkSub(n.link), lamp = lamp)
        TrRow("Адресов: ${info.domains.size}", "при сбое приложение пробует их по одному", icon = SmIcons.Server)
        TrRow("Запись узла: подпись верна", "действует до ${day(info.expires.toLong())}", icon = SmIcons.Shield, last = n.pass)
        if (!n.pass) {
            TrRow("Нет пропуска на узел", "без него не создать почту для контактов — вставьте пропуск текстом", icon = SmIcons.Alert, last = true)
        }
    }
    SectionTitle("Запись узла")
    SettingRow(SmIcons.Scan, "Принять новую запись", "QR или текстом от владельца узла", onClick = onScan)
    SettingRow(SmIcons.Copy, "Вставить текстом", "запись узла или пропуск", onClick = onPaste)
    SettingRow(SmIcons.Share, "Поделиться с контактом", "контакты получают её сами внутри чата", onClick = onShare)
    SmCard { Muted("Узел видит только зашифрованные сообщения и номера ящиков — без имён и текста. Адреса узла на экране не показываются.", inset = 0.dp) }
}

/** Проверка записи до «Принять»: подпись, срок, поля для [HttpsRelay]. */
private fun openRecord(text: String): Import {
    val info = try {
        nodeRecordOpen(text, nowSecs().toULong())
    } catch (e: NodeFfiException) {
        return when (e) {
            is NodeFfiException.BadSignature -> Import.Bad(
                "Подпись не сошлась",
                "Эту запись подписал не владелец узла, или она повреждена. Её нельзя принять — " +
                    "иначе сообщения пошли бы через чужой сервер.",
            )
            is NodeFfiException.Expired -> Import.Bad("Срок записи вышел", "Попросите у владельца узла свежую запись.")
            is NodeFfiException.BadFormat -> Import.Bad(
                "Это не запись узла",
                "Запись узла начинается с $NODE_PREFIX, пропуск — с $PASS_PREFIX. Скопируйте текст целиком.",
            )
        }
    }
    if (RelayAccount.endpoint(info) == null) {
        return Import.Bad("Запись не подходит", "Поля записи не проходят проверки приложения. Сообщите владельцу узла.")
    }
    return Import.Preview(text, info)
}

private fun passProblem(text: String): Import =
    try {
        queuePassOpen(text, nowSecs().toULong())
        Import.Bad("Пропуск не принят", "Не удалось сохранить пропуск. Попробуйте ещё раз.")
    } catch (e: NodeFfiException) {
        when (e) {
            is NodeFfiException.BadSignature ->
                Import.Bad("Подпись не сошлась", "Этот пропуск подписал не владелец узла, или он повреждён.")
            is NodeFfiException.Expired -> Import.Bad("Срок пропуска вышел", "Попросите у владельца узла новый пропуск.")
            is NodeFfiException.BadFormat -> Import.Bad("Это не пропуск", "Скопируйте текст пропуска целиком.")
        }
    }

private fun linkTitle(l: NodeLink): String = when (l) {
    NodeLink.Ok -> "Связь есть"
    NodeLink.Down -> "Узел не отвечает"
    NodeLink.Offline -> "Нет сети"
    NodeLink.Idle -> "Узел принят"
    NodeLink.None, NodeLink.Connecting -> "Подключение…"
}

private fun linkSub(l: NodeLink): String = when (l) {
    NodeLink.Ok -> "сообщения уходят через интернет"
    NodeLink.Down -> "интернет есть, узел не отвечает — пробуем запасные адреса по одному"
    NodeLink.Offline -> "телефон не в сети"
    NodeLink.Idle -> "подключится, когда появится контакт, с которым связь через узел"
    NodeLink.None, NodeLink.Connecting -> "подключаемся к узлу"
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("ru"))

private fun day(secs: Long): String = DAY.withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(secs))

private fun nowSecs(): Long = System.currentTimeMillis() / MS_PER_S

private const val NODE_PREFIX = "SMN1:"
private const val PASS_PREFIX = "SMP1:"
private const val MAX_TEXT = 8192
private const val REFRESH_MS = 2_000L
private const val MS_PER_S = 1000L
