package dev.sm.messenger.ui

/** Presentation only: deterministic screenshot fixtures. Never used for signatures/expiry/storage. */
internal object UiClock {
    var millis: () -> Long = System::currentTimeMillis
}
