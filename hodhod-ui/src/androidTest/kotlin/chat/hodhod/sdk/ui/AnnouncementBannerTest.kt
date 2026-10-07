package chat.hodhod.sdk.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import chat.hodhod.sdk.Announcement
import chat.hodhod.sdk.AnnouncementBlock
import chat.hodhod.sdk.AnnouncementKind
import chat.hodhod.sdk.ContactMode
import chat.hodhod.sdk.DarkMode
import chat.hodhod.sdk.FakeHodhodRepository
import chat.hodhod.sdk.HodhodConfig
import chat.hodhod.sdk.TextSegment
import chat.hodhod.sdk.ui.components.AnnouncementBanner
import chat.hodhod.sdk.ui.components.LocalAnnouncementLinkOpener
import chat.hodhod.sdk.ui.theme.HodhodTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.graphics.Color

/** Announcement banners: rendering, links, dismissal and placement on Home (English locale, light theme, in-memory repository). */
class AnnouncementBannerTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val cfg = HodhodConfig("http://localhost", "token", locale = "en", darkMode = DarkMode.LIGHT)

    private fun text(vararg s: TextSegment) = AnnouncementBlock.Text(s.toList())
    private fun notice(id: String = "n1", dismissible: Boolean = true, kind: AnnouncementKind = AnnouncementKind.NOTICE, vararg blocks: AnnouncementBlock) =
        Announcement(id, kind, dismissible, blocks.toList(), "v1")

    @Test fun rendersTextWithBoldAndLinkAndOpensLinkThroughOpener() {
        val opened = mutableListOf<String>()
        val a = notice(blocks = arrayOf(text(TextSegment("Maintenance "), TextSegment("tonight", bold = true), TextSegment(" - "), TextSegment("status page", href = "https://status.example.com"))))
        rule.setContent {
            HodhodTheme(Color(0xFF7A4FD1), false) {
                CompositionLocalProvider(LocalAnnouncementLinkOpener provides { opened += it }) { AnnouncementBanner(a, onDismiss = {}) }
            }
        }
        rule.onNodeWithText("Maintenance tonight - status page", substring = true).assertIsDisplayed()
        val node = rule.onNodeWithText("Maintenance tonight - status page", substring = true).fetchSemanticsNode()
        val text: AnnotatedString = node.config[SemanticsProperties.Text].first()
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        assertEquals("https://status.example.com", link.url)
        assertTrue(text.spanStyles.any { it.item.fontWeight?.weight == 700 })
        // the kind icon is the banner heading for TalkBack
        rule.onNode(hasContentDescription("Notice") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertExists()
        // tap the link span for real: find where its last characters are laid out and click there
        val layout = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
        assertEquals(chat.hodhod.sdk.ui.theme.VazirmatnFamily, layout.single().layoutInput.style.fontFamily)
        val box = layout.single().getBoundingBox(text.length - 3)
        rule.onNodeWithText("Maintenance tonight - status page", substring = true).performTouchInput { click(box.center) }
        rule.runOnIdle { assertEquals(listOf("https://status.example.com"), opened) }
    }

    @Test fun dismissButtonHasLabelAndCallsBack() {
        var dismissed = 0
        rule.setContent { HodhodTheme(Color(0xFF7A4FD1), false) { AnnouncementBanner(notice(blocks = arrayOf(text(TextSegment("Hi")))), onDismiss = { dismissed++ }) } }
        rule.onNodeWithContentDescription("Dismiss").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun nonDismissibleHasNoCloseButtonAndAlertHasWarningHeading() {
        rule.setContent {
            HodhodTheme(Color(0xFF7A4FD1), false) {
                AnnouncementBanner(notice(dismissible = false, kind = AnnouncementKind.ALERT, blocks = arrayOf(text(TextSegment("Outage")))), onDismiss = {})
            }
        }
        rule.onNodeWithText("Outage").assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss").assertDoesNotExist()
        rule.onNodeWithContentDescription("Warning").assertExists()
    }

    @Test fun imageOnlyAnnouncementDisappearsWhenTheImageCannotLoad() {
        val a = notice(blocks = arrayOf(AnnouncementBlock.Image("https://invalid.invalid/none.png", "Promo", null)))
        rule.setContent { HodhodTheme(Color(0xFF7A4FD1), false) { AnnouncementBanner(a, onDismiss = {}) } }
        rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag("hodhod-announcement-n1")).fetchSemanticsNodes().isEmpty() }
    }

    @Test fun failedImageIsHiddenButTextStays() {
        val a = notice(blocks = arrayOf(text(TextSegment("Still here")), AnnouncementBlock.Image("https://invalid.invalid/none.png", "Promo", null)))
        rule.setContent { HodhodTheme(Color(0xFF7A4FD1), false) { AnnouncementBanner(a, onDismiss = {}) } }
        rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("Promo").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Still here").assertIsDisplayed()
    }

    @Test fun homeShowsAnnouncementsFirstThenNoticesAndDismissHidesIt() {
        val a1 = notice("a1", blocks = arrayOf(text(TextSegment("First announcement"))))
        val a2 = notice("a2", dismissible = false, kind = AnnouncementKind.ALERT, blocks = arrayOf(text(TextSegment("Second announcement"))))
        val repo = FakeHodhodRepository(FakeHodhodRepository.sampleConfig(ContactMode.CHAT).copy(announcements = listOf(a1, a2)))
        repo.setIssueNotices(listOf(chat.hodhod.sdk.IssueNotice(5, "Payments degraded")))
        rule.setContent { HodhodChatContent(repo, cfg, onClose = {}) }
        rule.onNodeWithText("First announcement").assertIsDisplayed()
        val y = { t: String -> rule.onNodeWithText(t).fetchSemanticsNode().boundsInRoot.top }
        val first = y("First announcement"); val second = y("Second announcement"); val notice = y("Payments degraded"); val start = y("Start Conversation")
        assertTrue("$first < $second", first < second)
        assertTrue("$second < $notice", second < notice)
        assertTrue("$notice < $start", notice < start)
        rule.onNodeWithContentDescription("Dismiss", useUnmergedTree = true).performClick() // only a1 is dismissible
        rule.waitUntil(5_000) { rule.onAllNodesWithText("First announcement").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("Second announcement").assertIsDisplayed()
    }
}
