package dev.sm.messenger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        "LoRa (MeshCore)",
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

/** Лист «Появится в версии …»: значок, название, что это будет, «Понятно» (`.soonh` макета). */
@Composable
fun SoonSheet(item: Soon, onDismiss: () -> Unit) = SmSheet(onDismiss) { SoonContent(item, onDismiss) }

@Composable
internal fun SoonContent(item: Soon, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HeroIcon(item.icon, Modifier.padding(vertical = 4.dp))
        Text(item.title, color = Sm.colors.tx, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        SoonTag(item.versionText, big = true)
        Muted(item.text, center = true)
    }
    SmButton("Понятно", onDismiss, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp))
}
