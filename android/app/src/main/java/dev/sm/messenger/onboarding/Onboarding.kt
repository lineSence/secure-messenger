package dev.sm.messenger.onboarding

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sm.messenger.keys.KeysState
import dev.sm.messenger.ui.AppLogo
import dev.sm.messenger.ui.Soon
import dev.sm.messenger.ui.SoonSheet
import dev.sm.messenger.ui.SoonTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Первый запуск (UX-17, макет `ui-v6.html`): приветствие → имя → создание ключей →
 * разрешения → готово. В `0.0.1-alpha` «Уже пользуюсь на другом устройстве» — заглушка «Скоро»
 * (`0.3.0`, макет v10), шага «Проверка связи» нет (узлов ещё нет); первый контакт — пункт 5 плана.
 *
 * [initial] — ключ уже создан (приложение закрыли посреди первого запуска): второй не создаётся.
 * [createKeys] создаёт ключ личности (вызывается не в главном потоке); [onDone] — итог.
 */
@Composable
fun Onboarding(profile: Profile, initial: KeysState, createKeys: () -> KeysState, onDone: (KeysState) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(profile.name.orEmpty()) }
    var keys by remember { mutableStateOf(initial.takeIf { it is KeysState.Ready }) }
    BackHandler(enabled = step == 1) { step = 0 }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (step > 0) Dots(step)
        when (step) {
            0 -> Welcome { step = 1 }
            1 -> NameStep(name, { name = it.take(Profile.MAX_NAME) }) {
                profile.name = name.trim()
                step = 2
            }
            2 -> KeysStep(keys, create = {
                keys = withContext(Dispatchers.Default) { createKeys() }
            }) {
                val k = keys
                if (k is KeysState.Ready) step = 3 else if (k != null) onDone(k)
            }
            else -> PermissionsStep {
                profile.onboarded = true
                keys?.let(onDone)
            }
        }
    }
}

@Composable
private fun Dots(step: Int) {
    Text("Шаг $step из $LAST_STEP", style = MaterialTheme.typography.labelMedium)
}

/** Приветствие (макет v10): значок и название; второе устройство — заглушка «Скоро» (0.3.0). */
@Composable
private fun Welcome(next: () -> Unit) {
    var soon by remember { mutableStateOf<Soon?>(null) }
    AppLogo(88)
    Text("Uncensored", style = MaterialTheme.typography.headlineSmall)
    Text("Связь для своих — личный мессенджер для семьи и близких.")
    Feature("Сквозное шифрование", "Сообщения читаете только вы и собеседники")
    Feature("Без номера и почты", "Ваш идентификатор — ключ, созданный на этом телефоне")
    Feature("Работает без интернета", "Телефоны рядом связываются по Bluetooth")
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Начать") }
    TextButton(onClick = { soon = Soon.SecondDevice }, modifier = Modifier.fillMaxWidth()) {
        Text("Уже пользуюсь на другом устройстве", modifier = Modifier.padding(end = 8.dp))
        SoonTag()
    }
    soon?.let { SoonSheet(it) { soon = null } }
}

@Composable
private fun Feature(title: String, text: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, next: () -> Unit) {
    Text("Как вас называть?", style = MaterialTheme.typography.headlineSmall)
    Text("Имя видят только ваши контакты. Изменить можно позже.")
    OutlinedTextField(
        value = name,
        onValueChange = onName,
        label = { Text("Имя") },
        singleLine = true,
        supportingText = { Text("Телефон и почта не нужны.") },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = next, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Далее") }
}

@Composable
private fun KeysStep(keys: KeysState?, create: suspend () -> Unit, next: () -> Unit) {
    LaunchedEffect(Unit) { if (keys == null) create() }
    when (keys) {
        null -> {
            Text("Создаём ключи…", style = MaterialTheme.typography.headlineSmall)
            Text("Ключи шифрования создаются на этом телефоне и никуда не отправляются.")
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is KeysState.Ready -> {
            Text("Ключи созданы", style = MaterialTheme.typography.headlineSmall)
            Text("Это ваш идентификатор. Контакты сверяют его при встрече по QR.")
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    identityText(keys.identity.publicKey()),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(16.dp),
                )
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Копии на сервере нет. Если потеряете телефон, переписку восстановить нельзя: " +
                        "другие свои устройства появятся в следующих версиях.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Понятно, дальше") }
        }
        else -> {
            Text("Ключи не созданы", style = MaterialTheme.typography.headlineSmall)
            Text("Хранилище ключей Android не ответило. Приложение дальше не работает.")
            Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Понятно") }
        }
    }
}

/** Разрешения «Уведомления» и «Работа в фоне» (UX-18): с объяснением, можно пропустить. */
@SuppressLint("BatteryLife") // приложение не из Google Play; без этого Android усыпляет связь
@Composable
private fun PermissionsStep(done: () -> Unit) {
    val context = LocalContext.current
    // До Android 13 разрешения на уведомления нет — они разрешены по умолчанию.
    fun notifGranted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    fun battGranted() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    var notif by remember { mutableStateOf(notifGranted()) }
    var batt by remember { mutableStateOf(battGranted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notif = it
        asked = true
    }
    val askBatt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batt = battGranted()
        asked = true
    }
    val all = notif && batt

    Text("Чтобы сообщения приходили сразу", style = MaterialTheme.typography.headlineSmall)
    Text("Приложение не пользуется серверами Google для уведомлений и держит своё соединение. Для этого нужны два разрешения.")
    PermRow("Уведомления", "Показывать новые сообщения", notif) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    PermRow("Работа в фоне", "Без ограничений батареи, иначе Android усыпит соединение", batt) {
        askBatt.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
    }
    Text("В шторке будет постоянное уведомление «Соединение активно» — так Android не останавливает приложение.")
    if (asked && !all) {
        Text(
            "Без этого сообщения могут приходить с опозданием.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (all) {
        Button(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Далее") }
    } else {
        OutlinedButton(onClick = done, modifier = Modifier.fillMaxWidth()) { Text("Пропустить") }
    }
}

@Composable
private fun PermRow(title: String, text: String, granted: Boolean, ask: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.padding(4.dp))
        if (granted) Text("разрешено", color = MaterialTheme.colorScheme.primary) else Button(onClick = ask) { Text("Разрешить") }
    }
}

private const val LAST_STEP = 3
