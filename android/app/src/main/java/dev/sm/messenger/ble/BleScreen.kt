package dev.sm.messenger.ble

import android.bluetooth.BluetoothAdapter
import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val PRUNE_EVERY_MS = 3_000L

/** Строка в Настройки → Связь (UX-23): подпись — состояние Bluetooth рядом. */
fun bleSubtitle(vm: BleHub): String {
    val name = vm.current?.label
    return when {
        vm.permissionDenied -> "Нет разрешения «Устройства поблизости»"
        vm.mode == BleMode.Off -> "Выключено"
        vm.mode == BleMode.Conn && name != null -> "Подключено: $name"
        vm.mode == BleMode.Lost && name != null -> "$name вне досягаемости"
        vm.peers.isNotEmpty() -> "Рядом: " + vm.peers.joinToString { it.label }
        else -> "Ищем телефоны рядом…"
    }
}

/** Экран «Bluetooth рядом» по макету `design/mockups/ui-v6.html` (UX-23, UX-24). */
@Composable
fun BleScreen(vm: BleHub, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onPermissionResult(res.values.isNotEmpty() && res.values.all { it })
    }
    val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (vm.adapterEnabled()) vm.turnOn()
    }
    val on = vm.mode != BleMode.Off

    LaunchedEffect(vm.notice) {
        vm.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.notice = null
        }
    }
    LaunchedEffect(on) {
        while (on) {
            delay(PRUNE_EVERY_MS)
            vm.prune()
        }
    }

    fun switchOn() {
        when {
            !vm.hasPermissions() -> askPermission.launch(BleHub.PERMISSIONS)
            !vm.adapterEnabled() -> askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> vm.turnOn()
        }
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextButton(onClick = onBack) { Text("Назад") }
            Text("Bluetooth рядом", style = MaterialTheme.typography.titleLarge)
        }

        if (vm.permissionDenied) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Без разрешения «Устройства поблизости» Bluetooth не работает",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text("Настройки") }
                }
            }
        }

        Card {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Использовать Bluetooth рядом", style = MaterialTheme.typography.titleMedium)
                    Text(if (on) "включено — телефон виден рядом" else "выключено")
                }
                Switch(
                    checked = on,
                    onCheckedChange = { if (it) switchOn() else vm.turnOff(byUser = true) },
                    modifier = Modifier.semantics { contentDescription = "Использовать Bluetooth рядом" },
                )
            }
        }

        if (on) {
            Text("Телефоны рядом", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cur = vm.current
                    val shown = vm.peers.filter { it.address != cur?.address } +
                        listOfNotNull(cur?.takeIf { vm.mode == BleMode.Conn || vm.mode == BleMode.Lost })
                    if (shown.isEmpty()) {
                        Text("Ищем…", style = MaterialTheme.typography.titleMedium)
                        Text("Держите телефоны в нескольких метрах друг от друга")
                    }
                    shown.forEach { PeerRow(vm, it) }
                }
            }
            // Метки контактов (ADR-61): видят и находят друг друга только контакты.
            Text(
                "Телефон виден только вашим контактам: метка в Bluetooth меняется каждые 15 минут, " +
                    "вычислить её может только контакт.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Card {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Связь без интернета: сообщения идут с телефона на телефон без узла, на расстоянии до ~10 м. " +
                        "Шифрование то же, что и через интернет. Пока это единственный способ связи в приложении.",
                )
                Text(
                    "Риск: пока включено, сканер Bluetooth поблизости видит устройство с меняющейся меткой; " +
                        "понять по метке, чьё оно, может только ваш контакт.",
                )
            }
        }
    }
}

@Composable
private fun PeerRow(vm: BleHub, peer: NearPeer) {
    val isCurrent = vm.current?.address == peer.address
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(peer.label, style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    isCurrent && vm.mode == BleMode.Conn -> "Подключено · сообщения идут напрямую"
                    isCurrent && vm.mode == BleMode.Lost -> "Вне досягаемости · сообщения ждут в очереди"
                    peer.strong -> "Рядом · сигнал хороший"
                    else -> "Рядом · сигнал слабый"
                },
            )
        }
        when {
            isCurrent && vm.mode == BleMode.Conn -> Text("связь есть", color = MaterialTheme.colorScheme.primary)
            isCurrent && vm.mode == BleMode.Lost -> Text("нет связи", color = MaterialTheme.colorScheme.error)
            else -> Button(onClick = { vm.connect(peer) }) { Text("Подключить") }
        }
    }
}
