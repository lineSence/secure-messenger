import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sm.messenger.chat.ChatSummary
import dev.sm.messenger.chat.EmptyChats
import dev.sm.messenger.chat.ChatRow
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.ui.WhoSeesScreen
import dev.sm.messenger.ProfileScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Экраны для сверки с макетом (правило [UI-RENDER-CHECK]). Размер — как телефон в макете: 360x760 dp.
 * Новый экран — новый @Test; экраны с хабами (ChatHub, BleHub, RelayHub) сначала нужно отделить от них.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h760dp-xhdpi")
class Screens {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { Surface(Modifier.fillMaxSize()) { content() } }
        }
        rule.onRoot().captureRoboImage("${System.getProperty("shots.out")}/$name.png")
    }

    private val mama = Contact(1, "aa", "Мама", ByteArray(0), ByteArray(0), true, 0)

    @Test fun whoSees() = shot("who-sees") { WhoSeesScreen(onBack = {}) }
    @Test fun emptyChats() = shot("empty-chats") { EmptyChats(openAdd = {}) }
    @Test fun profile() = shot("profile") { ProfileScreen("Аня", "ab12 cd34 ef56 7890", onNode = {}, onBack = {}) }
    @Test fun chatRow() = shot("chat-row") { ChatRow(ChatSummary(mama, null, 2)) {} }
}
