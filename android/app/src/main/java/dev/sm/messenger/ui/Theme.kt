package dev.sm.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра макета `design/mockups/ui-v14.html` («Классика + папки + статус-полоса», светлая и тёмная; названия
 * токенов — как переменные CSS макета). Тема всегда как в системе (UX-10).
 */
@Immutable
class SmColors(
    val dark: Boolean,
    /** Фон экрана (`--bg`). */
    val bg: Color,
    /** Поверхность списков, карточек, листов (`--sur`). */
    val sur: Color,
    /** Шапка и вкладки (`--hd`, `--hdt`). */
    val hd: Color,
    val hdt: Color,
    /** Текст и второй текст (`--tx`, `--tx2`). */
    val tx: Color,
    val tx2: Color,
    /** Акцент, текст на нём, акцент для текста и ссылок (`--acc`, `--acct`, `--acc2`). */
    val acc: Color,
    val acct: Color,
    val acc2: Color,
    /** Линии и наведение (`--line`, `--hov`). */
    val line: Color,
    val hov: Color,
    /** Нижняя панель и «таблетка» выбранной вкладки (`--nav`, `--pill`, `--pillt`). */
    val nav: Color,
    val pill: Color,
    val pillt: Color,
    /** Обои чата: градиент сверху-слева вниз (`--wall`). */
    val wallA: Color,
    val wallB: Color,
    /** Пузыри и время в них (`--bin`, `--bout`, `--boutt`, `--metac`, `--metaout`). */
    val bin: Color,
    val bout: Color,
    val boutt: Color,
    val metac: Color,
    val metaout: Color,
    /** Системные плашки, например «Сегодня» (`--sysc`, `--sysct`). */
    val sysc: Color,
    val sysct: Color,
    /** Связь: есть, медленно, нет (`--okc`, `--warnc`, `--badc`). */
    val ok: Color,
    val warn: Color,
    val bad: Color,
)

private fun hex(v: Long) = Color(0xFF000000 or v)

val SmLight = SmColors(
    dark = false,
    bg = hex(0xFFFFFF), sur = hex(0xFFFFFF), hd = hex(0xFFFFFF), hdt = hex(0x1B1F24),
    tx = hex(0x1B1F24), tx2 = hex(0x687380),
    acc = hex(0x2479C3), acct = hex(0xFFFFFF), acc2 = hex(0x2479C3),
    line = hex(0xE7EAEE), hov = hex(0xF2F4F6),
    nav = hex(0xFFFFFF), pill = hex(0xE3EFFC), pillt = hex(0x1D5FA8),
    wallA = hex(0xD7E7C4), wallB = hex(0xB9D3B0),
    bin = hex(0xFFFFFF), bout = hex(0xE1F8CF), boutt = hex(0x1B1F24), metac = hex(0x687380), metaout = hex(0x387737),
    sysc = Color(0x2E000000), sysct = hex(0xFFFFFF),
    ok = hex(0x2FA35A), warn = hex(0xD48B00), bad = hex(0xD23B35),
)

val SmDark = SmColors(
    dark = true,
    bg = hex(0x17191D), sur = hex(0x1F2227), hd = hex(0x1F2227), hdt = hex(0xEEF0F3),
    tx = hex(0xEEF0F3), tx2 = hex(0x8D96A1),
    acc = hex(0x5AA6F0), acct = hex(0x0B1A2A), acc2 = hex(0x6FB2F2),
    line = hex(0x2C3036), hov = hex(0x2A2E34),
    nav = hex(0x1F2227), pill = hex(0x24384F), pillt = hex(0xBCD8F6),
    wallA = hex(0x16231B), wallB = hex(0x0D1611),
    bin = hex(0x262A30), bout = hex(0x2B5278), boutt = hex(0xEEF0F3), metac = hex(0x8D96A1), metaout = hex(0x9FC6EC),
    sysc = Color(0x1FFFFFFF), sysct = hex(0xDFE3E8),
    ok = hex(0x4CC27A), warn = hex(0xF0B33C), bad = hex(0xFF6B66),
)

val LocalSmColors = staticCompositionLocalOf { SmLight }

/** Доступ к палитре макета: `Sm.colors.acc`. */
object Sm {
    val colors: SmColors
        @Composable
        @ReadOnlyComposable
        get() = LocalSmColors.current
}

/** Смесь цвета со своим 100 % − [part] поверхности, как `color-mix(in srgb, c part%, sur)` в макете. */
fun Color.mixOver(base: Color, part: Float): Color = copy(alpha = part).compositeOver(base)

private fun scheme(c: SmColors): ColorScheme {
    val base = if (c.dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
    return base.copy(
        primary = c.acc, onPrimary = c.acct,
        primaryContainer = c.pill, onPrimaryContainer = c.pillt,
        secondary = c.acc2, onSecondary = c.acct,
        secondaryContainer = c.pill, onSecondaryContainer = c.pillt,
        tertiary = c.acc2, onTertiary = c.acct,
        background = c.bg, onBackground = c.tx,
        surface = c.sur, onSurface = c.tx,
        surfaceVariant = c.hov, onSurfaceVariant = c.tx2,
        surfaceTint = c.acc,
        inverseSurface = hex(0x2B2F35), inverseOnSurface = hex(0xFFFFFF), inversePrimary = c.acc,
        outline = c.line, outlineVariant = c.line,
        error = c.bad, onError = hex(0xFFFFFF),
        errorContainer = c.bad.mixOver(c.sur, 0.14f), onErrorContainer = c.tx,
        surfaceBright = c.sur, surfaceDim = c.sur,
        surfaceContainerLowest = c.sur, surfaceContainerLow = c.sur, surfaceContainer = c.sur,
        surfaceContainerHigh = c.sur, surfaceContainerHighest = c.hov,
        scrim = Color.Black,
    )
}

/** Размеры текста макета: основной 15, второй 14, мелкий 13/12/11, шапка 20 (fs + 5). */
private val SmTypography = Typography(
    displayLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Medium),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

private val SmShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Тема приложения: палитра, шрифты и скругления макета; светлая или тёмная — как в системе. */
@Composable
fun SmTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) SmDark else SmLight
    CompositionLocalProvider(LocalSmColors provides c) {
        MaterialTheme(colorScheme = scheme(c), typography = SmTypography, shapes = SmShapes, content = content)
    }
}
