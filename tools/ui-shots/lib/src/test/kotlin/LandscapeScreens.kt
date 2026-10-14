import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sm.messenger.ui.*
import dev.sm.messenger.contacts.LinkMadeContent
import dev.sm.messenger.chat.DeleteContactContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w640dp-h320dp-land-xhdpi")
class LandscapeScreens {
    @get:Rule val rule = createComposeRule()
    private fun shot(name: String, dark: Boolean, body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { SmTheme(dark) { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            Box(Modifier.fillMaxSize()) { body() }
        } } }
        rule.onRoot().captureRoboImage("${System.getProperty("shots.out")}/${if(dark) "d" else "l"}/landscape-$name.png")
    }
    private fun link(dark: Boolean) {
        shot("link", dark) { LinkMadeContent("DEMO", null, {}, {}, false) }
        rule.onNodeWithText("Копировать").assertIsDisplayed()
        rule.onNodeWithText("Поделиться ссылкой").assertIsDisplayed()
    }
    private fun delete(dark: Boolean) {
        shot("delete", dark) { DeleteContactContent("Очень длинное имя", false, {}, {}) }
        rule.onNodeWithText("Отмена").assertIsDisplayed()
        rule.onNodeWithText("Удалить контакт").assertIsDisplayed()
        rule.onNodeWithText("Останется").performScrollTo().assertIsDisplayed()
    }
    @Test fun linkLight() = link(false)
    @Test fun linkDark() = link(true)
    @Test fun deleteLight() = delete(false)
    @Test fun deleteDark() = delete(true)
}
