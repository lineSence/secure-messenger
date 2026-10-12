package dev.sm.messenger.ble

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.sm.messenger.ui.Avatar
import dev.sm.messenger.ui.Banner
import dev.sm.messenger.ui.BottomSpace
import dev.sm.messenger.ui.Muted
import dev.sm.messenger.ui.SectionTitle
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmButton
import dev.sm.messenger.ui.SmCard
import dev.sm.messenger.ui.SmHeader
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.SmSwitch
import dev.sm.messenger.ui.Tag
import dev.sm.messenger.ui.TrRow
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

/** Один телефон рядом в списке: подпись, подключён ли он, потеряна ли связь. */
internal class PeerUi(val label: String, val state: PeerState, val strong: Boolean, val onConnect: () -> Unit)

internal enum class PeerState { Idle, Connected, Lost }

/** Вкладка «Связь» (макет v14): узел, Bluetooth рядом (переключатель, телефоны), остальные пути — [others]. */
@Composable
fun BleScreen(vm: BleHub, node: @Composable () -> Unit, others: @Composable ColumnScope.() -> Unit = {}) {
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

    val cur = vm.current
    val shown = vm.peers.filter { it.address != cur?.address } + listOfNotNull(cur?.takeIf { vm.mode == BleMode.Conn || vm.mode == BleMode.Lost })
    NetTabContent(
        on = on,
        permissionDenied = vm.permissionDenied,
        peers = shown.map { p ->
            val isCurrent = cur?.address == p.address
            PeerUi(
                p.label,
                when {
                    isCurrent && vm.mode == BleMode.Conn -> PeerState.Connected
                    isCurrent && vm.mode == BleMode.Lost -> PeerState.Lost
                    else -> PeerState.Idle
                },
                p.strong,
            ) { vm.connect(p) }
        },
        onSwitch = { if (it) switchOn() else vm.turnOff(byUser = true) },
        onSettings = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        },
        node = node,
        others = others,
    )
}

@Composable
internal fun NetTabContent(
    on: Boolean,
    permissionDenied: Boolean,
    peers: List<PeerUi>,
    onSwitch: (Boolean) -> Unit,
    onSettings: () -> Unit,
    node: @Composable () -> Unit,
    others: @Composable ColumnScope.() -> Unit,
) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        SmHeader("Связь")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionTitle("Сейчас работает")
            node()
            if (permissionDenied) {
                Banner("Без разрешения «Устройства поблизости» Bluetooth не работает", c.bad, SmIcons.Bt) {
                    SmButton("Настройки", onSettings, small = true)
                }
            }
            SmCard {
                TrRow(
                    "Использовать Bluetooth рядом",
                    if (on) "включено — телефон виден рядом" else "выключено",
                    icon = SmIcons.Bt,
                    last = true,
                    trailing = { SmSwitch(on, onSwitch, Modifier.semantics { contentDescription = "Использовать Bluetooth рядом" }) },
                )
            }
            if (on) {
                SectionTitle("Телефоны рядом")
                SmCard {
                    if (peers.isEmpty()) TrRow("Ищем…", "Держите телефоны в нескольких метрах друг от друга", last = true)
                    peers.forEachIndexed { i, p -> PeerRow(p, last = i == peers.lastIndex) }
                }
                // Метки контактов (ADR-61): видят и находят друг друга только контакты.
                Muted(
                    "Телефон виден только вашим контактам: метка в Bluetooth меняется каждые 15 минут, " +
                        "вычислить её может только контакт.",
                    center = true,
                )
            }
            SmCard {
                Text(
                    buildAnnotatedString {
                        append(
                            "Связь без интернета: сообщения идут с телефона на телефон без узла, на расстоянии до ~10 м. " +
                                "Шифрование то же, что и через интернет. Работает и без узла.\n\n",
                        )
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Риск:") }
                        append(
                            " пока включено, сканер Bluetooth поблизости видит устройство с меняющейся меткой; " +
                                "понять по метке, чьё оно, может только ваш контакт.",
                        )
                    },
                    color = c.tx2,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            others()
            BottomSpace()
        }
    }
}

@Composable
private fun PeerRow(p: PeerUi, last: Boolean) {
    val c = Sm.colors
    val sub = when (p.state) {
        PeerState.Connected -> "Подключено · сообщения идут напрямую"
        PeerState.Lost -> "Вне досягаемости · сообщения ждут в очереди"
        PeerState.Idle -> if (p.strong) "Рядом · сигнал хороший" else "Рядом · сигнал слабый"
    }
    TrRow(
        p.label,
        sub,
        lamp = if (p.state == PeerState.Connected) c.ok else null,
        last = last,
        leading = { Avatar(p.label, 36) },
        trailing = {
            when (p.state) {
                PeerState.Connected -> Tag("связь есть", c.ok)
                PeerState.Lost -> Tag("нет связи", c.bad)
                PeerState.Idle -> SmButton("Подключить", p.onConnect, small = true)
            }
        },
    )
}
