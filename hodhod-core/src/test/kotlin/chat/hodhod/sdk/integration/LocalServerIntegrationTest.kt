package chat.hodhod.sdk.integration

import chat.hodhod.sdk.Attachment
import chat.hodhod.sdk.ConnectionState
import chat.hodhod.sdk.ContactMode
import chat.hodhod.sdk.ConversationState
import chat.hodhod.sdk.HodhodConfig
import chat.hodhod.sdk.HodhodException
import chat.hodhod.sdk.HodhodState
import chat.hodhod.sdk.HodhodUser
import chat.hodhod.sdk.MessageStatus
import chat.hodhod.sdk.MessageType
import chat.hodhod.sdk.TicketForm
import chat.hodhod.sdk.internal.CableClient
import chat.hodhod.sdk.internal.DefaultHodhodRepository
import chat.hodhod.sdk.internal.InMemorySessionStore
import chat.hodhod.sdk.internal.SessionStore
import chat.hodhod.sdk.internal.WidgetApi
import chat.hodhod.sdk.internal.cableUrl
import chat.hodhod.sdk.internal.int
import chat.hodhod.sdk.internal.jsonObjectOf
import chat.hodhod.sdk.internal.long
import chat.hodhod.sdk.internal.obj
import chat.hodhod.sdk.internal.parseJson
import chat.hodhod.sdk.internal.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests of the real implementation against the LOCAL Hodhod server (JVM only, real HTTP + real ActionCable).
 * Skipped automatically when the server or the seed file is unavailable.
 *
 * Setup (once): /tmp/lark-tools/rails-runner.sh /tmp/android-core/setup.rb  (+ docker cp of /tmp/lark-seed/android-core-it.json to the host)
 * Env: HODHOD_IT_BASE (default http://localhost:3000), HODHOD_IT_ENV (default /tmp/lark-seed/android-core-it.json)
 */
class LocalServerIntegrationTest {
    private val base = System.getenv("HODHOD_IT_BASE") ?: "http://localhost:3000"
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var env: kotlinx.serialization.json.JsonObject
    private val repos = mutableListOf<DefaultHodhodRepository>()

    private val webToken get() = env.str("website_token")!!
    private val accountId get() = env.int("account_id")!!
    private val inboxId get() = env.int("inbox_id")!!

    @Before fun setUp() {
        val file = File(System.getenv("HODHOD_IT_ENV") ?: "/tmp/lark-seed/android-core-it.json")
        assumeTrue("seed file missing: run /tmp/android-core/setup.rb", file.exists())
        env = parseJson(file.readText()).obj()!!
        val up = runCatching { http.newCall(Request.Builder().url("$base/api").build()).execute().use { it.code in 200..499 } }.getOrDefault(false)
        assumeTrue("server $base not reachable", up)
    }

    @After fun tearDown() {
        repos.forEach { it.shutdown() }
        scope.cancel()
    }

    private fun newRepo(token: String = webToken, store: SessionStore = InMemorySessionStore(), locale: String? = null): DefaultHodhodRepository {
        val cfg = HodhodConfig(base, token, locale = locale, allowCleartext = true)
        val wsHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        lateinit var repo: DefaultHodhodRepository
        val api = WidgetApi(base.trimEnd('/').plus("/").toHttpUrl(), token, http, { repo.uiLocale.value }, { store.get(SessionStore.AUTH_TOKEN) })
        repo = DefaultHodhodRepository(
            api, store, cfg, scope,
            cableFactory = { t, s, e, r -> CableClient(wsHttp, cableUrl(base), base, t, scope, s, e, r) },
            deviceLocale = { "en-US" },
        )
        repos += repo
        return repo
    }

    // ---- agent (dashboard) side via the real REST API -------------------------------------------------------------

    private fun agentCall(method: String, path: String, body: kotlinx.serialization.json.JsonObject? = null): kotlinx.serialization.json.JsonElement? {
        val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/api/v1/accounts/$accountId/$path").header("api_access_token", env.str("agent_api_token")!!)
            .method(method, rb ?: if (method == "GET") null else "{}".toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            assertTrue(r.isSuccessful, "agent $method $path -> ${r.code} $text")
            return parseJson(text)
        }
    }

    private fun agentReply(conversationId: Int, text: String, private: Boolean = false) =
        agentCall("POST", "conversations/$conversationId/messages", jsonObjectOf("content" to text, "message_type" to "outgoing", "private" to private))

    private fun agentTyping(conversationId: Int, on: Boolean) =
        agentCall("POST", "conversations/$conversationId/toggle_typing_status", jsonObjectOf("typing_status" to if (on) "on" else "off"))

    private fun agentSetStatus(conversationId: Int, status: String) =
        agentCall("POST", "conversations/$conversationId/toggle_status", jsonObjectOf("status" to status))

    private fun agentAssign(conversationId: Int) =
        agentCall("POST", "conversations/$conversationId/assignments", jsonObjectOf("assignee_id" to env.int("agent_id")))

    /** The server caps ticket creation at 20 per hour per IP (rack-attack) and per contact; repeated local runs hit it. That is environment, not a failure. */
    private fun <T> Result<T>.orSkipWhenThrottled(): T {
        val e = exceptionOrNull() as? HodhodException
        org.junit.Assume.assumeFalse("ticket rate limit of the local server reached: ${e?.code}", e?.code == "rate_limited")
        return getOrThrow()
    }

    private suspend fun connected(repo: DefaultHodhodRepository) {
        withTimeout(15_000) { while (repo.connection.value != ConnectionState.CONNECTED) delay(50) }
    }

    private suspend fun until(timeoutMs: Long = 15_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return; delay(50) }
        throw AssertionError("timeout: $what")
    }

    private fun activeId(repo: DefaultHodhodRepository): Int = (repo.conversation.value as ConversationState.Active).id

    // ---- tests ---------------------------------------------------------------------------------------------------

    @Test fun bootstrapLoadsRealWidgetConfig() = runBlocking<Unit> {
        val repo = newRepo(locale = "fa")
        assertTrue(repo.refresh().isSuccess)
        assertEquals(HodhodState.Ready, repo.state.value)
        val cfg = repo.widgetConfig.value!!
        assertEquals(webToken, cfg.websiteToken)
        assertEquals(ContactMode.BOTH, cfg.contactMode)
        assertTrue(cfg.csatSurveyEnabled)
        assertTrue(cfg.enabledFeatures.attachments && cfg.enabledFeatures.endConversation)
        assertFalse(cfg.lockToSingleConversation)
        assertEquals("fa", repo.uiLocale.value)
        assertNotNull(repo.contact.value.id)
        assertEquals(ConversationState.None, repo.conversation.value)
        assertTrue(repo.agents.value.any { it.name.contains("اپراتور") }, repo.agents.value.toString())
        connected(repo)
        // issue notices endpoint is reachable (may be empty)
        assertTrue(repo.issueNotices.value.size <= 3)
        assertEquals("both", cfg.contactMode.wire)
    }

    @Test fun unknownTokenFailsBootstrapWithNotFound() = runBlocking<Unit> {
        val repo = newRepo(token = "does-not-exist-token")
        val r = repo.refresh()
        assertTrue(r.isFailure)
        val s = repo.state.value as HodhodState.Failed
        assertEquals("not_found", s.code)
    }

    @Test fun chatBothWaysOverRealWebsocketThenEndCsatAndReset() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow()
        connected(repo)
        val sent = repo.sendMessage("سلام از SDK .seed", clientId = HodhodRepositoryId.next()).getOrThrow()
        assertEquals(MessageStatus.SENT, sent.status); assertNotNull(sent.serverId)
        val convId = activeId(repo)
        assertEquals(MessageType.INCOMING, repo.messages.value[0].messageType)
        // the server auto-adds template messages (e-mail prompt) after the first visitor message; they are not "unread" agent messages
        until(what = "template messages over websocket") { repo.messages.value.any { it.messageType == MessageType.TEMPLATE } }

        agentAssign(convId)
        // agent typing -> indicator, then the reply arrives live over the websocket
        agentTyping(convId, true)
        until(what = "agent typing") { (repo.conversation.value as? ConversationState.Active)?.agentTyping == true }
        agentReply(convId, "پاسخ اپراتور .seed")
        until(what = "agent reply over websocket") { repo.messages.value.any { it.messageType == MessageType.OUTGOING && it.content == "پاسخ اپراتور .seed" } }
        val reply = repo.messages.value.last { it.messageType == MessageType.OUTGOING }
        assertEquals(1, repo.messages.value.count { it.content == "پاسخ اپراتور .seed" }) // no duplicates
        assertTrue(reply.sender?.name?.contains("اپراتور") == true, reply.sender.toString())
        assertEquals(1, repo.unreadCount.value)
        repo.markRead().getOrThrow()
        assertEquals(0, repo.unreadCount.value)
        // private notes never reach the visitor
        agentReply(convId, "private note .seed", private = true)
        delay(700)
        assertTrue(repo.messages.value.none { it.content == "private note .seed" })
        // a fresh client (app restart) sees the same thread
        val store2 = InMemorySessionStore().also { s -> listOf(SessionStore.AUTH_TOKEN).forEach { k -> s.put(k, repos[0].authToken) } }
        val repo2 = newRepo(store = store2)
        repo2.refresh().getOrThrow()
        assertEquals(convId, activeId(repo2))
        assertEquals(repo.messages.value.map { it.serverId }.toSet(), repo2.messages.value.map { it.serverId }.toSet())

        // visitor ends the chat -> Ended -> CSAT arrives -> submit -> reset -> home
        repo.endConversation().getOrThrow()
        assertTrue(repo.conversation.value is ConversationState.Ended)
        assertFalse(repo.hasActiveConversation.value)
        until(what = "csat message from server") { repo.messages.value.any { it.contentType == "input_csat" } }
        repo.submitCsat(5, "عالی .seed").getOrThrow()
        assertTrue((repo.conversation.value as ConversationState.Ended).csatSubmitted)
        repo.resetConversation()
        assertEquals(ConversationState.None, repo.conversation.value)
        assertTrue(repo.messages.value.isEmpty())
        // after reset (resolved conversation hidden by server) a refresh stays on home
        repo.refresh().getOrThrow()
        assertEquals(ConversationState.None, repo.conversation.value)
        // next message starts a NEW conversation
        repo.sendMessage("گفت‌وگوی دوم .seed").getOrThrow()
        assertNotEquals(convId, activeId(repo))
    }

    @Test fun agentResolvingConversationEndsItLive() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow(); connected(repo)
        repo.sendMessage("need help .seed").getOrThrow()
        val convId = activeId(repo)
        agentSetStatus(convId, "resolved")
        until(what = "ended over websocket") { repo.conversation.value is ConversationState.Ended }
        // agent reopens it while the end screen is still showing
        agentSetStatus(convId, "open")
        until(what = "reopened") { repo.conversation.value is ConversationState.Active }
    }

    @Test fun lateEventsOfLeftConversationAreIgnored() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow(); connected(repo)
        repo.sendMessage("bye .seed").getOrThrow()
        val convId = activeId(repo)
        repo.endConversation().getOrThrow()
        repo.resetConversation()
        agentReply(convId, "late reply after reset .seed")
        delay(1_500)
        assertTrue(repo.messages.value.isEmpty())
        assertEquals(ConversationState.None, repo.conversation.value)
    }

    @Test fun attachmentUploadRoundTrip() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow()
        val png = File.createTempFile("pixel", ".png").apply {
            // 1x1 PNG
            writeBytes(java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
            deleteOnExit()
        }
        val r = repo.sendMessage("", listOf(Attachment(png, "pixel.png", "image/png"))).getOrThrow()
        assertEquals(MessageStatus.SENT, r.status)
        val att = repo.messages.value.last { it.attachments.isNotEmpty() }.attachments.single()
        assertEquals("image", att.fileType)
        assertTrue(!att.dataUrl.isNullOrEmpty(), att.toString())
    }

    @Test fun paginationLoadsOlderMessages() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow()
        repo.sendMessage("first .seed").getOrThrow()
        val convId = activeId(repo)
        repeat(24) { agentReply(convId, "agent msg $it .seed") }
        val repo2 = newRepo(store = InMemorySessionStore().also { it.put(SessionStore.AUTH_TOKEN, repos[0].authToken) })
        repo2.refresh().getOrThrow()
        assertEquals(20, repo2.messages.value.size)
        assertTrue((repo2.conversation.value as ConversationState.Active).hasMore)
        repo2.loadOlder().getOrThrow()
        // 1 visitor message + 24 agent replies + the server's template messages
        assertTrue(repo2.messages.value.size >= 25, repo2.messages.value.size.toString())
        assertEquals(repo2.messages.value.size, repo2.messages.value.map { it.serverId }.toSet().size)
        assertFalse((repo2.conversation.value as ConversationState.Active).hasMore)
        assertEquals("first .seed", repo2.messages.value.first().content)
        assertEquals(repo2.messages.value.map { it.createdAt }.sorted(), repo2.messages.value.map { it.createdAt })
    }

    @Test fun ticketCreateListThreadReplyAndLiveAgentReply() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow(); connected(repo)
        val file = File.createTempFile("ticketlog", ".txt").apply { writeText("log line"); deleteOnExit() }
        val t = repo.createTicket(
            TicketForm("مشکل ورود .seed", "نمی‌توانم وارد شوم .seed", name = "علی", email = "ali-${System.currentTimeMillis()}.android-core.seed@hodhod.test",
                attachments = listOf(Attachment(file, "log.txt", "text/plain"))),
        ).orSkipWhenThrottled()
        assertTrue(t.number > 0); assertEquals(chat.hodhod.sdk.TicketStatus.OPEN, t.status)
        assertTrue(repo.contact.value.hasEmail)
        assertEquals(t.number, repo.tickets.value.first().number)
        assertEquals(ConversationState.None, repo.conversation.value) // a ticket is not a chat
        val thread = repo.loadTicket(t.number).getOrThrow()
        assertEquals(1, thread.messages.size)
        assertEquals(1, thread.messages[0].attachments.size)
        repo.replyToTicket(t.number, "اطلاعات بیشتر .seed").getOrThrow()
        assertEquals(2, repo.loadTicket(t.number).getOrThrow().messages.size)
        // agent answers the ticket's conversation -> live ticket activity, not a chat message
        val activity = async { withTimeout(15_000) { repo.ticketActivity.first() } }
        delay(300)
        agentReply(t.conversationId!!, "پاسخ تیکت .seed")
        val a = activity.await()
        assertEquals(t.number, a.ticketNumber); assertEquals("پاسخ تیکت .seed", a.message.content)
        assertTrue(repo.messages.value.isEmpty())
        // fresh session lists the ticket
        val repo2 = newRepo(store = InMemorySessionStore().also { it.put(SessionStore.AUTH_TOKEN, repos[0].authToken) })
        repo2.refresh().getOrThrow()
        assertEquals(listOf(t.number), repo2.tickets.value.map { it.number })
        // validation errors come from the server too
        val bad = repo.createTicket(TicketForm("x", "y", email = "not-an-email")).exceptionOrNull() as HodhodException
        assertTrue(bad.code in setOf("invalid_param", "invalid_email", "email_invalid", "server"), bad.code)
    }

    @Test fun ticketOnlyInboxRefusesChatButAcceptsTickets() = runBlocking<Unit> {
        val repo = newRepo(token = env.str("ticket_website_token")!!)
        repo.refresh().getOrThrow()
        assertEquals(ContactMode.TICKET, repo.widgetConfig.value!!.contactMode)
        val r = repo.sendMessage("hello .seed")
        assertEquals("chat_disabled", (r.exceptionOrNull() as HodhodException).code)
        assertEquals(MessageStatus.FAILED, repo.messages.value.single().status)
        repo.createTicket(TicketForm("subject .seed", "body .seed", email = "t-${System.currentTimeMillis()}.android-core.seed@hodhod.test")).orSkipWhenThrottled()
    }

    @Test fun identifyWithHmacAndSessionSwitch() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow()
        val run = System.currentTimeMillis()
        val user1 = "it-user-1-$run"
        val user2 = "it-user-2-$run"
        val firstContact = repo.contact.value.id
        fun hmac(id: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(env.str("hmac_token")!!.toByteArray(), "HmacSHA256")) }
            .doFinal(id.toByteArray()).joinToString("") { "%02x".format(it) }
        // wrong hash rejected
        val bad = repo.identify(HodhodUser(user1, "deadbeef")).exceptionOrNull() as HodhodException
        assertEquals("unauthorized", bad.code)
        // right hash identifies the anonymous contact in place
        repo.identify(HodhodUser(user1, hmac(user1), name = "Ali IT", email = "it1-$run.android-core.seed@hodhod.test")).getOrThrow()
        assertEquals(user1, repo.contact.value.identifier)
        assertTrue(repo.contact.value.hasEmail)
        assertEquals(firstContact, repo.contact.value.id)
        repo.sendMessage("as user 1 .seed").getOrThrow()
        assertTrue(repo.messages.value.isNotEmpty())
        // another user on the same device -> new contact/session, old thread gone
        repo.identify(HodhodUser(user2, hmac(user2), name = "Sara IT")).getOrThrow()
        assertNotEquals(firstContact, repo.contact.value.id)
        assertTrue(repo.messages.value.isEmpty())
        assertEquals(ConversationState.None, repo.conversation.value)
        connected(repo)
        // custom attributes
        repo.setContactCustomAttributes(mapOf("plan" to "gold", "age" to 3)).getOrThrow()
    }

    @Test fun preChatThenTranscriptRequest() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow()
        repo.submitPreChat(mapOf("fullName" to "Pre Chat", "emailAddress" to "pc-${System.currentTimeMillis()}.android-core.seed@hodhod.test")).getOrThrow()
        assertTrue(repo.contact.value.hasEmail && repo.contact.value.hasName)
        repo.sendMessage("prechat done .seed").getOrThrow()
        val r = repo.sendTranscriptByEmail()
        // 200 when the account has email transcripts enabled, 402 (payment_required) otherwise: both are valid server answers
        assertTrue(r.isSuccess || (r.exceptionOrNull() as HodhodException).code in setOf("payment_required", "rate_limited"), r.toString())
    }

    @Test fun typingFromVisitorDoesNotBreak() = runBlocking<Unit> {
        val repo = newRepo()
        repo.refresh().getOrThrow()
        repo.sendMessage("typing test .seed").getOrThrow()
        repo.setTyping(true); delay(300); repo.setTyping(false); delay(300)
        assertTrue(repo.conversation.value is ConversationState.Active)
    }
}

internal object HodhodRepositoryId { fun next(): String = chat.hodhod.sdk.HodhodRepository.newClientId() }
