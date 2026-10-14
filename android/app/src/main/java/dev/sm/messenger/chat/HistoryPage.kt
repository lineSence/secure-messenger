package dev.sm.messenger.chat

internal data class HistoryPage(val lines: List<ChatLine>, val broken: Int, val oldest: Long?, val hasOlder: Boolean)

/** Решение по сохранённому снимку ввода; поздний ответ не стирает новый текст. */
internal fun draftAfterSend(current: String, submitted: String, saved: Boolean): String =
    if (saved && current == submitted) "" else current

internal fun mergeHistory(older: List<ChatLine>, newer: List<ChatLine>): List<ChatLine> =
    (older + newer).distinctBy { it.rowId }.sortedBy { it.rowId }
