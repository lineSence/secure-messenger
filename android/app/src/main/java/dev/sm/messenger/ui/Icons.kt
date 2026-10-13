package dev.sm.messenger.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Значки интерфейса — контуры 24×24 из макета (`design/mockups/ui-v14.html`, `IC`): без
 * библиотеки значков. Цвет задаёт `Icon` (tint), линия — 1,9, концы скруглены, как в макете.
 */
object SmIcons {
    // Объявлены первыми: значки ниже создаются при инициализации объекта.
    private val sources = HashMap<ImageVector, List<String>>()
    private val thinCache = HashMap<ImageVector, ImageVector>()

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

    // Для 0.0.1-alpha (макет `design/mockups/ui-v10.html`): строки и листы «Скоро».
    val Search = icon("search", circle(11f, 11f, 6.5f), "M20 20l-4.3-4.3")
    val Attach = icon("attach", "M16 7l-7.5 7.5a2.1 2.1 0 003 3L19 10a4.2 4.2 0 00-6-6l-8 8a6.3 6.3 0 009 9l6-6")
    val Smile = icon("smile", circle(12f, 12f, 9f), "M8.5 14.5a4.5 4.5 0 007 0M9 9.5v.5M15 9.5v.5")
    val Group = icon(
        "group",
        circle(9f, 8f, 3.5f),
        "M2.5 20c0-3.8 2.9-6 6.5-6s6.5 2.2 6.5 6",
        circle(17f, 9f, 2.8f),
        "M17 14c2.9 0 4.8 1.9 4.8 5",
    )
    val Link = icon("link", "M10 14a4 4 0 005.7 0l3-3a4 4 0 00-5.7-5.7l-1 1", "M14 10a4 4 0 00-5.7 0l-3 3a4 4 0 005.7 5.7l1-1")
    val Wifi = icon("wifi", "M2 9a15 15 0 0120 0M5 12.5a10 10 0 0114 0M8.5 16a5 5 0 017 0M12 19.5v.01")
    val Refresh = icon("refresh", "M20 11a8 8 0 10-2.3 6.3", "M20 4v7h-7")
    val Radio = icon(
        "radio",
        "M12 11v10M8 21h8",
        circle(12f, 9f, 2f),
        "M7.8 4.8a6 6 0 000 8.4M16.2 4.8a6 6 0 010 8.4M5 2a10 10 0 000 14M19 2a10 10 0 010 14",
    )
    val Laptop = icon("laptop", "M5.5 5h13a1.5 1.5 0 011.5 1.5v8a1.5 1.5 0 01-1.5 1.5h-13A1.5 1.5 0 014 14.5v-8A1.5 1.5 0 015.5 5z", "M2 19h20")
    val DebugLog = icon("debugLog", "M14 2H6v20h12V6zM14 2v4h4M9 12h6M9 16h6")
    val Download = icon("download", "M12 4v11M7 10l5 5 5-5M5 20h14")
    val Palette = icon(
        "palette",
        "M12 3a9 9 0 100 18c1.1 0 1.5-.8 1.5-1.6 0-1.2-1-1.4-1-2.4 0-.8.6-1.5 1.5-1.5H17a4 4 0 004-4c0-4.7-4-8.5-9-8.5z",
        circle(7.5f, 11f, 1f),
        circle(10f, 7f, 1f),
        circle(15f, 7.5f, 1f),
    )
    val Lock = icon("lock", "M7 10.5h10a2 2 0 012 2v6a2 2 0 01-2 2H7a2 2 0 01-2-2v-6a2 2 0 012-2z", "M8 10.5V7a4 4 0 018 0v3.5")
    val Bell = icon("bell", "M6 16V11a6 6 0 0112 0v5l2 2H4z", "M10 21h4")
    val Timer = icon("timer", circle(12f, 13f, 8f), "M12 9v4l2.5 2M9 2h6")
    val Exit = icon("exit", "M14 4h5v16h-5M10 8l-4 4 4 4M6 12h10")
    val Info = icon("info", circle(12f, 12f, 9f), "M12 11v5.5M12 7.5v.01")
    val Reply = icon("reply", "M10 7L4 12.5l6 5.5", "M4 12.5h10a6 6 0 016 6V20")
    val Scan = icon("scan", "M4 8V5a1 1 0 011-1h3M16 4h3a1 1 0 011 1v3M20 16v3a1 1 0 01-1 1h-3M8 20H5a1 1 0 01-1-1v-3M4 12h16")
    val UserPlus = icon("userplus", circle(10f, 8f, 4f), "M2 21c0-4.4 3.6-7 8-7 1.8 0 3.4.4 4.7 1.2M19 14v6M16 17h6")
    val Server = icon(
        "server",
        "M5.5 4h13a1.5 1.5 0 011.5 1.5v3a1.5 1.5 0 01-1.5 1.5h-13A1.5 1.5 0 014 8.5v-3A1.5 1.5 0 015.5 4z",
        "M5.5 14h13a1.5 1.5 0 011.5 1.5v3a1.5 1.5 0 01-1.5 1.5h-13A1.5 1.5 0 014 18.5v-3A1.5 1.5 0 015.5 14z",
        "M8 7h.01M8 17h.01",
    )
    val Copy = icon(
        "copy",
        "M10 8h8a2 2 0 012 2v8a2 2 0 01-2 2h-8a2 2 0 01-2-2v-8a2 2 0 012-2z",
        "M16 8V5a1 1 0 00-1-1H5a1 1 0 00-1 1v10a1 1 0 001 1h3",
    )
    val More = icon("more", circle(12f, 5f, 1f), circle(12f, 12f, 1f), circle(12f, 19f, 1f))
    val Phone = icon("phone", "M9.5 2.5h5A2.5 2.5 0 0117 5v14a2.5 2.5 0 01-2.5 2.5h-5A2.5 2.5 0 017 19V5a2.5 2.5 0 012.5-2.5z", "M11 18.5h2")
    val Key = icon("key", circle(8f, 15f, 4f), "M11 12l9-9M16 7l3 3M14 9l2 2")
    val Battery = icon(
        "battery",
        "M5 7h12a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2V9a2 2 0 012-2z",
        "M21 10.5v3M6.5 10v4M10 10v4",
    )
    val Share = icon("share", circle(6f, 12f, 2.5f), circle(18f, 6f, 2.5f), circle(18f, 18f, 2.5f), "M8.3 10.8l7.4-3.6M8.3 13.2l7.4 3.6")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val User = icon("user", circle(12f, 8f, 4f), "M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7")
    val Arrow = icon("arrow", "M5 12h14M13 6l6 6-6 6")

    private fun circle(cx: Float, cy: Float, r: Float): String =
        "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    /** Тот же значок с тонкой линией 1,6 — для крупных значков в плитках (`.obhero svg`). */
    fun thin(v: ImageVector): ImageVector = thinCache.getOrPut(v) { build(v.name, sources.getValue(v), THIN) }

    private fun icon(name: String, vararg paths: String): ImageVector = build(name, paths.toList(), STROKE).also { sources[it] = paths.toList() }

    private fun build(name: String, paths: List<String>, width: Float): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                for (d in paths) {
                    addPath(
                        pathData = addPathNodes(d),
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = width,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private const val STROKE = 1.9f
    private const val THIN = 1.6f
}
