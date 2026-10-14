package dev.sm.messenger.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Rotation: живёт с Activity. Process death: намеренно пустой ввод; сериализации секретов нет. */
class InvitationMemory : ViewModel() {
    var invitation by mutableStateOf("")
    var code by mutableStateOf("")
    override fun onCleared() { invitation = ""; code = "" }
}
