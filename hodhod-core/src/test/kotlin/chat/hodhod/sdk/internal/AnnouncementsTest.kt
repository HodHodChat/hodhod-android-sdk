package chat.hodhod.sdk.internal

import chat.hodhod.sdk.Announcement
import chat.hodhod.sdk.AnnouncementBlock
import chat.hodhod.sdk.AnnouncementKind
import chat.hodhod.sdk.FakeHodhodRepository
import chat.hodhod.sdk.TextSegment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementsTest {
    private lateinit var srv: TestServer

    @Before fun setUp() { srv = TestServer() }
    @After fun tearDown() { srv.stop() }

    private fun parse(json: String) = Parsers.announcements(parseJson(json).arr())

    private val contract = """[
      {"id":"a1","kind":"notice","dismissible":true,"updated_at":"2026-10-07T10:00:00Z","blocks":[
        {"type":"text","segments":[{"text":"Maintenance "},{"text":"tonight","bold":true},{"text":" - see ","bold":false},{"text":"status","href":"https://status.example.com/x"}]},
        {"type":"image","url":"https://cdn.example.com/a.png","alt":"Banner","href":"https://example.com/promo"}]},
      {"id":"a2","kind":"alert","dismissible":false,"updated_at":"2026-10-07T11:00:00Z","blocks":[{"type":"text","segments":[{"text":"Outage"}]}]}
    ]"""

    @Test fun parsesContractShape() {
        val l = parse(contract)
        assertEquals(2, l.size)
        val a = l[0]
        assertEquals("a1", a.id); assertEquals(AnnouncementKind.NOTICE, a.kind); assertTrue(a.dismissible)
        assertEquals("2026-10-07T10:00:00Z", a.updatedAt)
        val text = a.blocks[0] as AnnouncementBlock.Text
        assertEquals(
            listOf(TextSegment("Maintenance "), TextSegment("tonight", bold = true), TextSegment(" - see "), TextSegment("status", href = "https://status.example.com/x")),
            text.segments,
        )
        assertEquals(AnnouncementBlock.Image("https://cdn.example.com/a.png", "Banner", "https://example.com/promo"), a.blocks[1])
        assertEquals(AnnouncementKind.ALERT, l[1].kind); assertEquals(false, l[1].dismissible)
    }

    @Test fun missingOrInvalidIsEmpty() {
        assertTrue(Parsers.announcements(null).isEmpty())
        assertTrue(parse("[]").isEmpty())
        assertTrue(parse("""["x",1,null,{"kind":"alert"}]""").isEmpty())
        val noKey = Parsers.widgetConfig(parseJson(Fixtures.config()).obj()!!, null)!!
        assertTrue(noKey.announcements.isEmpty())
        for (bad in listOf(""","announcements":"nope"""", ""","announcements":{"id":1}""", ""","announcements":null""", ""","announcements":5""")) {
            val cfg = Parsers.widgetConfig(parseJson(Fixtures.config(extra = bad)).obj()!!, null)!!
            assertTrue(cfg.announcements.isEmpty(), bad)
        }
    }

    @Test fun readsAnnouncementsFromConfigJson() {
        val cfg = Parsers.widgetConfig(parseJson(Fixtures.config(extra = ""","announcements":$contract""")).obj()!!, null)!!
        assertEquals(listOf("a1", "a2"), cfg.announcements.map { it.id })
    }

    @Test fun unknownBlockTypesAreIgnoredAndEmptyAnnouncementsDropped() {
        val l = parse("""[
          {"id":"a1","kind":"notice","dismissible":true,"updated_at":"u","blocks":[{"type":"video","url":"https://x.test/v.mp4"},{"type":"text","segments":[{"text":"hi"}]},{"type":"button"}]},
          {"id":"a2","kind":"notice","dismissible":true,"updated_at":"u","blocks":[{"type":"video"},{"type":"text","segments":[]},{"type":"text","segments":[{"text":""},{"bold":true}]}]},
          {"id":"a3","kind":"notice","dismissible":true,"updated_at":"u","blocks":"oops"}]""")
        assertEquals(listOf("a1"), l.map { it.id })
        assertEquals(1, l[0].blocks.size)
    }

    @Test fun onlySafeLinkSchemesSurvive() {
        val ok = listOf("http://a.test/x", "https://a.test/x?y=1#z", "HTTPS://A.TEST", "mailto:help@a.test", "tel:+982100000", "  https://a.test  ")
        val bad = listOf("javascript:alert(1)", "JaVaScRiPt:alert(1)", "java\nscript:alert(1)", "data:text/html;base64,AAAA", "intent://x#Intent;scheme=a;end", "file:///etc/passwd",
            "content://x/y", "ftp://a.test/x", "//a.test/x", "https:", "https://", "mailto:", "/relative", "a.test/x", "https://a b.test", "vbscript:x", "market://details?id=a")
        ok.forEach { assertTrue(Parsers.safeHref(it) != null, it) }
        assertEquals("https://a.test", Parsers.safeHref("  https://a.test  "))
        bad.forEach { assertNull(Parsers.safeHref(it), it) }
        val segs = (parse("""[{"id":"a1","kind":"notice","dismissible":true,"updated_at":"u","blocks":[{"type":"text","segments":[
            {"text":"a","href":"javascript:alert(1)"},{"text":"b","href":"mailto:x@y.test"}]}]}]""")[0].blocks[0] as AnnouncementBlock.Text).segments
        assertNull(segs[0].href); assertEquals("mailto:x@y.test", segs[1].href)
    }

    @Test fun imagesNeedHttps() {
        val l = parse("""[{"id":"a1","kind":"notice","dismissible":true,"updated_at":"u","blocks":[
          {"type":"image","url":"http://a.test/a.png"},{"type":"image","url":"data:image/png;base64,AAAA"},{"type":"image","url":"javascript:1"},{"type":"image"},
          {"type":"image","url":"https://a.test/ok.png","href":"javascript:alert(1)"},{"type":"image","url":"https://a.test/ok2.png","alt":"  "}]}]""")
        assertEquals(
            listOf(AnnouncementBlock.Image("https://a.test/ok.png", null, null), AnnouncementBlock.Image("https://a.test/ok2.png", null, null)),
            l.single().blocks,
        )
    }

    @Test fun httpImagesOnlyForDevHostsAndOwnUploads() {
        val json = """[{"id":"a1","kind":"notice","dismissible":true,"updated_at":"u","blocks":[
          {"type":"image","url":"http://localhost:3000/rails/active_storage/blobs/redirect/abc/x.png"},
          {"type":"image","url":"http://10.0.2.2:3000/rails/active_storage/blobs/redirect/abc/y.png"},
          {"type":"image","url":"http://localhost:3000/other/z.png"},
          {"type":"image","url":"http://evil.test/rails/active_storage/blobs/redirect/abc/w.png"}]}]"""
        assertTrue(parse(json).isEmpty()) // production: https only
        val dev = Parsers.announcements(parseJson(json).arr(), setOf("10.0.2.2", "localhost"))
        assertEquals(
            listOf("http://localhost:3000/rails/active_storage/blobs/redirect/abc/x.png", "http://10.0.2.2:3000/rails/active_storage/blobs/redirect/abc/y.png"),
            dev.single().blocks.map { (it as AnnouncementBlock.Image).url },
        )
    }

    @Test fun capsDefaultsAndLenientIds() {
        val one = """{"id":"a%d","kind":"%s","updated_at":"u","blocks":[{"type":"text","segments":[{"text":"t"}]}]}"""
        val l = parse("[" + listOf(one.format(1, "weird"), one.format(2, "alert"), one.format(3, "notice")).joinToString(",") + "]")
        assertEquals(listOf("a1", "a2"), l.map { it.id }) // at most 2
        assertEquals(AnnouncementKind.NOTICE, l[0].kind) // unknown kind -> notice
        assertTrue(l[0].dismissible) // missing -> dismissible
        assertEquals("7", parse("""[{"id":7,"blocks":[{"type":"text","segments":[{"text":"t"}]}]}]""").single().id)
        assertEquals("", parse("""[{"id":"x8","blocks":[{"type":"text","segments":[{"text":"t"}]}]}]""").single().updatedAt)
    }

    // ---- repository: refresh, dismissal persistence, versioned re-show ----

    private fun announcementsJson(updated1: String = "v1", extra: String = "") = ""","announcements":[
      {"id":"a1","kind":"notice","dismissible":true,"updated_at":"$updated1","blocks":[{"type":"text","segments":[{"text":"one"}]}]},
      {"id":"a2","kind":"alert","dismissible":false,"updated_at":"v1","blocks":[{"type":"text","segments":[{"text":"two"}]}]}$extra]"""

    @Test fun refreshPublishesAndDismissPersistsAcrossRepositories() = runBlocking<Unit> {
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config(extra = announcementsJson()))
        val store = InMemorySessionStore()
        val repo = testRepo(srv, store)
        assertTrue(repo.announcements.value.isEmpty())
        repo.refresh()
        assertEquals(listOf("a1", "a2"), repo.announcements.value.map { it.id })
        repo.dismissAnnouncement("a2") // not dismissible -> ignored
        assertEquals(listOf("a1", "a2"), repo.announcements.value.map { it.id })
        repo.dismissAnnouncement("nope") // unknown -> ignored
        repo.dismissAnnouncement("a1")
        assertEquals(listOf("a2"), repo.announcements.value.map { it.id })
        assertEquals(listOf("a1", "a2"), repo.widgetConfig.value!!.announcements.map { it.id }) // raw config keeps both

        // "app restart": a new repository over the same persisted store
        val again = testRepo(srv, store)
        again.refresh()
        assertEquals(listOf("a2"), again.announcements.value.map { it.id })
        assertTrue(store.get(SessionStore.DISMISSED_ANNOUNCEMENTS)!!.contains("WT:a1:v1"))
    }

    @Test fun editingAnAnnouncementShowsItAgain() = runBlocking<Unit> {
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config(extra = announcementsJson("v1")))
        val store = InMemorySessionStore()
        val repo = testRepo(srv, store)
        repo.refresh(); repo.dismissAnnouncement("a1")
        assertEquals(listOf("a2"), repo.announcements.value.map { it.id })
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config(extra = announcementsJson("v2")))
        repo.refresh()
        assertEquals(listOf("a1", "a2"), repo.announcements.value.map { it.id })
        // an announcement that goes out of schedule and comes back unchanged stays dismissed
        repo.dismissAnnouncement("a1")
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config())
        repo.refresh()
        assertTrue(repo.announcements.value.isEmpty())
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config(extra = announcementsJson("v2")))
        repo.refresh()
        assertEquals(listOf("a2"), repo.announcements.value.map { it.id })
    }

    @Test fun dismissedListIsBoundedAndSessionResetForgetsDismissals() = runBlocking<Unit> {
        val many = (10..70).joinToString(",") { """{"id":"m$it","kind":"notice","dismissible":true,"updated_at":"v","blocks":[{"type":"text","segments":[{"text":"x"}]}]}""" }
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config(extra = announcementsJson(extra = ",$many")))
        val store = InMemorySessionStore()
        val repo = testRepo(srv, store)
        repo.refresh()
        // the parser caps the list at 2, so exercise the bound through repeated edits of announcement 1
        repeat(60) { i ->
            srv.onJson("POST", "/api/v1/widget/config", Fixtures.config(extra = announcementsJson("e$i")))
            repo.refresh(); repo.dismissAnnouncement("a1")
        }
        val saved = parseJson(store.get(SessionStore.DISMISSED_ANNOUNCEMENTS)!!).arr()!!
        assertEquals(DefaultHodhodRepository.MAX_DISMISSED_ANNOUNCEMENTS, saved.size)
        store.clear(); repo.resetSession() // logout
        assertEquals(listOf("a1", "a2"), repo.announcements.value.map { it.id })
    }

    @Test fun fakeRepositoryDismissesAndKeepsEditedVersionsVisible() {
        val a = Announcement("a1", AnnouncementKind.NOTICE, true, listOf(AnnouncementBlock.Text(listOf(TextSegment("hi")))), "v1")
        val fake = FakeHodhodRepository(FakeHodhodRepository.sampleConfig().copy(announcements = listOf(a, a.copy(id = "a2", kind = AnnouncementKind.ALERT, dismissible = false))))
        assertEquals(listOf("a1", "a2"), fake.announcements.value.map { it.id })
        fake.dismissAnnouncement("a2"); assertEquals(2, fake.announcements.value.size)
        fake.dismissAnnouncement("a1"); assertEquals(listOf("a2"), fake.announcements.value.map { it.id })
        fake.setAnnouncements(listOf(a, a.copy(id = "a2")))
        assertEquals(listOf("a2"), fake.announcements.value.map { it.id })
        fake.setAnnouncements(listOf(a.copy(updatedAt = "v2")))
        assertEquals(listOf("a1"), fake.announcements.value.map { it.id })
    }
}
