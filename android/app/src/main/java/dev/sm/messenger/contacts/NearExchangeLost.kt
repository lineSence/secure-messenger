package dev.sm.messenger.contacts

/** У получателя нет target: входящий запрос связан с askCard/started, не только с исходящим адресом. */
internal fun nearExchangeLost(address: String, target: String?, ask: String?, accepted: String?, started: Set<String>): Boolean =
    address == target || address == ask || address == accepted || address in started
