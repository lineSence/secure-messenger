package dev.sm.messenger.ble

/** Монотонное время: старая метка и ошибочные будущие отметки не означают «рядом». */
internal fun freshPeer(seenAt: Long, now: Long, timeout: Long): Boolean =
    seenAt >= 0 && now >= seenAt && timeout > 0 && now - seenAt < timeout
