package dev.sm.messenger.contacts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.Soon
import dev.sm.messenger.ui.SoonTag
import kotlinx.coroutines.delay

/** Вкладки «Новый контакт» по макету `design/mockups/ui-v7.html` (UX-25). */
enum class AddTab(val title: String) { My("Мой QR"), Scan("Сканировать"), Near("Рядом"), Link("Ссылка") }

/**
 * «Новый контакт»: Мой QR, Сканировать, Рядом, Ссылка (макет v7, ADR-62, UX-25).
 * [onAdded] — контакт сохранён: экран закрывается, подсказка показывает итог.
 */
@Composable
fun AddContactScreen(pairing: Pairing, start: AddTab, onBack: () -> Unit, onAdded: () -> Unit) {
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
                AddTab.Link -> LinkSoon(show = { tab = it })
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

/** «Ссылка» (макет v10): заглушка 0.0.2-alpha — ссылке нужен интернет и свой узел. */
@Composable
private fun LinkSoon(show: (AddTab) -> Unit) {
    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(SmIcons.Link, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
            Text(Soon.Link.title, style = MaterialTheme.typography.titleMedium)
            SoonTag(Soon.Link.versionText)
            Text(Soon.Link.text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { show(AddTab.My) }, modifier = Modifier.fillMaxWidth()) { Text("Показать мой QR") }
            OutlinedButton(onClick = { show(AddTab.Near) }, modifier = Modifier.fillMaxWidth()) { Text("Искать рядом") }
        }
    }
}
