import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.sm.messenger.diagnostics.DebugTools
import dev.sm.messenger.diagnostics.JournalContent
import dev.sm.messenger.diagnostics.JournalView
import dev.sm.messenger.diagnostics.JournalEntry
import dev.sm.messenger.diagnostics.DiagnosticEvent
import dev.sm.messenger.diagnostics.DiagnosticResult
import dev.sm.messenger.diagnostics.JournalSheet
import dev.sm.messenger.diagnostics.JournalSheetContent
import dev.sm.messenger.ProfileContent
import dev.sm.messenger.SettingsContent
import dev.sm.messenger.ble.NetTabContent
import dev.sm.messenger.ble.PeerState
import dev.sm.messenger.ble.PeerUi
import dev.sm.messenger.chat.ChatContent
import dev.sm.messenger.chat.ChatLine
import dev.sm.messenger.chat.ChatSummary
import dev.sm.messenger.chat.ChatsContent
import dev.sm.messenger.chat.ContactInfoContent
import dev.sm.messenger.chat.DeleteContactContent
import dev.sm.messenger.chat.MessageInfoContent
import dev.sm.messenger.chat.MessagePath
import dev.sm.messenger.chat.MessageState
import dev.sm.messenger.chat.NetStatus
import dev.sm.messenger.chat.NewChatSheetContent
import dev.sm.messenger.chat.Tone
import dev.sm.messenger.chat.VerifyContent
import dev.sm.messenger.contacts.AddFrame
import dev.sm.messenger.contacts.AddTab
import dev.sm.messenger.contacts.Contact
import dev.sm.messenger.contacts.LinkActions
import dev.sm.messenger.contacts.LinkContent
import dev.sm.messenger.contacts.LinkMadeContent
import dev.sm.messenger.contacts.NearContent
import dev.sm.messenger.contacts.NearbyPhone
import dev.sm.messenger.contacts.NewLinkContent
import dev.sm.messenger.contacts.MyQr
import dev.sm.messenger.contacts.PairState
import dev.sm.messenger.contacts.ScanBox
import dev.sm.messenger.onboarding.KeysDone
import dev.sm.messenger.onboarding.KeysWait
import dev.sm.messenger.onboarding.NameStep
import dev.sm.messenger.onboarding.ObFrame
import dev.sm.messenger.onboarding.Perm
import dev.sm.messenger.onboarding.PermissionsContent
import dev.sm.messenger.relay.InviteLinks
import dev.sm.messenger.relay.InviteView
import dev.sm.messenger.relay.NodeConnected
import dev.sm.messenger.relay.NodeFrame
import dev.sm.messenger.relay.NodeLink
import dev.sm.messenger.relay.NodeNotConnected
import dev.sm.messenger.relay.NodeNow
import dev.sm.messenger.relay.NodePreview
import dev.sm.messenger.relay.NodeProblem
import dev.sm.messenger.relay.NodeRowView
import dev.sm.messenger.relay.OutLink
import dev.sm.messenger.ui.SheetOver
import dev.sm.messenger.ui.SmTheme
import dev.sm.messenger.ui.Soon
import dev.sm.messenger.ui.SoonContent
import dev.sm.messenger.ui.WhoSeesContent
import java.util.Calendar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.sm_ffi.NodeDomainInfo
import uniffi.sm_ffi.NodeInfo

/**
 * Экраны для сверки с макетом (правило [UI-RENDER-CHECK]). Размер — как экран телефона в макете (368×754 dp),
 * тема — светлая (out/l) и тёмная (out/d). Новый экран — новый @Test в [ScreensBase]; экраны с хабами
 * (ChatHub, BleHub, RelayHub) сначала отделяются от них: рисуется stateless-часть («…Content»).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w368dp-h754dp-xhdpi")
abstract class ScreensBase(private val dark: Boolean) {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { SmTheme(dark) { Surface(Modifier.fillMaxSize(), color = dev.sm.messenger.ui.Sm.colors.bg) { content() } } }
        rule.onRoot().captureRoboImage("${System.getProperty("shots.out")}/${if (dark) "d" else "l"}/$name.png")
    }

    private fun at(h: Int, m: Int, daysAgo: Int = 0): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -daysAgo)
        set(Calendar.HOUR_OF_DAY, h)
        set(Calendar.MINUTE, m)
        set(Calendar.SECOND, 0)
    }.timeInMillis

    private fun lastSunday(): Int {
        val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - Calendar.SUNDAY
    }

    private val none = ByteArray(0)
    private val mama = Contact(1, "aa", "Мама", none, none, true, 0)
    private val anya = Contact(2, "bb", "Аня", none, none, false, 0)
    private val oleg = Contact(3, "cc", "Олег", none, none, true, 0)
    private fun line(id: Long, mine: Boolean, text: String, h: Int, m: Int, state: MessageState = MessageState.Delivered, via: MessagePath? = null, onNode: Boolean = false, day: Int = 0) =
        ChatLine(id, mine, text, at(h, m, day), at(h, m, day), if (mine) state else MessageState.Received, onNode, via)

    private val okStatus = NetStatus(Tone.Ok, "Связь есть", "")
    private val chats = listOf(
        ChatSummary(mama, line(1, false, "Доехали, всё хорошо", 12, 40), 2),
        ChatSummary(anya, line(2, false, "Вижу тебя рядом", 11, 2), 0),
        ChatSummary(oleg, line(3, true, "Завтра заеду", 12, 26, via = MessagePath.Node, day = lastSunday()), 0),
    )
    private val anyaLines = listOf(
        line(1, true, "Привет! Проверяю Bluetooth", 12, 23, via = MessagePath.Ble),
        line(2, false, "Вижу тебя рядом", 12, 26),
    )

    @Composable
    private fun chatScreen(c: Contact, lines: List<ChatLine>, status: NetStatus = okStatus, hint: String? = null, hintNode: Boolean = false) =
        ChatContent(
            contact = c,
            sub = if (c.verified) "ключ сверен" else "ключ не сверен",
            status = status,
            hint = hint,
            hintNode = hintNode,
            lines = lines,
            broken = 0,
            draft = "",
            onDraft = {},
            listState = rememberLazyListState(),
            onBack = {},
            onVerify = {},
            onInfo = {},
            openNet = {},
            onSend = {},
            onSoon = {},
            onMessageInfo = {},
        )

    @Composable
    private fun chatsScreen(list: List<ChatSummary>) = ChatsContent(list, false, okStatus, {}, {}, {}, {}, {}, {})

    private val node = NodeInfo(
        0UL,
        4_000_000_000UL,
        443u,
        "/",
        listOf(NodeDomainInfo("a.example", emptyList()), NodeDomainInfo("b.example", emptyList()), NodeDomainInfo("c.example", emptyList())),
        emptyList(),
    )

    @Test fun chats() = shot("chats") { chatsScreen(chats) }
    @Test fun chatsEmpty() = shot("chats-empty") { chatsScreen(emptyList()) }
    @Test fun sheetNew() = shot("sheet-new") { SheetOver({ chatsScreen(chats) }) { NewChatSheetContent() } }
    @Test fun chatAnya() = shot("chat-anya") { chatScreen(anya, anyaLines) }
    @Test fun chatMama() = shot(
        "chat-mama",
    ) {
        chatScreen(
            mama,
            listOf(
                line(1, true, "Выехали в 9", 12, 23, via = MessagePath.Node),
                line(2, false, "Как дорога?", 12, 26),
                line(3, true, "Пробка у моста, стоим", 12, 29, via = MessagePath.Node),
                line(4, false, "Доехали, всё хорошо", 12, 32),
            ),
        )
    }
    @Test fun chatOleg() = shot(
        "chat-oleg",
    ) {
        chatScreen(
            oleg,
            listOf(
                line(1, false, "Созвонимся, когда будет связь", 12, 23, day = lastSunday()),
                line(2, true, "Завтра заеду", 12, 26, via = MessagePath.Node, day = lastSunday()),
            ),
        )
    }
    @Test fun chatOff() = shot(
        "chat-off",
    ) {
        chatScreen(
            mama,
            listOf(line(1, true, "Выехали в 9", 12, 23, state = MessageState.Queued)),
            status = NetStatus(Tone.Bad, "Узел не отвечает", "сообщения ждут на телефоне"),
            hint = "Узел не отвечает — сообщения уйдут сами, когда связь вернётся",
            hintNode = true,
        )
    }
    @Test fun nodeIdle() = shot("node-idle") { NodeFrame({}) { NodeConnected(NodeNow(node, true, false, NodeLink.Idle), {}, {}, {}) } }
    @Test fun minfoQueued() = shot("minfo-queued") {
        val queued = line(1, true, "Выехали в 9", 12, 23, state = MessageState.Queued)
        SheetOver({ chatScreen(mama, listOf(queued)) }) { MessageInfoContent(queued) }
    }

    @Test fun infoAnya() = shot("info-anya") { ContactInfoContent(anya, {}, {}, {}, {}) }
    @Test fun infoMama() = shot("info-mama") { ContactInfoContent(mama, {}, {}, {}, {}) }
    @Test fun verifyAnya() = shot("verify-anya") { VerifyContent(anya, "12345 67890 12345 67890 12345 67890 12345 67890 12345 67890 12345 67890", {}, {}, {}) }
    @Test fun minfo() = shot("minfo") {
        SheetOver({ chatScreen(anya, anyaLines) }) { MessageInfoContent(anyaLines[0]) }
    }
    @Test fun delc() = shot("delc") {
        SheetOver({ ContactInfoContent(anya, {}, {}, {}, {}) }) { DeleteContactContent("Аня", false, {}, {}) }
    }
    @Test fun delcBusy() = shot("delc-busy") {
        SheetOver({ ContactInfoContent(anya, {}, {}, {}, {}) }) { DeleteContactContent("Аня", true, {}, {}) }
    }
    @Test fun soonGroup() = shot("soon-group") { SheetOver({ chatsScreen(chats) }) { SoonContent(Soon.Group) {} } }
    @Test fun soonUpd() = shot("soon-upd") { SheetOver({ chatsScreen(chats) }) { SoonContent(Soon.Updates) {} } }

    @Test fun addMy() = shot("add-my") { AddFrame(AddTab.My, {}, {}) { MyQr(PairState.Showing("uncensored:qr/AAAA-test-0123456789", System.currentTimeMillis() + 4 * 60_000 + 12_000), {}) } }
    @Test fun addScan() = shot("add-scan") { AddFrame(AddTab.Scan, {}, {}) { ScanBox {} } }
    @Test fun addNear() = shot("add-near") {
        AddFrame(AddTab.Near, {}, {}) { NearContent(PairState.Searching(listOf(NearbyPhone("x", "Аня", true))), {}, {}, {}, {}) }
    }
    @Test fun addNearIdle() = shot("add-near-idle") { AddFrame(AddTab.Near, {}, {}) { NearContent(PairState.Idle, {}, {}, {}, {}) } }

    private val actions = LinkActions({}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    private fun outLink(coded: Boolean) = OutLink(
        ByteArray(InviteLinks.SECRET_LEN),
        System.currentTimeMillis() / 1000 - 100 * 60,
        System.currentTimeMillis() / 1000 + 22 * 3600 + 40 * 60,
        ByteArray(InviteLinks.ID_LEN),
        ByteArray(InviteLinks.ID_LEN),
        coded = coded,
    )
    @Test fun addLink() = shot("add-link") {
        AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = true, outs = listOf(outLink(false))), actions) }
    }
    @Test fun addLinkNoNode() = shot("add-link-nonode") {
        AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = false), actions) }
    }
    @Test fun linkOpt() = shot("linkopt") {
        SheetOver({ AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = true), actions) } }) { NewLinkContent(false, {}, {}, {}) }
    }
    @Test fun linkOptCoded() = shot("linkopt-coded") {
        SheetOver({ AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = true), actions) } }) { NewLinkContent(true, {}, {}, {}) }
    }
    @Test fun linkMade() = shot("linkmade") {
        SheetOver({ AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = true), actions) } }) {
            LinkMadeContent("uncensored:i/7Hq2…xK9p", null, {}, {})
        }
    }
    @Test fun linkMadeCoded() = shot("linkmade-coded") {
        SheetOver({ AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = true), actions) } }) {
            LinkMadeContent("uncensored:i/7Hq2…xK9p", "ветка лампа берег чайка", {}, {})
        }
    }
    @Test fun linkAskCode() = shot("link-code") {
        AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = true, askCode = "x"), actions) }
    }
    @Test fun linkAskCodeWrong() = shot("link-code-wrong") {
        AddFrame(AddTab.Link, {}, {}) { LinkContent(InviteView(loaded = true, nodeReady = true, askCode = "x", codeWrong = true), actions) }
    }

    private val tab = listOf(
        PeerUi("Аня", PeerState.Connected, true) {},
    )
    private fun nodeRow(link: NodeLink = NodeLink.Ok, withTag: Boolean = false, title: String = "Узел"): @Composable () -> Unit =
        { NodeRowView(title, withTag, NodeNow(node, true, true, link)) {} }

    private val soonRows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
        dev.sm.messenger.ui.SectionTitle("Другие пути")
        dev.sm.messenger.ui.SoonRow(dev.sm.messenger.ui.SmIcons.Refresh, "Обходные пути", "при блокировках и белых списках", Soon.Bypass) {}
        dev.sm.messenger.ui.SoonRow(dev.sm.messenger.ui.SmIcons.Radio, "LoRa (MeshCore)", "на километры без интернета", Soon.Lora) {}
        dev.sm.messenger.ui.SoonRow(dev.sm.messenger.ui.SmIcons.Qr, "Файл или QR", "отнести сообщение без связи", Soon.FileQr) {}
        dev.sm.messenger.ui.SoonRow(dev.sm.messenger.ui.SmIcons.Net, "Проверка связи и история", "что работает, что проверено", Soon.Diagnostics) {}
    }

    @Test fun diag() = shot("diag") { NetTabContent(true, false, tab, {}, {}, nodeRow(withTag = true, title = "Интернет · свой узел"), soonRows) }
    @Test fun diagOff() = shot("diag-off") { NetTabContent(false, false, emptyList(), {}, {}, nodeRow(withTag = true, title = "Интернет · свой узел"), soonRows) }
    @Test fun settings() = shot("set") {
        SettingsContent("Аня", "0.0.2-alpha", "0.1.0", "3", 0, "включено, рядом: 1", true, nodeRow(), {}, {}, {}, {}, {}, debugRow = { DebugTools.Row {} })
    }
    @Test fun settingsDebug() {
        shot("set-debug") { SettingsContent("Аня", "0.0.2-alpha", "0.1.0", "3", 0, "включено, рядом: 1", true, nodeRow(), {}, {}, {}, {}, {}, debugRow = { DebugTools.Row {} }) }
        rule.onNodeWithText("Debug-логи").performScrollTo()
        rule.onRoot().captureRoboImage("${System.getProperty("shots.out")}/${if (dark) "d" else "l"}/set-debug.png")
    }
    private fun logView(): JournalView = JournalView(ready = true, enabled = true, entries = listOf(
        JournalEntry(1000, 2000, 1, 1, DiagnosticEvent.InviteOpen, DiagnosticResult.Start, 0),
        JournalEntry(1000, 3000, 1, 2, DiagnosticEvent.RelayGuest, DiagnosticResult.Ok, 0),
        JournalEntry(1000, 4000, 1, 3, DiagnosticEvent.RelayNew, DiagnosticResult.Rejected, 0),
        JournalEntry(1000, 4000, 1, 1, DiagnosticEvent.InviteOpen, DiagnosticResult.Failed, 0),
    ))
    @Composable private fun logScreen(view: JournalView = logView(), notice: String? = null) = JournalContent(view, false, notice, {}, {}, {}, {})
    @Test fun debugLogs() = shot("debug-logs") { logScreen() }
    @Test fun debugLogsEmpty() = shot("debug-logs-empty") { logScreen(JournalView(ready = true)) }
    @Test fun debugLogsError() = shot("debug-logs-error") { logScreen(notice = "Файл не сохранён. Журнал остался на телефоне. Попробуйте ещё раз.") }
    @Test fun debugLogsSave() = shot("debug-logs-save") { SheetOver(under = { logScreen() }) { JournalSheetContent(JournalSheet.Save, {}, {}) } }
    @Test fun debugLogsClear() = shot("debug-logs-clear") { SheetOver(under = { logScreen() }) { JournalSheetContent(JournalSheet.Clear, {}, {}) } }
    @Test fun profile() = shot("mykey") { ProfileContent("Аня", "a3f9 21c7 7e0b 9d44\n05e1 c2a8 66f3 1b90", nodeRow(), {}, {}) }
    @Test fun see() = shot("see") { WhoSeesContent("0.0.2-alpha", {}) }

    @Test fun nodeOk() = shot("node") { NodeFrame({}) { NodeConnected(NodeNow(node, true, true, NodeLink.Ok), {}, {}, {}) } }
    @Test fun nodeNone() = shot("node-none") { NodeFrame({}) { NodeNotConnected(false, false, {}, {}) } }
    @Test fun nodePrev() = shot("node-prev") { NodeFrame({}) { NodePreview(node, {}, {}) } }
    @Test fun nodeBad() = shot("node-bad") { NodeFrame({}) { NodeProblem("Запись не принята", "Подпись не сошлась: запись подделана или повреждена.", {}) } }

    @Test fun ob0() = shot("ob0") {
        ObFrame(null, {}, actions = {
            dev.sm.messenger.ui.SmButton("Начать", {}, Modifier.fillMaxWidth())
            dev.sm.messenger.ui.SmButton("Уже пользуюсь на другом устройстве", {}, Modifier.fillMaxWidth(), kind = dev.sm.messenger.ui.BtnKind.Text)
        }) { dev.sm.messenger.onboarding.Welcome() }
    }
    @Test fun ob1() = shot("ob1") { ObFrame(1, {}, actions = { dev.sm.messenger.ui.SmButton("Далее", {}, Modifier.fillMaxWidth(), enabled = false) }) { NameStep("", true, {}) } }
    @Test fun ob1Name() = shot("ob1-name") { ObFrame(1, {}, actions = { dev.sm.messenger.ui.SmButton("Далее", {}, Modifier.fillMaxWidth()) }) { NameStep("Аня", true, {}) } }
    @Test fun ob2() = shot("ob2") { ObFrame(2, {}, actions = {}) { KeysWait() } }
    @Test fun ob2Done() = shot("ob2-done") {
        ObFrame(2, {}, actions = { dev.sm.messenger.ui.SmButton("Понятно, дальше", {}, Modifier.fillMaxWidth()) }) { KeysDone("a3f9 21c7 7e0b 9d44\n05e1 c2a8 66f3 1b90") }
    }
    @Test fun ob3() = shot("ob3") { PermissionsContent(Perm.Ask, Perm.Ask, {}, {}, {}, {}) }
    @Test fun ob3No() = shot("ob3-no") { PermissionsContent(Perm.Yes, Perm.No, {}, {}, {}, {}) }
}

class ScreensLight : ScreensBase(false)

class ScreensDark : ScreensBase(true)
