package dev.sm.messenger.relay

import android.Manifest
import android.app.Application
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sm.messenger.chat.ChatHub
import dev.sm.messenger.contacts.QrScanner
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.SettingRow
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.SoonTag
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
private data class NodeNow(val info: NodeInfo?, val had: Boolean, val pass: Boolean, val link: NodeLink)

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(SmIcons.Back, contentDescription = "Назад") }
            Text("Узел", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val i = imp
            val n = now
            when {
                scanning -> Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        QrScanner(onText = {
                            if (scanning) {
                                scanning = false
                                check(it.take(MAX_TEXT))
                            }
                        })
                        Muted("Наведите на QR записи узла")
                        OutlinedButton(onClick = { scanning = false }, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
                    }
                }
                i is Import.Preview -> Preview(i.info, onAccept = { accept(i) }) { imp = null }
                i is Import.Bad -> Problem(i.title, i.text) { imp = null }
                n == null -> Muted("Загружаем…")
                n.info == null -> NotConnected(n.had, cameraDenied, onScan = ::scan, onPaste = ::paste)
                else -> Connected(n, onScan = ::scan, onPaste = ::paste) {
                    toast("Запись уходит контактам сама, внутри шифрованного чата")
                }
            }
        }
    }
}

/** Пункт «Узел» в Настройках, Профиле и на вкладке «Связь»: состояние по макету (`nodeSub`). */
@Composable
fun NodeRow(title: String = "Узел", onClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    var n by remember { mutableStateOf<NodeNow?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            n = withContext(Dispatchers.IO) { runCatching { loadNow(app) }.getOrNull() }
            delay(REFRESH_MS)
        }
    }
    val v = n
    if (v?.info == null) {
        val sub = if (v == null) null else "не подключён — примите запись узла"
        SettingRow(SmIcons.Server, title, sub, trailing = { SoonTag("нет") }, onClick = onClick)
    } else {
        SettingRow(SmIcons.Server, title, linkTitle(v.link).lowercase(Locale.ROOT), onClick = onClick)
    }
}

@Composable
private fun NotConnected(had: Boolean, cameraDenied: Boolean, onScan: () -> Unit, onPaste: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(SmIcons.Server, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Text("Узел не подключён", style = MaterialTheme.typography.titleMedium)
            if (had) Muted("Срок прежней записи узла вышел — примите новую.")
            Muted(
                "Узел — ваш сервер за рубежом: через него сообщения доходят на любом расстоянии. Запись узла даёт тот, " +
                    "кто его настроил, — QR или текстом. Контакты пришлют новую запись сами, если узел сменится.",
            )
            if (cameraDenied) Muted("Без разрешения «Камера» QR не отсканировать — вставьте запись текстом.")
            Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { ButtonLabel(SmIcons.Scan, "Сканировать QR узла") }
            OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth()) { ButtonLabel(SmIcons.Copy, "Вставить текстом") }
        }
    }
}

@Composable
private fun Preview(info: NodeInfo, onAccept: () -> Unit, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Запись узла", style = MaterialTheme.typography.titleMedium)
            Fact(SmIcons.Shield, "Подпись верна", "ключ владельца узла совпал с ключом в приложении", ok = true)
            Fact(SmIcons.Server, "Адресов: ${info.domains.size}", if (info.domains.size > 1) "основной и запасные" else "один адрес")
            Fact(SmIcons.Timer, "Действует до ${day(info.expires.toLong())}", "новую запись пришлют контакты или владелец")
            Muted(
                "Адреса узла хранятся только на этом телефоне и не показываются: так их труднее узнать при осмотре телефона.",
                center = false,
            )
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth()) { ButtonLabel(SmIcons.Check, "Принять") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
        }
    }
}

@Composable
private fun Problem(title: String, text: String, onClose: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(SmIcons.Alert, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.error)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(text)
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Понятно") }
        }
    }
}

@Composable
private fun Connected(n: NodeNow, onScan: () -> Unit, onPaste: () -> Unit, onShare: () -> Unit) {
    val info = n.info ?: return
    val ok = n.link == NodeLink.Ok
    val bad = n.link == NodeLink.Down || n.link == NodeLink.Offline
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(SmIcons.Wifi, linkTitle(n.link), linkSub(n.link), ok = ok, bad = bad)
            Fact(SmIcons.Server, "Адресов: ${info.domains.size}", "при сбое приложение пробует их по одному")
            Fact(SmIcons.Shield, "Запись узла: подпись верна", "действует до ${day(info.expires.toLong())}", ok = true)
            if (!n.pass) {
                val sub = "без него не создать почту для контактов — вставьте пропуск текстом"
                Fact(SmIcons.Alert, "Нет пропуска на узел", sub, bad = true)
            }
        }
    }
    SectionTitle("Запись узла")
    Card(Modifier.fillMaxWidth()) {
        SettingRow(SmIcons.Scan, "Принять новую запись", "QR от владельца узла", onClick = onScan)
        SettingRow(SmIcons.Copy, "Вставить текстом", "запись узла или пропуск", onClick = onPaste)
        SettingRow(SmIcons.Link, "Поделиться с контактом", "контакты получают её сами внутри чата", onClick = onShare)
    }
    Muted("Узел видит только зашифрованные сообщения и номера ящиков — без имён и текста. Адреса узла на экране не показываются.")
}

@Composable
private fun Fact(icon: ImageVector, title: String, sub: String, ok: Boolean = false, bad: Boolean = false) {
    val tint: Color = when {
        bad -> MaterialTheme.colorScheme.error
        ok -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Text(text, modifier = Modifier.padding(start = 8.dp))
}

@Composable
private fun Muted(text: String, center: Boolean = true) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth(),
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
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
    NodeLink.Ok -> "Работает"
    NodeLink.Down -> "Узел не отвечает"
    NodeLink.Offline -> "Нет сети"
    NodeLink.Idle -> "Узел принят"
    NodeLink.None, NodeLink.Connecting -> "Подключаемся"
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
