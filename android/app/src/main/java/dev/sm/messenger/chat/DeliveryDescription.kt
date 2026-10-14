package dev.sm.messenger.chat

internal fun deliveryDescription(line: ChatLine): String = when {
    !line.mine -> "Получено"
    line.state == MessageState.Failed -> "Не отправлено"
    line.state == MessageState.Delivered -> "Доставлено"
    line.onNode -> "На узле, ждёт получателя"
    else -> "На телефоне, ждёт отправки"
}
