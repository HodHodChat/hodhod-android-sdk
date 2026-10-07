package chat.hodhod.sdk.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import chat.hodhod.sdk.AnnouncementKind
import chat.hodhod.sdk.TextSegment
import chat.hodhod.sdk.ui.components.announcementPalette
import chat.hodhod.sdk.ui.components.announcementText
import chat.hodhod.sdk.ui.theme.contrast
import chat.hodhod.sdk.ui.theme.hodhodColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementUiTest {
    @Test fun boldAndLinkSpansAreBuiltFromSegments() {
        val opened = mutableListOf<String>()
        val t = announcementText(
            listOf(TextSegment("Hello "), TextSegment("bold", bold = true), TextSegment(" and "), TextSegment("link", href = "https://a.test/x"), TextSegment("\nnext line")),
            Color.Red, opened::add,
        )
        assertEquals("Hello bold and link\nnext line", t.text)
        val bold = t.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("bold", t.text.substring(bold.start, bold.end))
        val link = t.getLinkAnnotations(0, t.length).single()
        assertEquals("link", t.text.substring(link.start, link.end))
        val url = link.item as LinkAnnotation.Url
        assertEquals("https://a.test/x", url.url)
        url.linkInteractionListener!!.onClick(url)
        assertEquals(listOf("https://a.test/x"), opened)
    }

    @Test fun unsafeHrefsAndPlainUrlsAreNotLinked() {
        val t = announcementText(
            listOf(TextSegment("a", href = "javascript:alert(1)"), TextSegment("b", href = "intent://x#Intent;end"), TextSegment(" see https://auto.link and +98 21 1234567 ")),
            Color.Red, {},
        )
        assertTrue(t.getLinkAnnotations(0, t.length).isEmpty())
    }

    @Test fun mailtoAndTelLinksAreAllowed() {
        val t = announcementText(listOf(TextSegment("m", href = "mailto:a@b.test"), TextSegment("t", href = "tel:+982100")), Color.Red, {})
        assertEquals(2, t.getLinkAnnotations(0, t.length).size)
        // the phone number is wrapped in a LRI/PDI isolate (the link itself covers only the digits)
        assertEquals("m\u2066t\u2069", t.text)
        val tel = t.getLinkAnnotations(0, t.length).last()
        assertEquals("t", t.text.substring(tel.start, tel.end))
    }

    @Test fun textAndLinkColoursReadInLightAndDark() {
        for (dark in listOf(false, true)) {
            val c = hodhodColors(dark, Color(0xFF7A4FD1))
            for (kind in AnnouncementKind.entries) {
                val p = announcementPalette(c, kind)
                assertTrue("body text on $kind dark=$dark", contrast(c.text, p.background) >= 7.0)
                assertTrue("link/icon on $kind dark=$dark = ${contrast(p.strong, p.background)}", contrast(p.strong, p.background) >= 4.5)
            }
        }
    }
}
