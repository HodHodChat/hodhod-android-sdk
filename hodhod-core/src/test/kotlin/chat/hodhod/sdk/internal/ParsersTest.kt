package chat.hodhod.sdk.internal

import chat.hodhod.sdk.ContactMode
import chat.hodhod.sdk.MessageStatus
import chat.hodhod.sdk.MessageType
import chat.hodhod.sdk.TicketStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsersTest {
    @Test fun parsesConfigWithHtmlExtras() {
        val root = parseJson(Fixtures.config("both")).obj()!!
        val extras = Parsers.extrasFromHtml(Fixtures.widgetHtml())
        val cfg = Parsers.widgetConfig(root, extras)!!
        assertEquals(ContactMode.BOTH, cfg.contactMode)
        assertEquals("#7A4FD1", cfg.widgetColor)
        assertEquals(listOf("2026-11-13"), cfg.holidays)
        assertEquals(1, cfg.ticketCategories.size)
        assertEquals("Billing", cfg.ticketCategories[0].name)
        assertFalse(cfg.lockToSingleConversation)
        assertTrue(cfg.hasFlowBot)
        assertTrue(cfg.enabledFeatures.attachments && cfg.enabledFeatures.endConversation && cfg.enabledFeatures.emojiPicker)
        assertEquals("Ticket", cfg.ticketForm.title?.resolve("en"))
        assertEquals("تیکت", cfg.ticketForm.title?.resolve("fa-IR"))
        assertEquals(3, cfg.preChatForm.fields.size)
        assertEquals("conversation_attribute", cfg.preChatForm.fields[2].fieldType)
        assertTrue(cfg.preChatForm.enabled)
        assertEquals("Asia/Tehran", cfg.timezone)
        assertEquals(listOf("en", "fa"), cfg.enabledLanguages.map { it.code })
    }

    @Test fun configWithoutExtrasUsesSafeDefaults() {
        val cfg = Parsers.widgetConfig(parseJson(Fixtures.config()).obj()!!, null)!!
        assertFalse(cfg.lockToSingleConversation)
        assertTrue(cfg.holidays.isEmpty())
        assertTrue(cfg.ticketCategories.isEmpty())
    }

    @Test fun jsonExtrasWinOverHtml() {
        val root = parseJson(Fixtures.config(extra = ""","lock_to_single_conversation":true,"has_flow_bot":false,"lark_holidays":["2027-01-01"],"ticket_categories":[]""")).obj()!!
        assertTrue(Parsers.hasAllExtras(root))
        val cfg = Parsers.widgetConfig(root, Parsers.extrasFromHtml(Fixtures.widgetHtml()))!!
        assertTrue(cfg.lockToSingleConversation)
        assertEquals(listOf("2027-01-01"), cfg.holidays)
        assertTrue(cfg.ticketCategories.isEmpty())
    }

    @Test fun htmlWithoutChannelObject() {
        val e = Parsers.extrasFromHtml("<html></html>")
        assertNull(e.holidays); assertNull(e.lockToSingleConversation)
    }

    @Test fun parsesRestAndWebsocketMessages() {
        val rest = Parsers.message(parseJson(Fixtures.message(11, "hi", type = 1)).obj()!!)
        assertEquals("11", rest.id); assertEquals(11L, rest.serverId); assertEquals(MessageType.OUTGOING, rest.messageType)
        assertEquals("Sara", rest.sender?.name); assertEquals(MessageStatus.SENT, rest.status); assertNull(rest.contentType)

        val ws = parseJson("""{"id":12,"content":"yo","message_type":0,"content_type":"input_csat","conversation_id":5,"created_at":50,"echo_id":"abc","sender_type":"Contact","sender_id":7,
            "content_attributes":{"in_reply_to":3,"items":[{"a":1}]},"attachments":[{"id":1,"file_type":"image","data_url":"http://x/a.png","thumb_url":"","file_size":10,"width":3,"height":4,"extension":"png"}]}""").obj()!!
        val m = Parsers.message(ws)
        assertEquals("abc", m.clientId); assertEquals("abc", m.key); assertEquals("input_csat", m.contentType)
        assertEquals(3L, m.replyToId); assertEquals(MessageType.INCOMING, m.messageType)
        assertEquals("image", m.attachments[0].fileType); assertNull(m.attachments[0].thumbUrl); assertEquals(3, m.attachments[0].width)
        assertEquals("contact", m.sender?.type)
        assertNotNull(m.contentAttributes["items"])
    }

    @Test fun parsesTicket() {
        val t = Parsers.ticket(parseJson("""{"number":42,"subject":"S","status":"waiting_on_customer","category":{"id":2,"name":"B","color":"#fff"},"created_at":1,"updated_at":2,"resolved_at":null,"conversation_id":77}""").obj()!!)
        assertEquals(42, t.number); assertEquals(TicketStatus.WAITING, t.status); assertEquals("B", t.category?.name); assertNull(t.resolvedAt); assertEquals(77, t.conversationId)
        assertEquals(TicketStatus.UNKNOWN, TicketStatus.fromWire("zzz"))
    }

    @Test fun contactInfoMerging() {
        val first = Parsers.contactInfo(parseJson("""{"id":1,"has_email":true,"has_name":false,"has_phone_number":false,"identifier":"u1","pre_chat_identified":true}""").obj()!!)
        assertTrue(first.hasEmail); assertTrue(first.preChatSatisfied); assertEquals("u1", first.identifier)
        val merged = Parsers.contactInfo(parseJson("""{"id":1,"name":"Ali"}""").obj()!!, first)
        assertTrue(merged.hasName); assertTrue(merged.hasEmail); assertEquals("u1", merged.identifier)
    }

    @Test fun backoffIsJitteredAndCapped() {
        val b = Backoff(100, 1_000, kotlin.random.Random(1))
        val waits = (1..12).map { b.next() }
        assertTrue(waits.all { it in 50..1_000 }, "$waits")
        assertTrue(waits.last() >= 500, "$waits")
        b.reset()
        assertTrue(b.next() <= 100)
    }

    @Test fun errorMapping() {
        assertEquals("chat_disabled", WidgetApi.errorFor(403, """{"error":"chat_disabled","code":"chat_disabled"}""").code)
        assertEquals("rate_limited", WidgetApi.errorFor(429, "").code)
        assertEquals("payment_required", WidgetApi.errorFor(402, "").code)
        assertEquals("spam", WidgetApi.errorFor(422, """{"error":"spam","code":"spam"}""").code)
        assertEquals("server", WidgetApi.errorFor(500, "boom").code)
        assertEquals("forbidden", WidgetApi.errorFor(403, """{"error":"Not allowed to do that"}""").code)
        assertEquals("suspended", WidgetApi.errorFor(401, """{"error":"Account is suspended"}""").code)
        assertEquals("unauthorized", WidgetApi.errorFor(401, """{"error":"Invalid token"}""").code)
    }
}
