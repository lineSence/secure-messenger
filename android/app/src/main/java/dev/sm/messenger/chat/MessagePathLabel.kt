package dev.sm.messenger.chat

/** Только подтверждённый путь, не предположение по наличию узла или BLE-метки. */
internal fun messagePathLabel(delivered: Boolean, onNode: Boolean, via: MessagePath?): String? = when {
    delivered && via == MessagePath.Ble -> "Bluetooth"
    onNode || (delivered && via == MessagePath.Node) -> "Интернет"
    else -> null
}
