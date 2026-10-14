import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sm.messenger.ui.*
import dev.sm.messenger.contacts.NewLinkContent
import dev.sm.messenger.contacts.LinkMadeContent
import dev.sm.messenger.chat.DeleteContactContent
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h500dp-xhdpi")
class AdaptiveScreens {
    @get:Rule val rule = createComposeRule()
    private fun shot(name: String, dark: Boolean, body: @androidx.compose.runtime.Composable () -> Unit) {
        rule.setContent { SmTheme(dark) { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
            Box(Modifier.fillMaxSize()) { body() }
        } } }
        rule.onRoot().captureRoboImage("${System.getProperty("shots.out")}/${if(dark) "d" else "l"}/adaptive-$name.png")
    }
    private fun link(dark: Boolean) {
        shot("link", dark) { LinkMadeContent("DEMO", null, {}, {}, false) }
        rule.onNodeWithText("Поделиться ссылкой").assertIsDisplayed()
        rule.onNodeWithText("Копировать").assertIsDisplayed()
    }
    private fun delete(dark: Boolean) {
        shot("delete", dark) { DeleteContactContent("Имя контакта с очень длинной подписью", false, {}, {}) }
        rule.onNodeWithText("Удалить контакт").assertIsDisplayed()
        rule.onNodeWithText("Отмена").assertIsDisplayed()
        rule.onNodeWithText("Останется").performScrollTo().assertIsDisplayed()
        rule.onRoot().captureRoboImage("${System.getProperty("shots.out")}/${if(dark) "d" else "l"}/adaptive-delete-scrolled.png")
    }
    @Test fun linkLight() = link(false)
    @Test fun linkDark() = link(true)
    @Test fun deleteLight() = delete(false)
    @Test fun deleteDark() = delete(true)
    @Test fun newLinkLight() = shot("new-link", false) { NewLinkContent(false, {}, {}, {}) }
    @Test fun newLinkDark() = shot("new-link", true) { NewLinkContent(true, {}, {}, {}) }
    @Test fun singleSwitchAction() {
        var changes = 0
        val checked = androidx.compose.runtime.mutableStateOf(false)
        rule.setContent { SmTheme(false) { NewLinkContent(checked.value, { checked.value = it; changes++ }, {}, {}) } }
        val switches = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        switches.assertCountEquals(1)
        switches[0].performClick()
        assertEquals(1, changes)
        switches[0].assertIsOn()
    }
    @Test fun selectedTabHasSemanticRole() {
        val selected = androidx.compose.runtime.mutableStateOf(0)
        rule.setContent { SmTheme(false) { SmNavBar(listOf(NavItem("Чаты", SmIcons.Chats), NavItem("Связь", SmIcons.Net)), selected.value, { selected.value = it }) } }
        rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(2)
        rule.onNodeWithText("Чаты").assertIsSelected()
        rule.onNodeWithText("Связь").assertIsNotSelected()
        rule.onNodeWithText("Связь").performClick()
        rule.onNodeWithText("Связь").assertIsSelected()
        rule.onNodeWithText("Чаты").assertIsNotSelected()
    }
    @Test fun retainedInvitationIsMemoryOnly() {
        val store = androidx.lifecycle.ViewModelStore()
        val first = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[dev.sm.messenger.onboarding.InvitationMemory::class.java]
        first.invitation = "DEMO"; first.code = "synthetic code"
        val rotated = androidx.lifecycle.ViewModelProvider(store, androidx.lifecycle.ViewModelProvider.NewInstanceFactory())[dev.sm.messenger.onboarding.InvitationMemory::class.java]
        assertSame(first, rotated)
        assertEquals("DEMO", rotated.invitation)
        store.clear()
        val restarted = dev.sm.messenger.onboarding.InvitationMemory()
        assertEquals("", restarted.invitation)
        assertEquals("", first.code)
    }
    @Test fun navigationLight() = shot("nav", false) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { SmNavBar(listOf(NavItem("Чаты", SmIcons.Chats), NavItem("Связь", SmIcons.Net), NavItem("Настройки", SmIcons.Settings)), 0, {}) } }
    @Test fun navigationDark() = shot("nav", true) { Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { SmNavBar(listOf(NavItem("Чаты", SmIcons.Chats), NavItem("Связь", SmIcons.Net), NavItem("Настройки", SmIcons.Settings)), 0, {}) } }
    @Test fun brokenHistoryIsNotAnEmptyChat() {
        rule.setContent { SmTheme(false) {
            dev.sm.messenger.chat.ChatContent(
                contact = dev.sm.messenger.contacts.Contact(1, "demo", "Аня", ByteArray(0), ByteArray(0), true, 0),
                sub = "ключ сверен", status = dev.sm.messenger.chat.NetStatus(dev.sm.messenger.chat.Tone.Ok, "Связь есть", ""),
                hint = null, hintNode = false, lines = emptyList(), broken = 1, draft = "", onDraft = {},
                listState = androidx.compose.foundation.lazy.rememberLazyListState(), onBack = {}, onVerify = {}, onInfo = {}, openNet = {}, onSend = {}, onSoon = {}, onMessageInfo = {},
            )
        } }
        rule.onNodeWithText("Сообщений пока нет", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Повторить загрузку").assertIsDisplayed()
    }
    @Test fun paletteContrast() {
        fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
            fun luminance(c: androidx.compose.ui.graphics.Color): Double {
                fun component(x: Float) = if (x <= 0.04045f) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
                return 0.2126 * component(c.red) + 0.7152 * component(c.green) + 0.0722 * component(c.blue)
            }
            val x = luminance(a); val y = luminance(b)
            return (maxOf(x,y)+0.05)/(minOf(x,y)+0.05)
        }
        for (c in listOf(SmLight, SmDark)) {
            for ((fg,bg) in listOf(c.tx2 to c.sur, c.acct to c.acc, c.metac to c.bin, c.metaout to c.bout, (if (c.dark) c.acct else androidx.compose.ui.graphics.Color.White) to c.bad)) {
                assertTrue("contrast ${contrast(fg,bg)}", contrast(fg,bg) >= 4.5)
            }
        }
    }
}
