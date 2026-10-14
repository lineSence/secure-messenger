package dev.sm.messenger.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sm.messenger.keys.KeysState
import dev.sm.messenger.ui.AppLogo
import dev.sm.messenger.ui.BtnKind
import dev.sm.messenger.ui.HeroIcon
import dev.sm.messenger.ui.Sm
import dev.sm.messenger.ui.SmButton
import dev.sm.messenger.ui.SmCard
import dev.sm.messenger.ui.SmIconButton
import dev.sm.messenger.ui.SmIcons
import dev.sm.messenger.ui.Tag
import dev.sm.messenger.ui.Note
import dev.sm.messenger.ui.inputBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.sm_ffi.SignalFfiException
import uniffi.sm_ffi.pairFrameIntro

/**
 * Первый запуск по намерению (макет v16): получить приглашение, пригласить человека или добавить его при встрече.
 * Разрешения запрашиваются только после действия, которому они нужны. Сеть и обмен ключами не моделируются этим экраном.
 *
 * [initial] — ключ уже создан (приложение закрыли посреди первого запуска): второй не создаётся.
 * [createKeys] создаёт ключ личности (вызывается не в главном потоке); [onDone] — итог и маршрут, куда идти
 * дальше (`""` — к чатам, `add:<вкладка>` — «Новый контакт»).
 */
@Composable
fun Onboarding(
    profile: Profile,
    initial: KeysState,
    createKeys: () -> KeysState,
    onDone: (KeysState, String, String) -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var intent by rememberSaveable { mutableStateOf("nearby") }
    var name by rememberSaveable { mutableStateOf(profile.name.orEmpty()) }
    var invitation by remember { mutableStateOf("") }
    var keys by remember { mutableStateOf(initial.takeIf { it is KeysState.Ready }) }

    BackHandler(enabled = step != 0) {
        step = when (step) {
            5, 6 -> 0
            1 -> if (intent == "receive") 5 else 0
            2 -> 1
            4 -> 2
            else -> 0
        }
    }

    fun finish(route: String) {
        profile.onboarded = true
        keys?.let { onDone(it, route, if (route == "add:Link") invitation else "") }
    }

    when (step) {
        0 -> ObFrame(null, onBack = {}, actions = {}) {
            Welcome(
                onReceive = { intent = "receive"; step = 5 },
                onInvite = { intent = "invite"; step = 1 },
                onNearby = { intent = "nearby"; step = 1 },
                onHelp = { step = 6 },
            )
        }
        5 -> ObFrame(0, onBack = { step = 0 }, actions = {
            SmButton(
                "Продолжить",
                { intent = "receive"; step = 1 },
                Modifier.fillMaxWidth(),
                enabled = invitation.isNotBlank(),
            )
        }) {
            InvitationEntry(invitation) { invitation = it.take(MAX_PENDING_INVITATION_CHARS) }
        }
        6 -> ObFrame(0, onBack = { step = 0 }, actions = {
            SmButton("Продолжить настройку", { step = 0 }, Modifier.fillMaxWidth())
        }) {
            Hero(SmIcons.Link)
            Title("Переход из другого мессенджера")
            Para("Попросите знакомого прислать приглашение Uncensored и вставьте его текст в приложении.")
            Para("Перенос переписки из другого мессенджера не поддерживается. Не отправляйте посторонним ключи и коды безопасности.")
        }
        1 -> ObFrame(1, onBack = { step = if (intent == "receive") 5 else 0 }, actions = {
            SmButton("Далее", {
                profile.name = name.trim()
                step = 2
            }, Modifier.fillMaxWidth(), enabled = nameOk(name))
        }) {
            NameStep(name, nameOk(name)) { name = it.take(Profile.MAX_NAME) }
        }
        2 -> KeysStep(keys, onBack = { step = 1 }, create = {
            keys = withContext(Dispatchers.Default) { createKeys() }
        }) {
            val k = keys
            if (k is KeysState.Ready) {
                step = 4
            } else if (k != null) {
                onDone(k, "", invitation)
            }
        }
        else -> when (intent) {
            "nearby" -> ObFrame(3, onBack = { step = 2 }, actions = {
                SmButton("Показать мой QR", { finish("add:My") }, Modifier.fillMaxWidth(), icon = SmIcons.Qr)
                SmButton("Сканировать QR", { finish("add:Scan") }, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Scan)
                SmButton("Найти телефон по Bluetooth", { finish("add:Near") }, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Bt)
                SmButton("Позже", { finish("") }, Modifier.fillMaxWidth(), kind = BtnKind.Text)
            }) {
                Hero(SmIcons.UserPlus)
                Title("Добавьте первого человека")
                Para("При встрече можно обменяться QR или найти телефон рядом. Телефоны обменяются ключами по Bluetooth.")
                Note("Если выбрать Bluetooth, имя контакта не подтверждает его личность. Сверьте код безопасности позже.")
            }
            "invite" -> ObFrame(3, onBack = { step = 2 }, actions = {
                SmButton("Создать приглашение", { finish("add:Link") }, Modifier.fillMaxWidth(), icon = SmIcons.Link)
                SmButton("Пригласить при встрече", { finish("add:My") }, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Qr)
                SmButton("Позже", { finish("") }, Modifier.fillMaxWidth(), kind = BtnKind.Text)
            }) {
                Hero(SmIcons.Link)
                Title("Пригласите близкого")
                Para("Для приглашения по ссылке нужен ваш узел. Настройте его на следующем экране. Ссылка действует 24 часа и используется один раз.")
                Note("Если у получателя нет доступа к узлу, приглашение может не сработать. Не обходите проверку подписи узла.")
            }
            else -> ObFrame(3, onBack = { step = 2 }, actions = {
                SmButton("Проверить приглашение", { finish("add:Link") }, Modifier.fillMaxWidth(), icon = SmIcons.Link)
                SmButton("Добавить при встрече", { finish("add:My") }, Modifier.fillMaxWidth(), kind = BtnKind.Tonal, icon = SmIcons.Qr)
                SmButton("Позже", { finish("") }, Modifier.fillMaxWidth(), kind = BtnKind.Text)
            }) {
                Hero(SmIcons.Link)
                Title("Приглашение готово к проверке")
                Para("Сначала создайте ключи этого телефона. Затем приложение проверит приглашение и покажет следующий шаг.")
                Note("Если проверка завершится ошибкой, текст приглашения останется в поле.")
            }
        }
    }
}

/**
 * Каркас шага (`.ob`): белая поверхность, стрелка «назад» слева, полоски шагов ([step] — сколько закрашено;
 * `null` — без стрелки и полосок), содержимое и кнопки внизу ([actions]).
 */
@Composable
internal fun ObFrame(step: Int?, onBack: () -> Unit, actions: @Composable () -> Unit, content: @Composable () -> Unit) {
    val c = Sm.colors
    Box(Modifier.fillMaxSize().background(c.sur).statusBarsPadding().navigationBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            if (step != null) Dots(step)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 20.dp)) { content() }
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
        if (step != null) SmIconButton(SmIcons.Back, "Назад", onBack, Modifier.padding(start = 4.dp, top = 4.dp), tint = c.tx)
    }
}

/** Полоски шагов (`.obdots`): 22×4 dp, пройденные — акцентом; всего [TOTAL_DOTS]. */
@Composable
private fun Dots(step: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(TOTAL_DOTS) { i ->
            Box(Modifier.size(width = 22.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(if (i < step) Sm.colors.acc else Sm.colors.line))
        }
    }
}

@Composable
private fun Hero(icon: ImageVector) = Box(Modifier.padding(top = 14.dp, bottom = 10.dp)) { HeroIcon(icon) }

@Composable
private fun Title(text: String) {
    Text(text, color = Sm.colors.tx, fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
}

@Composable
private fun Para(text: String) {
    Text(text, color = Sm.colors.tx2, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 12.dp))
}

/** Первый экран v16: выбор действия без обязательного прохождения всех шагов. */
@Composable
internal fun Welcome(
    onReceive: () -> Unit = {},
    onInvite: () -> Unit = {},
    onNearby: () -> Unit = {},
    onHelp: () -> Unit = {},
) {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        AppLogo(88)
    }
    Title("Uncensored")
    Para("Связь для своих — личная переписка с близкими.")
    Para("Без номера телефона и почты. Выберите, как хотите начать.")
    StartChoice(SmIcons.Link, "Мне прислали приглашение", "Открыть текст, полученный от знакомого", onReceive)
    StartChoice(SmIcons.UserPlus, "Хочу пригласить близкого", "Создать приглашение и отправить ему", onInvite)
    StartChoice(SmIcons.Qr, "Мы сейчас рядом", "Добавить при встрече по QR или Bluetooth", onNearby)
    Text(
        "Уже начали настройку? Выполненные шаги сохраняются на этом телефоне.",
        color = Sm.colors.tx2,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    SmButton("Как перейти из другого мессенджера", onHelp, Modifier.fillMaxWidth(), kind = BtnKind.Text)
}

@Composable
private fun StartChoice(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    SmCard(padding = 0.dp) {
        dev.sm.messenger.ui.SettingRow(icon, title, subtitle, onClick = onClick)
    }
}

/** Поле приглашения. Текст хранится в saveable-состоянии до завершения первого запуска. */
@Composable
internal fun InvitationEntry(value: String, onValueChange: (String) -> Unit) {
    Hero(SmIcons.Link)
    Title("Вставьте приглашение знакомого")
    Para("Скопируйте весь текст приглашения из сообщения. После проверки приложение предложит добавить контакт.")
    Box(
        Modifier.fillMaxWidth().height(128.dp).clip(RoundedCornerShape(12.dp))
            .background(Sm.colors.bg).border(1.dp, Sm.colors.line, RoundedCornerShape(12.dp)).padding(14.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            textStyle = TextStyle(color = Sm.colors.tx, fontSize = 14.sp, lineHeight = 20.sp),
            cursorBrush = SolidColor(Sm.colors.acc),
        )
        if (value.isEmpty()) {
            Text("Вставьте текст приглашения", color = Sm.colors.tx2, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
    Note("Не вставляйте ключи безопасности или пропуски узла. При ошибке проверки текст можно исправить и отправить снова.")
}

@Composable
internal fun NameStep(name: String, valid: Boolean, onName: (String) -> Unit) {
    val c = Sm.colors
    Title("Как вас называть?")
    Para("Имя видят только ваши контакты. Изменить можно позже.")
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(92.dp).clip(CircleShape).background(Color(0xFF65AADD)), contentAlignment = Alignment.Center) {
            val first = name.trim().firstOrNull()
            if (first != null) Text(first.uppercase(), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Medium) else Icon(SmIcons.User, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
    Box(Modifier.fillMaxWidth().inputBox().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        BasicTextField(
            name,
            onName,
            Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = TextStyle(color = c.tx, fontSize = 15.sp),
            cursorBrush = SolidColor(c.acc),
        )
        if (name.isEmpty()) Text("Имя", color = c.tx2, fontSize = 15.sp)
    }
    val bad = name.isNotBlank() && !valid
    Text(
        if (bad) "Без невидимых и служебных символов." else "Телефон и почта не нужны.",
        color = if (bad) c.bad else c.tx2,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Имя пройдёт проверку ядра (`check_name`: без невидимых, bidi и управляющих символов) — иначе добавить контакт не выйдет. */
private fun nameOk(name: String): Boolean =
    name.isNotBlank() &&
        try {
            pairFrameIntro(name.trim())
            true
        } catch (_: SignalFfiException) {
            false
        }

@Composable
private fun KeysStep(keys: KeysState?, onBack: () -> Unit, create: suspend () -> Unit, next: () -> Unit) {
    LaunchedEffect(Unit) { if (keys == null) create() }
    when (keys) {
        null -> ObFrame(2, onBack, actions = {}) { KeysWait() }
        is KeysState.Ready -> ObFrame(2, onBack, actions = {
            SmButton("Понятно, дальше", next, Modifier.fillMaxWidth())
        }) { KeysDone(identityText(keys.identity.publicKey())) }
        else -> ObFrame(2, onBack, actions = { SmButton("Понятно", next, Modifier.fillMaxWidth()) }) {
            Hero(SmIcons.Key)
            Title("Ключи не созданы")
            Para("Хранилище ключей Android не ответило. Приложение дальше не работает.")
        }
    }
}

@Composable
internal fun KeysWait() {
    Hero(SmIcons.Key)
    Title("Создаём ключи…")
    Para("Ключи шифрования создаются на этом телефоне и никуда не отправляются.")
    LinearProgressIndicator(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
        color = Sm.colors.acc,
        trackColor = Sm.colors.line,
    )
}

@Composable
internal fun KeysDone(identity: String) {
    val c = Sm.colors
    Hero(SmIcons.Key)
    Title("Ключи созданы")
    Para("Это ваш идентификатор. Контакты сверяют его при встрече по QR.")
    Text(
        identity,
        color = c.tx,
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.hov).padding(14.dp),
    )
    Note(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Копии на сервере нет.") }
            append(" Если потеряете телефон, переписку восстановить нельзя: другие свои устройства появятся в следующих версиях.")
        },
        icon = SmIcons.Warn,
        tone = c.warn,
    )
}

/** Состояние разрешения на шаге: не просили · выдано · отказано. */
internal enum class Perm { Ask, Yes, No }

@Composable
internal fun PermissionsContent(notif: Perm, batt: Perm, onNotif: () -> Unit, onBatt: () -> Unit, onBack: () -> Unit, done: () -> Unit) {
    val all = notif == Perm.Yes && batt == Perm.Yes
    val asked = notif != Perm.Ask || batt != Perm.Ask
    ObFrame(3, onBack, actions = {
        SmButton(if (all) "Далее" else "Пропустить", done, Modifier.fillMaxWidth(), kind = if (all) BtnKind.Primary else BtnKind.Tonal)
    }) {
        Title("Чтобы сообщения приходили сразу")
        Para("Приложение не пользуется серверами Google для уведомлений и держит своё соединение. Для этого нужны два разрешения.")
        PermRow(SmIcons.Bell, "Уведомления", "Показывать новые сообщения", notif, onNotif)
        PermRow(SmIcons.Battery, "Работа в фоне", "Без ограничений батареи, иначе Android усыпит соединение", batt, onBatt)
        Note("В шторке будет постоянное уведомление «Соединение активно» — так Android не останавливает приложение.")
        if (asked && !all) {
            Note(
                "Без этого сообщения могут приходить с опозданием. Исправить можно в Настройки → Уведомления.",
                icon = SmIcons.Warn,
                tone = Sm.colors.warn,
            )
        }
    }
}

@Composable
private fun PermRow(icon: ImageVector, title: String, text: String, state: Perm, ask: () -> Unit) {
    val c = Sm.colors
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.sur).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c.acc, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = c.tx, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            Text(text, color = c.tx2, fontSize = 13.sp, lineHeight = 18.sp)
        }
        when (state) {
            Perm.Yes -> Tag("разрешено", c.ok, icon = SmIcons.Check)
            Perm.No -> SmButton("Настройки", ask, small = true, kind = BtnKind.Tonal)
            Perm.Ask -> SmButton("Разрешить", ask, small = true)
        }
    }
}

private const val TOTAL_DOTS = 3
private const val MAX_PENDING_INVITATION_CHARS = 8192
