package chat.hodhod.sdk.internal

import chat.hodhod.sdk.Attachment
import chat.hodhod.sdk.ConnectionState
import chat.hodhod.sdk.ConversationState
import chat.hodhod.sdk.HodhodState
import chat.hodhod.sdk.HodhodUser
import chat.hodhod.sdk.MessageStatus
import chat.hodhod.sdk.MessageType
import chat.hodhod.sdk.TicketForm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {
    private lateinit var srv: TestServer

    @Before fun setUp() { srv = TestServer() }
    @After fun tearDown() { srv.stop() }

    private fun body(path: String, method: String = "POST") = srv.requestsTo(method, path).last().body.readUtf8()

    @Test fun bootstrapLoadsConfigContactAgentsNoticesAndExtras() = runBlocking<Unit> {
        val repo = testRepo(srv)
        assertEquals(HodhodState.Idle, repo.state.value)
        assertTrue(repo.refresh().isSuccess)
        assertEquals(HodhodState.Ready, repo.state.value)
        val cfg = repo.widgetConfig.value!!
        assertEquals("Hodhod", cfg.websiteName)
        assertEquals(listOf("2026-11-13"), cfg.holidays) // from /widget html
        assertEquals("Billing", cfg.ticketCategories.single().name)
        assertEquals("Sara", repo.agents.value.single().name)
        assertEquals("Payments degraded", repo.issueNotices.value.single().message)
        assertEquals(ConversationState.None, repo.conversation.value)
        assertEquals("fa", repo.uiLocale.value) // account locale (device en-US also supported, server wins like the web widget)
        // auth + token propagation
        val contactReq = srv.requestsTo("GET", "/api/v1/widget/contact").last()
        assertEquals("AUTH1", contactReq.getHeader("X-Auth-Token"))
        assertTrue(contactReq.path!!.contains("website_token=WT"))
        // the /widget html is fetched with the session token so it does not create another contact
        assertTrue(srv.requestsTo("GET", "/widget").last().path!!.contains("cw_conversation=AUTH1"))
        Unit
    }

    @Test fun bootstrapFailureSetsFailedState() = runBlocking<Unit> {
        srv.onJson("POST", "/api/v1/widget/config", """{"error":"Account is suspended"}""", 401)
        val repo = testRepo(srv)
        val r = repo.refresh()
        assertTrue(r.isFailure)
        val s = repo.state.value as HodhodState.Failed
        assertEquals("suspended", s.code)
        // recovers on retry
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config())
        assertTrue(repo.refresh().isSuccess)
        assertEquals(HodhodState.Ready, repo.state.value)
    }

    @Test fun loadsExistingConversationWithUnreadAndHasMore() = runBlocking<Unit> {
        val msgs = (1..20).joinToString(",") { Fixtures.message(it.toLong(), "m$it", type = if (it % 2 == 0) 1 else 0) }
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[$msgs],"meta":{"contact_last_seen_at":1010}}""")
        val repo = testRepo(srv)
        repo.refresh()
        val conv = repo.conversation.value as ConversationState.Active
        assertEquals(5, conv.id); assertTrue(conv.hasMore)
        assertEquals(20, repo.messages.value.size)
        // agent messages (even ids) newer than last seen 1010: ids 12,14,16,18,20 (created 1000+id)
        assertEquals(5, repo.unreadCount.value); assertEquals(5, conv.unreadCount)
        assertTrue(repo.hasActiveConversation.value)
        assertEquals("Sara", conv.assignee?.name)
        repo.markRead()
        assertEquals(0, repo.unreadCount.value)
        assertTrue(body("/api/v1/widget/conversations/update_last_seen").contains("contact_last_seen_at"))
    }

    @Test fun loadOlderUsesBeforeAndStopsWhenEmpty() = runBlocking<Unit> {
        val first = (21..40).joinToString(",") { Fixtures.message(it.toLong(), "m$it") }
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.on("GET", "/api/v1/widget/messages") { req ->
            when {
                req.path!!.contains("before=21") -> srv.json("""{"payload":[${Fixtures.message(5, "old")}],"meta":{}}""")
                else -> srv.json("""{"payload":[$first],"meta":{"contact_last_seen_at":0}}""")
            }
        }
        val repo = testRepo(srv)
        repo.refresh()
        assertTrue(repo.loadOlder().isSuccess)
        assertEquals("old", repo.messages.value.first().content)
        assertEquals(21, repo.messages.value.size)
        assertFalse((repo.conversation.value as ConversationState.Active).hasMore) // short page
        assertTrue(repo.loadOlder().isSuccess) // no-op, no extra request
        assertEquals(1, srv.requests.count { it.path!!.contains("before=21") })
    }

    @Test fun optimisticSendThenConfirmedAndCreatesConversation() = runBlocking<Unit> {
        srv.on("POST", "/api/v1/widget/messages") { req ->
            Thread.sleep(150)
            srv.json("""{"id":101,"content":"hello","inbox_id":1,"conversation_id":5,"message_type":0,"created_at":2000,"private":false,"content_attributes":{}}""")
        }
        srv.onJson("GET", "/api/v1/widget/conversations", "{}")
        val repo = testRepo(srv)
        repo.refresh()
        val deferred = async(kotlinx.coroutines.Dispatchers.Default) { repo.sendMessage("  hello ", clientId = "cid-1") }
        awaitUntil { repo.messages.value.isNotEmpty() }
        val sending = repo.messages.value.single()
        assertEquals(MessageStatus.SENDING, sending.status); assertEquals("cid-1", sending.key); assertEquals(MessageType.INCOMING, sending.messageType)
        val sent = deferred.await().getOrThrow()
        assertEquals(MessageStatus.SENT, sent.status)
        val m = repo.messages.value.single()
        assertEquals("101", m.id); assertEquals("cid-1", m.key); assertEquals(MessageStatus.SENT, m.status)
        val b = body("/api/v1/widget/messages")
        assertTrue(b.contains("\"echo_id\":\"cid-1\"") && b.contains("\"content\":\"hello\""), b)
        assertTrue(repo.conversation.value is ConversationState.Active)
        // conversation attributes fetched after creation
        awaitUntil { srv.requestsTo("GET", "/api/v1/widget/conversations").size >= 2 }
    }

    @Test fun failedSendCanBeRetriedAndDiscarded() = runBlocking<Unit> {
        val n = AtomicInteger()
        srv.on("POST", "/api/v1/widget/messages") {
            if (n.getAndIncrement() == 0) MockResponse().setResponseCode(500) else
                srv.json("""{"id":102,"content":"x","conversation_id":5,"message_type":0,"created_at":2000,"content_attributes":{}}""")
        }
        val repo = testRepo(srv)
        repo.refresh()
        val r = repo.sendMessage("x", clientId = "c2")
        assertTrue(r.isFailure)
        assertEquals("server", (r.exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        assertEquals(MessageStatus.FAILED, repo.messages.value.single().status)
        val ok = repo.retry("c2")
        assertTrue(ok.isSuccess)
        assertEquals(1, repo.messages.value.size)
        assertEquals(MessageStatus.SENT, repo.messages.value.single().status)
        // discard
        srv.on("POST", "/api/v1/widget/messages") { MockResponse().setResponseCode(500) }
        repo.sendMessage("y", clientId = "c3")
        assertEquals(MessageStatus.FAILED, repo.messages.value.last().status)
        repo.discardFailed("c3")
        assertEquals(1, repo.messages.value.size)
    }

    @Test fun blankMessageRejectedWithoutRequest() = runBlocking<Unit> {
        val repo = testRepo(srv)
        val r = repo.sendMessage("   ")
        assertEquals("invalid_param", (r.exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        assertTrue(srv.requestsTo("POST", "/api/v1/widget/messages").isEmpty())
    }

    @Test fun resolvedConversationRejectingMessagesMapsToConversationResolved() = runBlocking<Unit> {
        srv.on("POST", "/api/v1/widget/messages") { srv.json("""{"error":"این گفت‌وگو بسته شده"}""", 403) }
        val repo = testRepo(srv)
        val r = repo.sendMessage("x")
        assertEquals("conversation_resolved", (r.exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
    }

    @Test fun attachmentSentAsMultipartWithOptimisticPreview() = runBlocking<Unit> {
        var n = 102
        srv.on("POST", "/api/v1/widget/messages") {
            srv.json("""{"id":${++n},"conversation_id":5,"message_type":0,"created_at":2000,"content_attributes":{},"attachments":[{"id":1,"file_type":"image","data_url":"http://x/i.png","thumb_url":"http://x/t.png"}]}""")
        }
        val f = File.createTempFile("pic", ".png").apply { writeBytes(ByteArray(10) { 1 }); deleteOnExit() }
        val repo = testRepo(srv)
        val r = repo.sendMessage("with pic", listOf(Attachment(f, "pic.png", "image/png")), "c-text")
        assertTrue(r.isSuccess)
        val reqs = srv.requestsTo("POST", "/api/v1/widget/messages")
        assertEquals(2, reqs.size) // text, then one request per attachment (like the web widget)
        assertTrue(reqs[0].getHeader("Content-Type")!!.startsWith("application/json"))
        val mp = reqs[1].body.readUtf8()
        assertTrue(reqs[1].getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(mp.contains("name=\"message[attachments][]\"; filename=\"pic.png\""), mp)
        assertTrue(mp.contains("name=\"message[echo_id]\""))
        assertEquals(2, repo.messages.value.size)
        assertEquals("image", repo.messages.value.last().attachments.single().fileType)
    }

    @Test fun endConversationThenResetSemantics() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi")}],"meta":{}}""")
        srv.on("GET", "/api/v1/widget/conversations/toggle_status") { MockResponse().setResponseCode(200) }
        val repo = testRepo(srv, cable = true)
        repo.refresh()
        awaitUntil(message = "cable connected") { repo.connection.value == ConnectionState.CONNECTED }
        assertTrue(repo.endConversation().isSuccess)
        val ended = repo.conversation.value as ConversationState.Ended
        assertEquals(5, ended.id); assertTrue(ended.csatEnabled); assertFalse(ended.csatSubmitted)
        assertFalse(repo.hasActiveConversation.value)
        assertEquals(1, repo.messages.value.size) // thread still visible on the end screen
        repo.resetConversation()
        assertEquals(ConversationState.None, repo.conversation.value)
        assertTrue(repo.messages.value.isEmpty())
        assertFalse(repo.hasActiveConversation.value)
        // late event of the ended conversation is ignored
        srv.broadcast("message.created", Fixtures.message(2, "late csat", conv = 5).replace("\"content_type\":\"text\"", "\"content_type\":\"input_csat\""))
        Thread.sleep(300)
        assertTrue(repo.messages.value.isEmpty())
        assertEquals(ConversationState.None, repo.conversation.value)
        // agent reopens ended conversation => becomes current again
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5, "open"))
        srv.broadcast("conversation.status_changed", """{"id":5,"status":"open"}""")
        awaitUntil(message = "reopened") { repo.conversation.value is ConversationState.Active }
        repo.shutdown()
    }

    @Test fun endConversationTolerates404AndForbiddenFails() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi")}],"meta":{}}""")
        srv.on("GET", "/api/v1/widget/conversations/toggle_status") { MockResponse().setResponseCode(404) }
        val repo = testRepo(srv)
        repo.refresh()
        assertTrue(repo.endConversation().isSuccess)
        assertTrue(repo.conversation.value is ConversationState.Ended)
        val repo2 = testRepo(srv)
        srv.on("GET", "/api/v1/widget/conversations/toggle_status") { MockResponse().setResponseCode(403) }
        repo2.refresh()
        assertEquals("forbidden", (repo2.endConversation().exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        assertTrue(repo2.conversation.value is ConversationState.Active)
    }

    @Test fun lockToSingleConversationKeepsResolvedConversationActive() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5, "resolved"))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi")}],"meta":{}}""")
        srv.on("GET", "/widget") { MockResponse().setBody(Fixtures.widgetHtml().replace("lockToSingleConversation: false", "lockToSingleConversation: true")) }
        val repo = testRepo(srv)
        repo.refresh()
        val st = repo.conversation.value
        assertTrue(st is ConversationState.Active && st.status == chat.hodhod.sdk.ConversationStatus.RESOLVED, st.toString())
        assertTrue(repo.hasActiveConversation.value)
        // without lock the same server state is "ended"
        srv.onJson("GET", "/widget", "<html></html>")
        val repo2 = testRepo(srv)
        repo2.refresh()
        assertTrue(repo2.conversation.value is ConversationState.Ended)
    }

    @Test fun resolvedAndLeftConversationIsNotRestoredOnRefresh() = runBlocking<Unit> {
        // server hides resolved conversations of non-locked inboxes => home state after restart
        srv.onJson("GET", "/api/v1/widget/conversations", "{}")
        val repo = testRepo(srv)
        repo.refresh()
        assertEquals(ConversationState.None, repo.conversation.value)
        assertFalse(repo.hasActiveConversation.value)
    }

    @Test fun refreshDoesNotWipeEndScreenBeforeReset() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi")}],"meta":{}}""")
        srv.onJson("GET", "/widget", "<html></html>")
        val repo = testRepo(srv)
        repo.refresh(); repo.endConversation()
        srv.onJson("GET", "/api/v1/widget/conversations", "{}") // server now hides it
        repo.refresh()
        assertTrue(repo.conversation.value is ConversationState.Ended)
        assertEquals(1, repo.messages.value.size)
    }

    @Test fun csatPatchesCsatMessageAndMarksSubmitted() = runBlocking<Unit> {
        val csat = Fixtures.message(9, "", conv = 5).replace("\"content_type\":\"text\"", "\"content_type\":\"input_csat\"")
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi")},$csat],"meta":{}}""")
        srv.onJson("GET", "/api/v1/widget/conversations/toggle_status", "{}")
        srv.onJson("GET", "/widget", "<html></html>")
        srv.on("PATCH", "/api/v1/widget/messages/9") { srv.json("{}") }
        val repo = testRepo(srv)
        repo.refresh(); repo.endConversation()
        assertFalse((repo.conversation.value as ConversationState.Ended).csatSubmitted)
        assertTrue(repo.submitCsat(4, " great ").isSuccess)
        val b = body("/api/v1/widget/messages/9", "PATCH")
        assertTrue(b.contains("\"rating\":4") && b.contains("\"feedback_message\":\"great\"") && b.contains("submitted_values"), b)
        assertTrue((repo.conversation.value as ConversationState.Ended).csatSubmitted)
        assertEquals("invalid_param", (repo.submitCsat(7, null).exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
    }

    @Test fun transcriptCallsEndpointAndMapsErrors() = runBlocking<Unit> {
        srv.on("POST", "/api/v1/widget/conversations/transcript") { MockResponse().setResponseCode(402) }
        val repo = testRepo(srv)
        repo.refresh()
        assertEquals("payment_required", (repo.sendTranscriptByEmail().exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        srv.on("POST", "/api/v1/widget/conversations/transcript") { MockResponse().setResponseCode(200) }
        assertTrue(repo.sendTranscriptByEmail().isSuccess)
        assertEquals("rate_limited", (repo.sendTranscriptByEmail().exceptionOrNull() as chat.hodhod.sdk.HodhodException).code) // local cooldown
    }

    @Test fun ticketLifecycle() = runBlocking<Unit> {
        val ticketJson = """{"number":12,"subject":"Broken","status":"open","category":{"id":4,"name":"Billing","color":"#f00"},"created_at":10,"updated_at":10,"resolved_at":null,"conversation_id":77}"""
        srv.on("POST", "/api/v1/widget/tickets") { srv.json("""{"id":1,"content":"d","message_type":0,"created_at":10,"ticket":$ticketJson,"contact":{"id":7,"email":"a@b.c","name":"A"}}""", 201) }
        srv.onJson("GET", "/api/v1/widget/tickets/12", """{"payload":[{"id":1,"content":"d","message_type":0,"created_at":10},{"id":2,"content":"reply","message_type":1,"created_at":20,"sender":{"id":3,"name":"Sara","type":"user"}}],"ticket":$ticketJson,"contact":{"id":7}}""")
        srv.on("POST", "/api/v1/widget/tickets/12/reply") { srv.json("""{"id":3,"content":"more","message_type":0,"created_at":30,"ticket":$ticketJson}""", 201) }
        val repo = testRepo(srv)
        repo.refresh()
        // client-side validation
        assertEquals("subject_required", (repo.createTicket(TicketForm("  ", "d")).exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        assertEquals("email_required", (repo.createTicket(TicketForm("s", "d")).exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        assertTrue(srv.requestsTo("POST", "/api/v1/widget/tickets").isEmpty())
        val f = File.createTempFile("att", ".txt").apply { writeText("x"); deleteOnExit() }
        val t = repo.createTicket(TicketForm("Broken", "It broke", categoryId = 4, name = "Ali", email = "a@b.c", attachments = listOf(Attachment(f, "att.txt", "text/plain")))).getOrThrow()
        assertEquals(12, t.number)
        val mp = srv.requestsTo("POST", "/api/v1/widget/tickets").single().body.readUtf8()
        for (part in listOf("ticket[subject]", "ticket[description]", "ticket[category_id]", "ticket[contact][name]", "ticket[contact][email]", "ticket[attachments][]", "message[referer_url]")) {
            assertTrue(mp.contains("name=\"$part\""), "missing $part in $mp")
        }
        assertFalse(mp.contains("ticket[website]")) // honeypot never sent
        assertEquals(12, repo.tickets.value.single().number)
        assertTrue(repo.contact.value.hasEmail)
        val thread = repo.loadTicket(12).getOrThrow()
        assertEquals(2, thread.messages.size); assertEquals("reply", thread.messages[1].content)
        val m = repo.replyToTicket(12, "more").getOrThrow()
        assertEquals("more", m.content)
        assertTrue(srv.requestsTo("POST", "/api/v1/widget/tickets/12/reply").single().body.readUtf8().contains("name=\"ticket[content]\""))
        assertEquals("description_required", (repo.replyToTicket(12, " ").exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
    }

    @Test fun ticketServerErrorsSurfaceCodes() = runBlocking<Unit> {
        srv.on("POST", "/api/v1/widget/tickets") { srv.json("""{"error":"rate_limited","code":"rate_limited"}""", 429) }
        val repo = testRepo(srv)
        repo.refresh()
        val r = repo.createTicket(TicketForm("s", "d", email = "a@b.c"))
        assertEquals("rate_limited", (r.exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        srv.on("POST", "/api/v1/widget/tickets") { srv.json("""{"error":"ticket_disabled","code":"ticket_disabled"}""", 403) }
        assertEquals("ticket_disabled", (repo.createTicket(TicketForm("s", "d", email = "a@b.c")).exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
    }

    @Test fun preChatUpdatesContactAndKeepsConversationAttributesForFirstMessage() = runBlocking<Unit> {
        srv.on("PATCH", "/api/v1/widget/contact") { srv.json("""{"id":7,"has_email":true,"has_name":true,"has_phone_number":false}""") }
        srv.onJson("GET", "/api/v1/widget/contact", """{"id":7,"has_email":true,"has_name":true,"has_phone_number":false,"pre_chat_identified":true}""")
        srv.on("POST", "/api/v1/widget/messages") { srv.json("""{"id":1,"content":"hi","conversation_id":5,"message_type":0,"created_at":5,"content_attributes":{}}""") }
        val repo = testRepo(srv)
        repo.refresh()
        assertTrue(repo.submitPreChat(mapOf("fullName" to " Ali ", "emailAddress" to "a@b.c", "plan" to "gold", "age" to "3")).isSuccess)
        val b = body("/api/v1/widget/contact", "PATCH")
        assertTrue(b.contains("\"name\":\"Ali\"") && b.contains("\"email\":\"a@b.c\"") && b.contains("\"age\":\"3\"") && !b.contains("plan"), b)
        assertTrue(repo.contact.value.preChatSatisfied)
        repo.sendMessage("hi")
        assertTrue(body("/api/v1/widget/messages").contains("\"plan\":\"gold\""))
        // not re-sent on later messages
        repo.sendMessage("again")
        assertFalse(body("/api/v1/widget/messages").contains("plan"))
    }

    @Test fun identifySwitchesContactWhenServerIssuesNewAuthToken() = runBlocking<Unit> {
        val store = InMemorySessionStore()
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi")}],"meta":{}}""")
        val repo = testRepo(srv, store)
        repo.refresh()
        assertEquals(1, repo.messages.value.size)
        srv.on("PATCH", "/api/v1/widget/contact/set_user") { srv.json("""{"id":8,"has_email":true,"has_name":true,"has_phone_number":false,"widget_auth_token":"AUTH2"}""") }
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config().replace("AUTH1", "AUTH2").replace("PUBSUB", "PUBSUB2"))
        srv.onJson("GET", "/api/v1/widget/conversations", "{}")
        val r = repo.identify(HodhodUser("user-42", "hash123", name = "Ali", email = "a@b.c", customAttributes = mapOf("plan" to "gold")))
        assertTrue(r.isSuccess)
        val b = body("/api/v1/widget/contact/set_user", "PATCH")
        assertTrue(b.contains("\"identifier\":\"user-42\"") && b.contains("\"identifier_hash\":\"hash123\"") && b.contains("\"plan\":\"gold\""), b)
        assertEquals("AUTH2", store.get(SessionStore.AUTH_TOKEN))
        assertEquals("PUBSUB2", store.get(SessionStore.PUBSUB_TOKEN))
        assertTrue(repo.messages.value.isEmpty()) // previous contact's thread is gone
        assertEquals("AUTH2", srv.requestsTo("POST", "/api/v1/widget/config").last().getHeader("X-Auth-Token"))
    }

    @Test fun identifyHmacRejectedSurfacesError() = runBlocking<Unit> {
        srv.on("PATCH", "/api/v1/widget/contact/set_user") { srv.json("""{"error":"HMAC failed: Invalid Identifier Hash Provided"}""", 401) }
        val repo = testRepo(srv)
        val r = repo.identify(HodhodUser("u", "bad"))
        assertEquals("unauthorized", (r.exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
    }

    @Test fun websocketEventsDriveMessagesTypingPresenceAndTicketRouting() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi", type = 0)}],"meta":{}}""")
        srv.onJson("GET", "/api/v1/widget/tickets", """{"payload":[{"number":3,"subject":"t","status":"open","created_at":1,"updated_at":1,"conversation_id":77}]}""")
        val repo = testRepo(srv, cable = true)
        repo.refresh()
        awaitUntil(message = "connected") { repo.connection.value == ConnectionState.CONNECTED }
        // agent message
        srv.broadcast("message.created", Fixtures.message(2, "hello there", extra = ""","sender_type":"User","echo_id":null"""))
        awaitUntil(message = "agent msg") { repo.messages.value.size == 2 }
        assertEquals(1, repo.unreadCount.value)
        // update with content_attributes merge
        srv.broadcast("message.updated", Fixtures.message(2, "hello there", extra = ""","content_attributes":{"submitted_email":"x@y.z"}""").replace("\"content_attributes\":{},", ""))
        awaitUntil(message = "updated") { repo.messages.value.last().contentAttributes["submitted_email"] == "x@y.z" }
        // deleted
        srv.broadcast("message.updated", Fixtures.message(2, "x", extra = ""","content_attributes":{"deleted":true}""").replace("\"content_attributes\":{},", ""))
        awaitUntil(message = "deleted") { repo.messages.value.size == 1 }
        // typing (ignore private), then off
        srv.broadcast("conversation.typing_on", """{"conversation":{"id":5},"is_private":true}""")
        Thread.sleep(150); assertFalse((repo.conversation.value as ConversationState.Active).agentTyping)
        srv.broadcast("conversation.typing_on", """{"conversation":{"id":5},"is_private":false}""")
        awaitUntil(message = "typing on") { (repo.conversation.value as? ConversationState.Active)?.agentTyping == true }
        srv.broadcast("conversation.typing_off", """{"conversation":{"id":5}}""")
        awaitUntil(message = "typing off") { (repo.conversation.value as? ConversationState.Active)?.agentTyping == false }
        // typing on another conversation ignored
        srv.broadcast("conversation.typing_on", """{"conversation":{"id":99},"is_private":false}""")
        Thread.sleep(150); assertFalse((repo.conversation.value as ConversationState.Active).agentTyping)
        // presence
        srv.broadcast("presence.update", """{"users":{"3":"busy"}}""")
        awaitUntil(message = "presence") { repo.agents.value.single().availability == "busy" }
        // another conversation's message is ignored; ticket conversation message becomes ticket activity
        val activity = async(kotlinx.coroutines.Dispatchers.Default) { repo.ticketActivity.first() }
        Thread.sleep(100)
        srv.broadcast("message.created", Fixtures.message(50, "other", conv = 99))
        srv.broadcast("message.created", Fixtures.message(51, "ticket reply", conv = 77))
        val ta = activity.await()
        assertEquals(3, ta.ticketNumber); assertEquals("ticket reply", ta.message.content)
        assertEquals(1, repo.messages.value.size)
        // status resolved => Ended
        srv.broadcast("conversation.status_changed", """{"id":5,"status":"resolved"}""")
        awaitUntil(message = "ended") { repo.conversation.value is ConversationState.Ended }
        repo.shutdown()
    }

    @Test fun ticketConversationEventArrivingBeforeCreateResponseDoesNotLeakIntoChat() = runBlocking<Unit> {
        val ticketJson = """{"number":12,"subject":"S","status":"open","created_at":10,"updated_at":10,"conversation_id":77}"""
        srv.onJson("GET", "/api/v1/widget/conversations", "{}")
        srv.on("POST", "/api/v1/widget/tickets") { srv.json("""{"id":1,"content":"d","message_type":0,"created_at":10,"ticket":$ticketJson,"contact":{"id":7,"email":"a@b.c"}}""", 201) }
        val repo = testRepo(srv, cable = true)
        repo.refresh()
        awaitUntil(message = "connected") { repo.connection.value == ConnectionState.CONNECTED }
        // cable is faster than the HTTP response of POST /tickets: the first message of the ticket conversation looks like a chat
        srv.broadcast("message.created", Fixtures.message(60, "ticket body", type = 0, conv = 77))
        awaitUntil(message = "phantom chat") { repo.conversation.value is ConversationState.Active }
        repo.createTicket(TicketForm("S", "d", email = "a@b.c")).getOrThrow()
        assertEquals(ConversationState.None, repo.conversation.value)
        assertTrue(repo.messages.value.isEmpty())
        // later events of that conversation are ticket activity only
        srv.broadcast("message.created", Fixtures.message(61, "late", conv = 77))
        Thread.sleep(150)
        assertEquals(ConversationState.None, repo.conversation.value)
        repo.shutdown()
    }

    @Test fun serverMessagesSharingAnEchoIdKeepUniqueListKeys() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        val a = Fixtures.message(1, "body", type = 0, extra = ""","echo_id":"dup-echo"""")
        val b = Fixtures.message(2, "body", type = 0, extra = ""","echo_id":"dup-echo"""")
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[$a,$b],"meta":{}}""")
        val repo = testRepo(srv)
        repo.refresh()
        val keys = repo.messages.value.map { it.key }
        assertEquals(2, keys.size)
        assertEquals(2, keys.toSet().size)
    }

    @Test fun reloadRacingWithSendResponseDoesNotLeaveDuplicateKeys() = runBlocking<Unit> {
        srv.on("POST", "/api/v1/widget/messages") { _ ->
            Thread.sleep(600)
            srv.json("""{"id":101,"content":"hello","inbox_id":1,"conversation_id":5,"message_type":0,"created_at":2000,"private":false,"content_attributes":{}}""")
        }
        srv.onJson("GET", "/api/v1/widget/conversations", "{}")
        val repo = testRepo(srv, cable = true)
        repo.refresh()
        awaitUntil(message = "connected") { repo.connection.value == ConnectionState.CONNECTED }
        val deferred = async(kotlinx.coroutines.Dispatchers.Default) { repo.sendMessage("hello", clientId = "cid-9") }
        awaitUntil { repo.messages.value.isNotEmpty() }
        // while the POST is in flight the conversation appears on the server and the client reloads it (the server copy has no echo id)
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(101, "hello", type = 0)}],"meta":{}}""")
        srv.broadcast("conversation.created", """{"id":5}""")
        awaitUntil(message = "reload merged") { repo.messages.value.any { it.serverId == 101L } }
        deferred.await().getOrThrow()
        val keys = repo.messages.value.map { it.key }
        assertEquals(keys.toSet().size, keys.size, keys.toString())
        assertEquals(1, repo.messages.value.size)
        assertEquals("cid-9", repo.messages.value.single().key)
        repo.shutdown()
    }

    @Test fun echoedMessageOverWebsocketDeduplicatesWithRestResponse() = runBlocking<Unit> {
        srv.on("POST", "/api/v1/widget/messages") {
            Thread.sleep(300) // ws event overtakes the HTTP response
            srv.json("""{"id":200,"content":"yo","conversation_id":5,"message_type":0,"created_at":3000,"content_attributes":{}}""")
        }
        srv.onJson("GET", "/api/v1/widget/conversations", "{}")
        val repo = testRepo(srv, cable = true)
        repo.refresh()
        awaitUntil { repo.connection.value == ConnectionState.CONNECTED }
        val job = async(kotlinx.coroutines.Dispatchers.Default) { repo.sendMessage("yo", clientId = "echo-1") }
        awaitUntil { repo.messages.value.size == 1 }
        srv.broadcast("message.created", Fixtures.message(200, "yo", type = 0, createdAt = 3000, extra = ",\"echo_id\":\"echo-1\""))
        awaitUntil(message = "echo applied") { repo.messages.value.singleOrNull()?.id == "200" }
        assertEquals(1, repo.messages.value.size)
        job.await()
        assertEquals(1, repo.messages.value.size)
        assertEquals(MessageStatus.SENT, repo.messages.value.single().status)
        repo.shutdown()
    }

    @Test fun logoutStyleResetClearsState() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi")}],"meta":{}}""")
        val repo = testRepo(srv)
        repo.refresh()
        repo.resetSession()
        assertEquals(HodhodState.Idle, repo.state.value)
        assertTrue(repo.messages.value.isEmpty()); assertNull(repo.contact.value.id)
        assertNotNull(repo.widgetConfig.value) // config kept so the UI can still render the home
    }

    @Test fun cleartextBaseUrlIsRefusedUnlessAllowed() = runBlocking<Unit> {
        val repo = testRepo(srv)
        repo.failBootstrap(chat.hodhod.sdk.HodhodException("cleartext", "no"))
        assertEquals("cleartext", (repo.refresh().exceptionOrNull() as chat.hodhod.sdk.HodhodException).code)
        assertTrue(srv.requests.isEmpty())
    }
}
