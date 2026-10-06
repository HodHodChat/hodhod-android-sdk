package chat.hodhod.sdk.internal

import chat.hodhod.sdk.HodhodException
import chat.hodhod.sdk.TicketFilter
import chat.hodhod.sdk.TicketForm
import chat.hodhod.sdk.TicketStatus
import chat.hodhod.sdk.TicketSummaryCounts
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "My tickets": filters, counters, pagination, converted tickets and older-server fallbacks (MockWebServer). */
class MyTicketsTest {
    private lateinit var srv: TestServer

    @Before fun setUp() { srv = TestServer() }
    @After fun tearDown() { srv.stop() }

    private fun t(n: Int, status: String = "open", source: String = "widget", conv: Int = 100 + n, extra: String = "") =
        """{"number":$n,"subject":"S$n","status":"$status","source":"$source","conversation_id":$conv,"conversation_display_id":$conv,"created_at":${1000 + n},"updated_at":${2000 + n}$extra}"""

    private fun listJson(items: List<String>, open: Int, closed: Int, page: Int = 1, per: Int = 20) =
        """{"payload":[${items.joinToString(",")}],"meta":{"open_count":$open,"closed_count":$closed,"total":${open + closed},"page":$page,"per_page":$per}}"""

    @Test fun parsesSourceDisplayIdAndIsOpen() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/tickets", listJson(listOf(t(1, "waiting_on_customer", "conversation", 55, ""","is_open":true,"last_agent_reply_at":5000"""), t(2, "closed", extra = ""","is_open":false""")), 1, 1))
        val repo = testRepo(srv)
        val list = repo.loadTickets(TicketFilter.ALL).getOrThrow()
        val a = list.tickets[0]
        assertEquals("conversation", a.source); assertTrue(a.isFromConversation); assertEquals(55, a.conversationDisplayId)
        assertTrue(a.isOpen); assertEquals(5000L, a.lastAgentReplyAt); assertEquals(TicketStatus.WAITING, a.status)
        assertFalse(list.tickets[1].isOpen); assertFalse(list.tickets[1].isFromConversation)
        assertEquals(1, list.counts.open); assertEquals(1, list.counts.closed); assertEquals(2, list.counts.total)
    }

    @Test fun filterAndPageAreSentAndHasMoreComesFromCounters() = runBlocking<Unit> {
        srv.on("GET", "/api/v1/widget/tickets") { r ->
            val q = r.requestUrl!!
            assertEquals("closed", q.queryParameter("status")); assertEquals("2", q.queryParameter("page")); assertEquals("2", q.queryParameter("per_page"))
            srv.json(listJson(listOf(t(3, "closed")), 1, 5, page = 2, per = 2))
        }
        val repo = testRepo(srv)
        val l = repo.loadTickets(TicketFilter.CLOSED, 2, 2).getOrThrow()
        assertEquals(2, l.page); assertTrue(l.hasMore) // 2 * 2 < 5
        assertEquals(TicketSummaryCounts(1, 6), repo.ticketSummary.value)
        srv.on("GET", "/api/v1/widget/tickets") { srv.json(listJson(listOf(t(1, "closed")), 1, 5, page = 3, per = 2)) }
        assertFalse(repo.loadTickets(TicketFilter.CLOSED, 3, 2).getOrThrow().hasMore) // 3 * 2 >= 5
    }

    @Test fun olderServerWithoutMetaIsFilteredPaginatedAndCountedOnTheClient() = runBlocking<Unit> {
        val all = (1..5).map { n -> """{"number":$n,"subject":"S$n","status":"${if (n % 2 == 0) "closed" else "open"}","created_at":$n,"updated_at":$n,"conversation_id":${100 + n}}""" }
        srv.onJson("GET", "/api/v1/widget/tickets", """{"payload":[${all.joinToString(",")}]}""")
        val repo = testRepo(srv)
        val open = repo.loadTickets(TicketFilter.OPEN, 1, 2).getOrThrow()
        assertEquals(listOf(1, 3), open.tickets.map { it.number }); assertTrue(open.hasMore)
        assertEquals(3, open.counts.open); assertEquals(2, open.counts.closed)
        assertNull(open.tickets[0].source)
        assertEquals(listOf(5), repo.loadTickets(TicketFilter.OPEN, 2, 2).getOrThrow().tickets.map { it.number })
        assertEquals(listOf(2, 4), repo.loadTickets(TicketFilter.CLOSED).getOrThrow().tickets.map { it.number })
    }

    @Test fun summaryEndpointAndFallbackToList() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/tickets/summary", """{"open_count":2,"total":7}""")
        srv.on("GET", "/api/v1/widget/tickets") { srv.json("""{"error":"boom"}""", 500) } // list down: the light endpoint still feeds the badge
        val repo = testRepo(srv)
        repo.refresh()
        awaitUntil(message = "summary") { repo.ticketSummary.value == TicketSummaryCounts(2, 7) }
        // refresh() loads the first page whose meta is authoritative as well
        srv.onJson("GET", "/api/v1/widget/tickets", listJson(listOf(t(1)), 3, 1))
        repo.loadTickets(TicketFilter.ALL)
        assertEquals(TicketSummaryCounts(3, 4), repo.ticketSummary.value)
    }

    @Test fun olderServerSummaryFallsBackToTicketListCounts() = runBlocking<Unit> {
        srv.on("GET", "/api/v1/widget/tickets/summary") { srv.json("""{"error":"not_found"}""", 404) }
        srv.onJson("GET", "/api/v1/widget/tickets", """{"payload":[{"number":1,"subject":"a","status":"open","created_at":1,"updated_at":1},{"number":2,"subject":"b","status":"resolved","created_at":1,"updated_at":1}]}""")
        val repo = testRepo(srv)
        repo.refresh()
        awaitUntil(message = "fallback summary") { repo.ticketSummary.value == TicketSummaryCounts(1, 2) }
    }

    @Test fun failedSummaryKeepsLastValueAndFailedListReportsError() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/tickets", listJson(listOf(t(1)), 1, 0))
        val repo = testRepo(srv)
        repo.loadTickets(TicketFilter.ALL)
        assertEquals(TicketSummaryCounts(1, 1), repo.ticketSummary.value)
        srv.on("GET", "/api/v1/widget/tickets") { srv.json("""{"error":"boom"}""", 500) }
        assertEquals("server", (repo.loadTickets(TicketFilter.OPEN).exceptionOrNull() as HodhodException).code)
        assertEquals(TicketSummaryCounts(1, 1), repo.ticketSummary.value)
    }

    @Test fun createTicketBumpsSummaryOptimistically() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/tickets/summary", """{"open_count":0,"total":0}""")
        srv.on("POST", "/api/v1/widget/tickets") { srv.json("""{"id":1,"content":"d","message_type":0,"created_at":10,"ticket":${t(9)},"contact":{"id":7}}""", 201) }
        val repo = testRepo(srv)
        repo.refresh()
        srv.onJson("GET", "/api/v1/widget/tickets/summary", """{"open_count":1,"total":1}""")
        repo.createTicket(TicketForm("S9", "d", email = "a@b.c")).getOrThrow()
        awaitUntil(message = "summary after create") { repo.ticketSummary.value == TicketSummaryCounts(1, 1) }
    }

    @Test fun convertedTicketOfTheCurrentChatStaysInTheChat() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/conversations", Fixtures.conversation(5))
        srv.onJson("GET", "/api/v1/widget/messages", """{"payload":[${Fixtures.message(1, "hi", conv = 5)}],"meta":{}}""")
        srv.onJson("GET", "/api/v1/widget/tickets", listJson(listOf(t(1, "open", "conversation", conv = 5), t(2, "open", "widget", conv = 77)), 2, 0))
        val repo = testRepo(srv, cable = true)
        repo.refresh()
        awaitUntil(message = "cable") { repo.connection.value == chat.hodhod.sdk.ConnectionState.CONNECTED }
        srv.broadcast("message.created", Fixtures.message(2, "agent says", type = 1, conv = 5))
        awaitUntil(message = "agent message in chat") { repo.messages.value.any { it.content == "agent says" } }
        srv.broadcast("message.created", Fixtures.message(3, "ticket reply", type = 1, conv = 77))
        awaitUntil(message = "ticket activity handled") { srv.requestsTo("GET", "/api/v1/widget/tickets").size >= 2 }
        assertFalse(repo.messages.value.any { it.content == "ticket reply" })
    }
}
