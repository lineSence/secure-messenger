package dev.sm.messenger.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Значки интерфейса — контуры 24×24 из макета (`design/mockups/ui-v7.html` и `ui-v8.html`, `IC`): без
 * библиотеки значков. Цвет задаёт `Icon` (tint), линия — 1,8, концы скруглены, как в макете.
 */
object SmIcons {
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Send = icon("send", "M4 12l16-8-5 16-3-6z", "M12 14l8-10")
    /** «Доставлено» — галочка в круге (UX-26, макет v8): не ✓✓, двойная галочка у других — «прочитано». */
    val Delivered = icon("delivered", circle(12f, 12f, 9f), "M8 12.3l2.8 2.8L16.2 9.5")
    val Warn = icon("warn", "M12 4l9 16H3z", "M12 10v4M12 17v.01")
    val Chev = icon("chev", "M9 5l7 7-7 7")
    val Check = icon("check", "M5 12.5l4 4L19 6.5")
    val Clock = icon("clock", circle(12f, 12f, 8.5f), "M12 7.5V12l3 2")
    val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4")
    val Chats = icon("chats", "M4 5h16v11H9l-5 4z")
    val Net = icon("net", "M5 19v-3M10 19v-6M15 19V9M20 19V5")
    val Settings = icon(
        "settings",
        circle(12f, 12f, 3f),
        "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1",
    )
    val Shield = icon("shield", "M12 3l8 3v6c0 4.8-3.4 8-8 9-4.6-1-8-4.2-8-9V6z", "M8.5 12l2.5 2.5 4.5-5")
    val Alert = icon("alert", "M12 3l8 3v6c0 4.8-3.4 8-8 9-4.6-1-8-4.2-8-9V6z", "M12 8v5M12 16.5v.01")
    val Qr = icon(
        "qr",
        "M5 4h4a1 1 0 011 1v4a1 1 0 01-1 1H5a1 1 0 01-1-1V5a1 1 0 011-1z",
        "M15 4h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1V5a1 1 0 011-1z",
        "M5 14h4a1 1 0 011 1v4a1 1 0 01-1 1H5a1 1 0 01-1-1v-4a1 1 0 011-1z",
        "M14 14h2v2h-2zM18 14h2M14 18h2M18 18h2v2",
    )
    val Bt = icon("bt", "M7 7l10 10-5 4V3l5 4L7 17")

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (d in paths) {
                    addPath(
                        pathData = addPathNodes(d),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = STROKE,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private const val STROKE = 1.8f
}
