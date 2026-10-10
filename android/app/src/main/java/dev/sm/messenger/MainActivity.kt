package dev.sm.messenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import uniffi.sm_ffi.coreSelfCheck
import uniffi.sm_ffi.coreVersion

/**
 * Каркас клиента (шаг 3): проверяет, что ядро на Rust загружается и отвечает.
 * Экраны по макету v5 (`design/mockups/ui-v5.html`) — следующими PR.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = coreVersion()
        val selfCheck = coreSelfCheck()
        setContent {
            // Тема — как в системе, без своего переключателя (UX-10).
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CoreStatus(version, selfCheck)
                }
            }
        }
    }
}

@Composable
private fun CoreStatus(version: String, selfCheck: Boolean) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Ядро: $version", style = MaterialTheme.typography.titleMedium)
        Text(if (selfCheck) "Самопроверка ядра: пройдена" else "Самопроверка ядра: ОШИБКА")
    }
}
