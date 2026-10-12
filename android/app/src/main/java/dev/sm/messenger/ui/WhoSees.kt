package dev.sm.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp

/** Посредник и что он знает (ADR-140). */
private class Seer(val icon: ImageVector, val who: String, val sees: String, val notSees: String)

/**
 * Список «Кто что видит» по макету `ui-v13.html` (ADR-140). Текст сверяется перед каждым релизом и
 * меняется вместе с транспортами. Код к ссылке необязательный (ADR-142), фраза о нём — как в макете.
 */
private val SEERS = listOf(
    Seer(
        SmIcons.Send,
        "Telegram, MAX и другой канал, где вы переслали ссылку",
        "Текст ссылки. Без кода из него можно достать адрес узла и открыть ссылку первым. С кодом — только случайный набор знаков.",
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
    WhoSeesContent(version, onBack)
}

@Composable
internal fun WhoSeesContent(version: String, onBack: () -> Unit) {
    val c = Sm.colors
    Column(Modifier.fillMaxSize().background(c.bg).navigationBarsPadding()) {
        SmHeader("Кто что видит", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Muted(
                "Честно и коротко: что знает каждый, через кого проходит связь. Если вам говорят «мы и так всё видим» — " +
                    "сверьтесь с этим списком.",
            )
            SEERS.forEach { s ->
                SmCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(s.icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.tx)
                        Text(s.who, color = c.tx, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(labeled("Видит:", s.sees), color = c.tx, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    Text(labeled("Не видит:", s.notSees), color = c.tx2, fontSize = 15.sp, lineHeight = 21.sp)
                }
            }
            Muted("Обновляется с каждой версией приложения. Версия списка: $version.")
            BottomSpace()
        }
    }
}

private fun labeled(label: String, text: String) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
    append(" ")
    append(text)
}
