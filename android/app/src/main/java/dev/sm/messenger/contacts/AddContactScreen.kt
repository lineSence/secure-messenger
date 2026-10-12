package dev.sm.messenger.contacts

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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sm.messenger.ble.BleHub
import dev.sm.messenger.relay.InStage
import dev.sm.messenger.relay.InviteFlow
import dev.sm.messenger.relay.OutLink
import dev.sm.messenger.relay.OutStage
import dev.sm.messenger.ui.SmIcons
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

    val askBle = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        blePermission = res.values.isNotEmpty() && res.values.all { it }
        bleDenied = !blePermission
        if (blePermission) afterBle?.invoke()
        afterBle = null
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        cameraPermission = ok
        cameraDenied = !ok
    }

    fun withBle(action: () -> Unit) {
        if (blePermission) {
            action()
        } else {
            afterBle = action
            askBle.launch(BleHub.PERMISSIONS)
        }
    }

    LaunchedEffect(tab) {
        when (tab) {
            AddTab.My -> withBle { pairing.showQr() }
            AddTab.Scan -> {
                pairing.startScan()
                if (!cameraPermission) askCamera.launch(Manifest.permission.CAMERA)
                if (!blePermission) askBle.launch(BleHub.PERMISSIONS)
            }
            AddTab.Near, AddTab.Link -> pairing.idle()
        }
    }
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

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Назад") }
            Text("Новый контакт", style = MaterialTheme.typography.titleLarge)
        }
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            AddTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.title, maxLines = 1) })
            }
        }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (bleDenied && tab != AddTab.Link) PermissionBanner("Без разрешения «Устройства поблизости» телефоны не обменяются ключами")
            when (tab) {
                AddTab.My -> MyQr(state, retry = { withBle { pairing.showQr() } })
                AddTab.Scan -> Scan(pairing, state, cameraPermission, cameraDenied)
                AddTab.Near -> Near(pairing, state, start = { withBle { pairing.startNearby() } })
                AddTab.Link -> LinkTab(invites, onSee)
            }
        }
    }
}

@Composable
private fun MyQr(state: PairState, retry: () -> Unit) {
    Muted("Покажите код собеседнику при встрече — контакт будет сразу сверен.")
    when (state) {
        is PairState.Showing -> {
            QrImage(state.qr, modifier = Modifier.padding(horizontal = 24.dp))
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state.expiresAt) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1_000)
                }
            }
            val left = ((state.expiresAt - now) / 1000).coerceAtLeast(0)
            Muted("Код одноразовый, обновится через ${left / 60}:${"%02d".format(left % 60)}")
        }
        PairState.Exchanging -> Progress("Обмениваемся ключами с телефоном рядом…")
        is PairState.Failed -> FailCard(state.text, "Показать новый код", retry)
        else -> Unit
    }
    BluetoothHint()
}

@Composable
private fun Scan(pairing: Pairing, state: PairState, camera: Boolean, cameraDenied: Boolean) {
    when {
        state is PairState.Failed -> FailCard(state.text, "Сканировать снова") { pairing.startScan() }
        state == PairState.Exchanging -> Progress("QR прочитан. Обмениваемся ключами с телефоном рядом…")
        cameraDenied -> PermissionBanner("Без разрешения «Камера» QR не отсканировать")
        camera && state == PairState.Scanning -> {
            QrScanner(onText = pairing::onQrText)
            Muted("Наведите на QR собеседника")
        }
        else -> Unit
    }
    BluetoothHint()
}

@Composable
private fun Near(pairing: Pairing, state: PairState, start: () -> Unit) {
    when (state) {
        is PairState.Searching -> {
            Text("Рядом открыт этот же экран", style = MaterialTheme.typography.labelLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.phones.isEmpty()) {
                        PhoneRow("Ищем…", "Откройте «Новый контакт → Рядом» на втором телефоне")
                    }
                    state.phones.forEach { p ->
                        PhoneRow(p.name, if (p.strong) "Рядом · сигнал хороший" else "Рядом · сигнал слабый") {
                            Button(onClick = { pairing.pick(p) }) { Text("Добавить") }
                        }
                    }
                }
            }
        }
        is PairState.Waiting -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                PhoneRow(state.name, "Ждём подтверждения на втором телефоне…") { CircularProgressIndicator() }
            }
        }
        is PairState.Ask -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Добавить «${state.name}»?", style = MaterialTheme.typography.titleMedium)
                Muted(
                    "Имя «${state.name}» человек указал сам. Контакт будет помечен «не сверен»: по Bluetooth нельзя " +
                        "убедиться, что рядом именно этот человек.",
                    center = false,
                )
                Muted("Сверить можно позже — отсканировать его QR при встрече или сравнить код безопасности.", center = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pairing.answer(true) }) { Text("Добавить") }
                    OutlinedButton(onClick = { pairing.answer(false) }) { Text("Отмена") }
                }
            }
        }
        is PairState.Failed -> FailCard(state.text, "Искать снова") { pairing.retryNearby() }
        else -> {
            Muted("Если встретиться с QR неудобно: откройте этот экран на обоих телефонах — они найдут друг друга по Bluetooth.")
            Button(onClick = start, modifier = Modifier.fillMaxWidth()) { Text("Искать рядом") }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Пока открыт этот экран и идёт поиск, телефон виден другим телефонам с приложением рядом. " +
                "Закройте экран — и он снова виден только вашим контактам.",
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PhoneRow(title: String, sub: String, right: @Composable (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodyMedium)
        }
        right?.invoke()
    }
}

@Composable
private fun FailCard(text: String, action: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Не получилось", style = MaterialTheme.typography.titleMedium)
            Muted(text, center = false)
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
        }
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun BluetoothHint() =
    Muted("После сканирования телефоны обменяются ключами по Bluetooth — держите их рядом.")

@Composable
private fun Muted(text: String, center: Boolean = true) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp),
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun PermissionBanner(text: String) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            Button(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("Настройки") }
        }
    }
}

private fun hasAll(context: android.content.Context, perms: Array<String>) =
    perms.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

/** «Ссылка» по макету `ui-v11.html` (ADR-83; без кода к ссылке — ADR-141). */
@Composable
private fun LinkTab(invites: InviteFlow, onSee: () -> Unit) {
    val context = LocalContext.current
    val v = invites.view
    var text by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { invites.refresh() }
    LaunchedEffect(v.problem, v.added) {
        v.problem?.let { Toast.makeText(context, it.text, Toast.LENGTH_LONG).show() }
        v.added?.let { Toast.makeText(context, "Контакт «$it» добавлен · не сверен", Toast.LENGTH_LONG).show() }
        if (v.problem != null || v.added != null) invites.clearNotice()
    }
    v.made?.let { LinkMadeDialog(it, onClose = invites::clearMade) }
    if (!v.loaded) {
        Progress("Загружаем ссылки…")
        return
    }
    if (!v.nodeReady) {
        TextCard(
            "Сначала подключите узел",
            "Ссылка работает только через ваш узел в интернете. Запись узла и пропуск даёт тот, кто настроил узел.",
        )
        return
    }
    val asking = v.ins.firstOrNull { it.stage == InStage.Ask }
    if (asking != null) {
        LinkConfirm(asking.name, v.busy, onAdd = { invites.confirm(asking.inviter) }, onCancel = { invites.cancel(asking.inviter) })
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Пригласить по ссылке", style = MaterialTheme.typography.titleMedium)
            Muted(
                "Ссылка одноразовая и действует 24 часа. Отправьте её любым способом — по ней человек добавится " +
                    "один раз. Пока вы не сверите код безопасности, контакт будет «не сверен».",
                center = false,
            )
            Button(onClick = invites::create, enabled = !v.busy, modifier = Modifier.fillMaxWidth()) { Text("Создать ссылку") }
            TextButton(onClick = onSee) { Text("Кто что видит") }
        }
    }
    if (v.outs.isNotEmpty()) {
        Text("Действующие ссылки", style = MaterialTheme.typography.titleSmall)
        v.outs.forEach { l -> OutLinkRow(l, enabled = !v.busy) { invites.revoke(l.secret) } }
    }
    v.ins.filter { it.stage == InStage.Sent }.forEach { l ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Progress("Проверяем ссылку…")
                Muted(
                    "Ждём ответа пригласившего — его телефон должен быть в сети. Экран можно закрыть. " +
                        "Ответа нет долго — ссылку могли уже использовать: попросите новую.",
                    center = false,
                )
                TextButton(onClick = { invites.cancel(l.inviter) }, enabled = !v.busy) { Text("Отменить") }
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Получили ссылку?", style = MaterialTheme.typography.titleMedium)
            Muted("Скопируйте текст ссылки из сообщения и вставьте сюда.", center = false)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(MAX_LINK_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Текст ссылки") },
                maxLines = 4,
            )
            Button(
                onClick = {
                    invites.open(text)
                    text = ""
                },
                enabled = !v.busy && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Открыть") }
        }
    }
}

@Composable
private fun OutLinkRow(l: OutLink, enabled: Boolean, onRevoke: () -> Unit) {
    val made = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(l.createdAt))
    val hours = (l.expires - System.currentTimeMillis() / MS_PER_S) / SECS_PER_HOUR
    val left = if (hours >= 1) "ещё $hours ч" else "меньше часа"
    val state = if (l.stage == OutStage.Active) "не использована" else "обмениваемся ключами"
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(SmIcons.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Ссылка от $made")
            Text("$left · $state", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (l.stage == OutStage.Active) OutlinedButton(onClick = onRevoke, enabled = enabled) { Text("Отозвать") }
    }
}

@Composable
private fun LinkConfirm(name: String, busy: Boolean, onAdd: () -> Unit, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (busy) {
                Progress("Обмениваемся ключами…")
                Muted("Через узел — ключи шифрования видят только ваши телефоны.")
                return@Column
            }
            Text("Добавить «$name»?", style = MaterialTheme.typography.titleLarge)
            Text("не сверен", color = MaterialTheme.colorScheme.error)
            Muted("Имя указал сам пригласивший. Ссылку мог переслать кто угодно — сверьте код безопасности при встрече или голосом.")
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text("Добавить") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
        }
    }
}

@Composable
private fun LinkMadeDialog(link: String, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ссылка создана") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Одноразовая, действует 24 часа. Кто откроет её первым — тот и добавится; потом она перестанет работать.", center = false)
                Text(link.take(LINK_PREVIEW) + "…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                context.startActivity(Intent.createChooser(send, null))
                onClose()
            }) { Text("Поделиться") }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                val clip = ClipData.newPlainText("Ссылка-приглашение", link)
                // Android 13+: не показывать текст ссылки во всплывающем предпросмотре буфера.
                clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                onClose()
            }) { Text("Копировать") }
        },
    )
}

@Composable
private fun TextCard(title: String, text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(SmIcons.Link, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Muted(text)
        }
    }
}

/** Самая длинная ссылка — около 5,7 тыс. знаков (запись узла до 4 КБ); с запасом. */
private const val MAX_LINK_CHARS = 8192
private const val LINK_PREVIEW = 32
private const val MS_PER_S = 1000L
private const val SECS_PER_HOUR = 3600L

/** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); на Android 12 флаг просто не читается. */
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
