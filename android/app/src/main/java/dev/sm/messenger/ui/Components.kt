package dev.sm.messenger.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Общие части интерфейса, повторяющие макет `design/mockups/ui-v14.html`: шапка, кнопки, карточки, листы,
 * полоса связи, нижняя панель. Размеры — те же, что в CSS макета (px = dp).
 */

/** Линия во всю ширину — `border-bottom: 1px solid var(--line)`. */
fun Modifier.bottomLine(color: Color): Modifier = drawBehind {
    val h = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - h / 2), Offset(size.width, size.height - h / 2), h)
}

/** Линия сверху — `border-top`. */
fun Modifier.topLine(color: Color): Modifier = drawBehind {
    val h = 1.dp.toPx()
    drawLine(color, Offset(0f, h / 2), Offset(size.width, h / 2), h)
}

/** Кнопка-значок 48×48 (`.ib`). */
@Composable
fun SmIconButton(icon: ImageVector, description: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Color.Unspecified, enabled: Boolean = true) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = if (tint == Color.Unspecified) Sm.colors.hdt else tint, modifier = Modifier.size(24.dp)) }
}

/**
 * Шапка экрана (`.hdr`): высота от 56, назад слева (или отступ 8 у вкладок), заголовок 20 sp, под ним
 * необязательная строка 12 sp; [avatar] — аватар перед заголовком. Под статус-бар — свой отступ (edge-to-edge).
 */
@Composable
fun SmHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    sub: String? = null,
    avatar: (@Composable () -> Unit)? = null,
    line: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Sm.colors
    Row(
        modifier.fillMaxWidth().background(c.hd).statusBarsPadding().let { if (line) it.bottomLine(c.line) else it }
            .heightIn(min = 56.dp).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) SmIconButton(SmIcons.Back, "Назад", onBack) else if (avatar == null) Box(Modifier.width(8.dp))
        avatar?.invoke()
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, color = c.hdt, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = c.hdt.copy(alpha = 0.8f), fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

private val AvatarColors = listOf(0xFFE17076, 0xFF7BC862, 0xFFE5CA77, 0xFF65AADD, 0xFFA695E7, 0xFFEE7AAE, 0xFF6EC9CB, 0xFFFAA774)

/** Кружок с первой буквой имени (`.av`): цвет — от имени, буква 38 % размера. */
@Composable
fun Avatar(name: String, size: Int = 54) {
    val color = Color(AvatarColors[Math.floorMod(name.hashCode(), AvatarColors.size)])
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.38f).sp)
    }
}

/** Вид кнопки (`.btn`): основная, светлая (`sec`), текстовая (`txt`), опасная. */
enum class BtnKind { Primary, Tonal, Text, Danger }

/** Кнопка макета (`.btn`): высота 44 (малая 36), скругление во всю высоту, 14 sp, жирная. */
@Composable
fun SmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    icon: ImageVector? = null,
    small: Boolean = false,
    enabled: Boolean = true,
) {
    val c = Sm.colors
    val bg = when (kind) {
        BtnKind.Primary -> c.acc
        BtnKind.Tonal -> c.pill
        BtnKind.Text -> Color.Transparent
        BtnKind.Danger -> c.bad
    }
    val fg = when (kind) {
        BtnKind.Primary -> c.acct
        BtnKind.Tonal -> c.pillt
        BtnKind.Text -> c.acc2
        BtnKind.Danger -> Color.White
    }
    val h = if (small) 36.dp else 44.dp
    Row(
        modifier.height(h).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(h / 2)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = if (small) 14.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(if (small) 18.dp else 18.dp))
        Text(text, color = fg, fontSize = if (small) 13.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Метка (`.tag`): 11 sp, цвет [color] с 15 % фона; [icon] — маленький значок слева (14 dp). */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier, big: Boolean = false, icon: ImageVector? = null) {
    Row(
        modifier.clip(RoundedCornerShape(if (big) 10.dp else 6.dp)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = if (big) 10.dp else 6.dp, vertical = if (big) 3.dp else 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, fontSize = if (big) 13.sp else 11.sp, lineHeight = if (big) 17.sp else 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Метка «не сверен» (`.tag.warn`). */
@Composable
fun NotVerifiedTag(modifier: Modifier = Modifier) = Tag("не сверен", Sm.colors.warn, modifier)

/** Серая метка «Скоро» (или «Появится в версии …») — `.tag.soon`. */
@Composable
fun SoonTag(text: String = "Скоро", big: Boolean = false) = Tag(text, Sm.colors.tx2, big = big)

/** Заголовок раздела (`.sech`). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Sm.colors.acc2,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

/** Строка настроек (`.srow`): значок, название 15 sp, подпись 13 sp, справа метка или стрелка. */
@Composable
fun SettingRow(
    icon: ImageVector?,
    title: String,
    sub: String?,
    danger: Boolean = false,
    trailing: @Composable (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    minHeight: Dp = 60.dp,
    chevron: Boolean = true,
    onClick: (() -> Unit)?,
) {
    val c = Sm.colors
    val color = if (danger) c.bad else c.tx
    Row(
        Modifier.fillMaxWidth().background(c.sur).let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .heightIn(min = minHeight).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) leading() else if (icon != null) Icon(icon, contentDescription = null, tint = if (danger) c.bad else c.tx2, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, color = color, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.tx2, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (trailing != null) trailing() else if (chevron && onClick != null) Icon(SmIcons.Chev, contentDescription = null, tint = c.tx2, modifier = Modifier.size(24.dp))
    }
}

/** Строка-заглушка: метка «Скоро», нажатие — лист [SoonSheet] через [open]. */
@Composable
fun SoonRow(icon: ImageVector, title: String, sub: String?, item: Soon, danger: Boolean = false, open: (Soon) -> Unit) {
    SettingRow(icon, title, sub, danger = danger, trailing = { SoonTag() }) { open(item) }
}

/** Карточка (`.card`): поверхность, поля 10/12, скругление 16, внутри отступ 14. */
@Composable
fun SmCard(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clip(RoundedCornerShape(16.dp)).background(Sm.colors.sur).padding(padding),
        content = content,
    )
}

/**
 * Строка внутри карточки (`.tr`): значок или цветная лампа, название 15 sp, подпись 13 sp; между строками линия,
 * у последней ([last]) её нет.
 */
@Composable
fun TrRow(
    title: String,
    sub: String?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    lamp: Color? = null,
    last: Boolean = false,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val c = Sm.colors
    Row(
        modifier.fillMaxWidth().let { if (last) it else it.bottomLine(c.line) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (lamp != null) Box(Modifier.padding(horizontal = 6.dp).size(12.dp).clip(CircleShape).background(lamp))
        if (icon != null) Icon(icon, contentDescription = null, tint = c.tx, modifier = Modifier.size(24.dp))
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(title, color = c.tx, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = c.tx2, fontSize = 13.sp, lineHeight = 18.sp)
        }
        trailing?.invoke()
    }
}

/** Заголовок внутри карточки (`.card h3`): 16 sp, жирный. */
@Composable
fun CardTitle(text: String) {
    Text(text, color = Sm.colors.tx, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 4.dp))
}

/** Карточка из строк `.srow` без своих отступов. */
@Composable
fun SmCardRows(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = SmCard(modifier, padding = 0.dp, content = content)

/** Пояснение на тонированной подложке (`.note`): значок и текст, цвет — [tone]. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, icon: ImageVector = SmIcons.Info, tone: Color = Sm.colors.acc) =
    Note(AnnotatedString(text), modifier, icon, tone)

/** То же с оформленным текстом (жирное начало и т. п.). */
@Composable
fun Note(text: AnnotatedString, modifier: Modifier = Modifier, icon: ImageVector = SmIcons.Info, tone: Color = Sm.colors.acc) {
    val c = Sm.colors
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(tone.mixOver(c.sur, 0.12f)).padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(24.dp))
        Text(text, color = c.tx, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

/** Полоса-подсказка под шапкой (`.banner`): значок цвета [tone], текст 13 sp, справа кнопка. */
@Composable
fun Banner(text: String, tone: Color, icon: ImageVector?, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    val c = Sm.colors
    Row(
        modifier.fillMaxWidth().background(tone.mixOver(c.sur, 0.14f)).bottomLine(c.line).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(24.dp))
        Text(text, color = c.tx, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** Пояснительный серый текст (`.muted`). */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier, center: Boolean = false, inset: Dp = 16.dp, vertical: Dp = if (inset == 0.dp) 4.dp else 8.dp) {
    Text(
        text,
        color = Sm.colors.tx2,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        textAlign = if (center) TextAlign.Center else null,
        modifier = modifier.padding(horizontal = inset, vertical = vertical),
    )
}

/** Крупный значок в скруглённом квадрате (`.obhero`): 92 dp, подложка — акцент 14 %. */
@Composable
fun HeroIcon(icon: ImageVector, modifier: Modifier = Modifier, tone: Color = Sm.colors.acc) {
    Box(
        modifier.size(92.dp).clip(RoundedCornerShape(28.dp)).background(tone.mixOver(Sm.colors.sur, 0.14f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(50.dp)) }
}

/** Скругление листа (`.sheet`), затемнение под ним и ручка 36×4. */
internal val SheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
internal val SheetScrim = Color(0x70000000)

@Composable
internal fun SheetHandle() {
    Box(Modifier.padding(top = 12.dp, bottom = 10.dp).size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Sm.colors.line))
}

/** Лист снизу (`.sheet`): скругление 24 сверху, ручка 36×4, затемнение 44 %. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Sm.colors.sur,
        shape = SheetShape,
        scrimColor = SheetScrim,
        dragHandle = { SheetHandle() },
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp), content = content)
    }
}

/** Лист так, как его видно на экране, но без отдельного окна — для рендера экранов (`tools/ui-shots`). */
@Composable
internal fun SheetOver(under: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        under()
        Box(Modifier.fillMaxSize().background(SheetScrim))
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(SheetShape).background(Sm.colors.sur)) {
            Box(Modifier.align(Alignment.CenterHorizontally)) { SheetHandle() }
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp), content = content)
        }
    }
}

/** Заголовок листа (`.sheet h3`). */
@Composable
fun SheetTitle(text: String) {
    Text(text, color = Sm.colors.tx, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
}

/** Переключатель 44×26 (`.sw`). */
@Composable
fun SmSwitch(checked: Boolean, onChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val c = Sm.colors
    Box(
        modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(13.dp)).background(if (checked) c.acc else c.line)
            .let { if (onChange != null) it.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onChange(!checked) } else it },
    ) {
        Box(
            Modifier.padding(start = if (checked) 21.dp else 3.dp, top = 3.dp).size(20.dp)
                .shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White),
        )
    }
}

/** Внешний вид поля ввода (`.inp`): высота 46, скругление 12, рамка line, фон bg. */
@Composable
fun Modifier.inputBox(): Modifier = this.height(46.dp).clip(RoundedCornerShape(12.dp)).background(Sm.colors.bg).border(1.dp, Sm.colors.line, RoundedCornerShape(12.dp))

/** Таблетка выбранного значения/вкладки с произвольной формой. */
@Composable
fun Pill(shape: Shape, color: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(shape).background(color), contentAlignment = Alignment.Center) { content() }
}

/** Пункт нижней панели. */
data class NavItem(val title: String, val icon: ImageVector, val dot: Color? = null)

/** Нижняя панель (`.nav`): высота 72, линия сверху, у выбранного «таблетка» 64×32; точка у «Связи», если связь не в порядке. */
@Composable
fun SmNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Sm.colors
    Column(Modifier.fillMaxWidth().background(c.nav).topLine(c.line).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(72.dp)) {
            items.forEachIndexed { i, it ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).height(72.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                ) {
                    Box(
                        Modifier.size(width = 64.dp, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) c.pill else Color.Transparent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(it.icon, contentDescription = null, tint = if (on) c.pillt else c.tx2, modifier = Modifier.size(24.dp))
                        if (it.dot != null) Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 16.dp).size(8.dp).clip(CircleShape).background(it.dot))
                    }
                    Text(it.title, color = if (on) c.tx else c.tx2, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Папки под шапкой (`.folders`): выбранная — акцентная с линией 3 dp, остальные бледнее; `dim` — «Скоро». */
@Composable
fun Folders(items: List<String>, selected: Int, dim: Set<Int> = emptySet(), onSelect: (Int) -> Unit) {
    val c = Sm.colors
    Row(Modifier.fillMaxWidth().background(c.hd).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, t ->
            val on = i == selected
            Text(
                t,
                color = if (on) c.acc2 else c.hdt.copy(alpha = if (i in dim) 0.55f else 0.7f),
                fontSize = 14.sp,
                lineHeight = 19.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clickable { onSelect(i) }.drawBehind {
                    if (on) drawRect(c.acc2, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 3.dp.toPx()))
                }.padding(horizontal = 12.dp, vertical = 10.dp).padding(bottom = 3.dp),
            )
        }
    }
}

/** Кнопка «Новое сообщение» (`.fab`): 56 dp, скругление 18, тень. */
@Composable
fun SmFab(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sm.colors
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier.size(56.dp).shadow(6.dp, shape, ambientColor = Color(0x4D000000), spotColor = Color(0x4D000000)).clip(shape).background(c.acc).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = c.acct, modifier = Modifier.size(24.dp)) }
}

/** Пустой отступ-«нижняя панель» внизу прокручиваемых списков. */
@Composable
fun BottomSpace(h: Dp = 24.dp) = Box(Modifier.height(h))

/**
 * IME-отступ один раз на корне приложения.
 *
 * При `enableEdgeToEdge()` окно под клавиатуру не уменьшается, и отступ обязан добавить Compose.
 * На части прошивок `adjustResize` всё же уменьшает окно и одновременно отдаёт непустой
 * `WindowInsets.ime`: второй отступ даёт пустую полосу высотой с клавиатуру. `maximumWindowMetrics`
 * от клавиатуры не зависит, поэтому «высота окна + клавиатура не больше максимума» означает,
 * что окно уже уменьшено и отступ не нужен.
 */
@Composable
fun Modifier.imePaddingNoDouble(): Modifier {
    val density = LocalDensity.current
    val ime = WindowInsets.ime.getBottom(density)
    if (ime <= 0) return this
    val wm = LocalContext.current.getSystemService(WindowManager::class.java)
    val current = wm.currentWindowMetrics.bounds.height()
    val maximum = wm.maximumWindowMetrics.bounds.height()
    // Окно уменьшено, если его высота плюс клавиатура не выходят за максимум.
    return if (current + ime <= maximum) this else Modifier.imePadding()
}

