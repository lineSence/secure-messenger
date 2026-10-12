package dev.sm.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Посредник и что он знает (ADR-140). */
private class Seer(val icon: ImageVector, val who: String, val sees: String, val notSees: String)

/**
 * Список «Кто что видит» по макету `ui-v13.html` (ADR-140). Текст сверяется перед каждым релизом и
 * меняется вместе с транспортами. Кода к ссылке в 0.0.2-alpha нет (ADR-141) — фраза о нём из
 * макета не показывается.
 */
private val SEERS = listOf(
    Seer(
        SmIcons.Send,
        "Telegram, MAX и другой канал, где вы переслали ссылку",
        "Текст ссылки. Из него можно достать адрес узла и открыть ссылку первым.",
        "Ваши сообщения в Uncensored — они идут не через этот канал.",
    ),
    Seer(
        SmIcons.Wifi,
        "Оператор связи и ТСПУ",
        "Что телефон обращается к одному зарубежному адресу и домену, когда и сколько данных. Похоже на обычный сайт.",
        "Текст сообщений и с кем вы переписываетесь — внутри всё зашифровано.",
    ),
    Seer(
        SmIcons.Server,
        "Хостер узла и тот, кто изъял узел",
        "IP-адреса участников, время и размеры шифрованных писем в очередях. Очереди только в памяти — после перезагрузки их нет.",
        "Текст сообщений, имена и номера телефонов — их на узле нет.",
    ),
    Seer(SmIcons.Bt, "Сканер Bluetooth рядом", "Что рядом есть устройство с меняющейся меткой.", "Чьё это устройство — понимает только ваш контакт."),
    Seer(SmIcons.Lock, "Тот, у кого ваш разблокированный телефон", "Всё: переписку, контакты, адрес узла.", "—"),
)

/** «Кто что видит» (Настройки → Связь, «Новый контакт → Ссылка»): по посреднику — «видит / не видит». */
@Composable
fun WhoSeesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(SmIcons.Back, contentDescription = "Назад") }
            Text("Кто что видит", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Muted(
                "Честно и коротко: что знает каждый, через кого проходит связь. Если вам говорят «мы и так всё видим» — " +
                    "сверьтесь с этим списком.",
            )
            SEERS.forEach { s ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(s.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(s.who, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(labeled("Видит: ", s.sees), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            labeled("Не видит: ", s.notSees),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Muted("Обновляется с каждой версией приложения. Версия списка: $version.")
        }
    }
}

private fun labeled(label: String, text: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(label) }
    append(text)
}

@Composable
private fun Muted(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}
