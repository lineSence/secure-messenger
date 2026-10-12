package dev.sm.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Что видно в интерфейсе 0.0.1-alpha, но ещё не сделано (макет `design/mockups/ui-v10.html`).
 * Версии — по плану `wiki/workflows/releases.md`; `null` — «в следующих версиях». Заглушка ничего
 * не делает: только показывает лист [SoonSheet].
 */
enum class Soon(val title: String, val version: String?, val text: String, val icon: ImageVector) {
    Group("Группы", "0.2.0", "Группы со сквозным шифрованием: участники, администраторы, сведения о группе.", SmIcons.Group),
    Folders("Папки «Личные» и «Группы»", "0.2.0", "Папки появятся вместе с группами. Сейчас все чаты — в одном списке.", SmIcons.Chats),
    Search("Поиск по чатам", null, "Поиск по именам и тексту сообщений — прямо на телефоне, без сервера.", SmIcons.Search),
    Emoji("Панель эмодзи", null, "Пока эмодзи можно вводить с клавиатуры телефона.", SmIcons.Smile),
    Attach("Фото и файлы", null, "Сейчас передаётся только текст: по Bluetooth файлы идут слишком медленно.", SmIcons.Attach),
    Menu(
        "Реакции, ответы, копирование",
        null,
        "Касание сообщения откроет меню: реакции, «Ответить», «Копировать», «Удалить у себя».",
        SmIcons.Reply,
    ),
    Link(
        "Ссылка-приглашение",
        "0.0.2-alpha",
        "Для ссылки нужен интернет и свой узел. Сейчас добавьте человека по QR при встрече или «Рядом».",
        SmIcons.Link,
    ),
    Bypass(
        "Обходные пути",
        "0.0.3-alpha",
        "Если интернет блокируют, приложение само найдёт работающий путь, в том числе при белых списках.",
        SmIcons.Refresh,
    ),
    Diagnostics(
        "Проверка связи и история",
        "0.0.3-alpha",
        "Что работает сейчас, какие пути проверены, история смен связи, отчёт группе.",
        SmIcons.Net,
    ),
    Lora(
        "LoRa (Reticulum)",
        "0.5.0",
        "Связь без интернета на километры через модуль LoRa (Heltec V3), подключённый по Bluetooth.",
        SmIcons.Radio,
    ),
    FileQr(
        "Файл или QR",
        null,
        "Передать зашифрованное сообщение без связи: показать QR человеку рядом или отправить файл.",
        SmIcons.Qr,
    ),
    Devices("Мои устройства", "0.3.0", "Второй телефон и компьютер с переносом истории.", SmIcons.Laptop),
    SecondDevice(
        "Подключение к своему аккаунту",
        "0.3.0",
        "Сейчас у каждого телефона свой ключ. Второе устройство можно будет подключить по QR с основного.",
        SmIcons.Laptop,
    ),
    Updates(
        "Обновления",
        "0.1.0-beta",
        "Проверка и установка обновлений с проверкой подписи. Сейчас новую версию ставят файлом поверх старой — " +
            "Android сам проверит, что подпись та же.",
        SmIcons.Download,
    ),
    Look("Оформление", null, "Компактный вид списка и сообщений. Тема — как в системе.", SmIcons.Palette),
    AppLock("Блокировка приложения", null, "Код или отпечаток при открытии приложения.", SmIcons.Lock),
    Privacy("Конфиденциальность", null, "Что видят контакты: отчёты о доставке, имя, время в сети.", SmIcons.Shield),
    ChatNotifications(
        "Уведомления этого чата",
        null,
        "Без звука для отдельного чата. Сейчас уведомления настраиваются в настройках Android.",
        SmIcons.Bell,
    ),
    Vanishing("Исчезающие сообщения", null, "Сообщения удаляются у обоих через выбранное время.", SmIcons.Timer),
    Rename(
        "Изменить имя",
        null,
        "Имя задаётся при первом запуске. Изменить его можно будет здесь — контакты увидят новое при следующей связи.",
        SmIcons.Edit,
    ),
    VerifyQr(
        "Сверка по QR",
        null,
        "Сейчас сверьте цифры с экраном собеседника — при встрече или голосом по другому каналу.",
        SmIcons.Scan,
    ),
    ;

    val versionText: String get() = version?.let { "Появится в версии $it" } ?: "Появится в следующих версиях"
}

/** Лист «Появится в версии …»: значок, название, что это будет, «Понятно». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoonSheet(item: Soon, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(item.icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary) }
            Text(item.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            SoonTag(item.versionText)
            Text(item.text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Понятно") }
        }
    }
}

/** Серая метка «Скоро» (или «Появится в версии …»). */
@Composable
fun SoonTag(text: String = "Скоро") {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Заголовок раздела в списке настроек. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** Строка списка: значок, название, пояснение и справа — метка или стрелка. */
@Composable
fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    danger: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing() else Icon(SmIcons.Chev, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Строка-заглушка: метка «Скоро», нажатие — лист [SoonSheet] через [open]. */
@Composable
fun SoonRow(icon: ImageVector, title: String, sub: String?, item: Soon, danger: Boolean = false, open: (Soon) -> Unit) {
    SettingRow(icon, title, sub, danger = danger, trailing = { SoonTag() }) { open(item) }
}
