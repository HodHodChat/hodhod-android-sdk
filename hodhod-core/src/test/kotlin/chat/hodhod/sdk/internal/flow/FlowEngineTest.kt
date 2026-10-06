package chat.hodhod.sdk.internal.flow

import chat.hodhod.sdk.FlowStatus
import chat.hodhod.sdk.internal.Fixtures
import chat.hodhod.sdk.internal.TestServer
import chat.hodhod.sdk.internal.testRepo
import chat.hodhod.sdk.internal.parseJson
import chat.hodhod.sdk.internal.obj
import chat.hodhod.sdk.internal.arr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real repository + engine against a mock server: flow fetch, handoff body (live/ticket), analytics envelope, reset, config extras. */
class FlowEngineTest {
    private lateinit var srv: TestServer

    @Before fun setUp() { srv = TestServer() }
    @After fun tearDown() { srv.stop() }

    private val FLOW = """{"payload":{"schema":2,"flow_id":9,"revision":4,"require_flow":true,"bot":{"name":"Bot","avatar_url":null},
      "settings":{"enabled":true,"botName":"Bot","welcome":"hi {{contact.name|default:\"friend\"}}"},"menu_order":["t"],
      "nodes":[{"id":"t","data":{"kind":"topic","label":"Help"}},{"id":"i","data":{"kind":"input","label":"Subject","inputType":"text","variable":"subject"}},
               {"id":"a","data":{"kind":"agent","mode":"ticket","ticketMessage":"done"}}],
      "edges":[{"id":"e1","source":"t","target":"i","sourceHandle":null},{"id":"e2","source":"i","target":"a","sourceHandle":null}]}}"""

    private fun repoWithFlow(): chat.hodhod.sdk.internal.DefaultHodhodRepository {
        srv.onJson("GET", "/api/v1/widget/chatbot_flow", FLOW)
        srv.onJson("POST", "/api/v1/widget/chatbot_flow_events", """{"accepted":[],"duplicate":[],"rejected":[]}""")
        srv.onJson("POST", "/api/v1/widget/config", Fixtures.config(extra = ""","has_flow_bot":true,"lock_to_single_conversation":false,"lark_holidays":[],"ticket_categories":[]"""))
        srv.onJson("POST", "/api/v1/widget/messages", """{"id":55,"content":"x","message_type":0,"created_at":1000,"conversation_id":31,"status":"sent"}""")
        val repo = testRepo(srv)
        repo.flowScopeFactory = { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        repo.flowSchedulerFactory = { object : FlowScheduler {
            override fun setTimeout(ms: Long, fn: () -> Unit): Any = Object()
            override fun clearTimeout(id: Any) = Unit
        } }
        return repo
    }

    @Test fun configWithAllExtrasSkipsTheWidgetHtmlRequest() = runBlocking<Unit> {
        val repo = repoWithFlow()
        assertTrue(repo.refresh().isSuccess)
        assertEquals(0, srv.requestsTo("GET", "/widget").size)
        assertTrue(repo.widgetConfig.value!!.hasFlowBot)
    }

    @Test fun loadsRunsAndHandsOffAsTicketWithFlowBody() = runBlocking<Unit> {
        val repo = repoWithFlow()
        repo.refresh()
        val engine = repo.flow
        engine.load()
        assertEquals(FlowStatus.READY, engine.status.value)
        assertTrue(engine.requireFlow.value)
        val v0 = engine.view.value!!
        assertEquals("menu", v0.control!!.type)
        assertEquals("hi friend", v0.blocks.first().rich.first()["children"].let { (it as List<Map<String, Any?>>).first()["value"] })
        engine.selectOption("t")
        assertEquals("input", engine.view.value!!.control!!.type)
        engine.setInputValue("Payment failed")
        engine.submitInput()
        withTimeout(5000) { while (!engine.handedOff.value) delay(20) }
        val req = srv.requestsTo("POST", "/api/v1/widget/messages").last()
        val body = parseJson(req.body.readUtf8()).obj()!!
        val flow = body["flow"]!!.obj()!!
        assertEquals("manual".takeIf { false } ?: "agent_node", (flow["reason"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(4, (flow["revision"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(2, (flow["schema"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        val trace = flow["trace"]!!.arr()!!.map { it.obj()!!["n"].toString().trim('"') }
        assertEquals(listOf("t", "i", "a"), trace)
        assertEquals("Payment failed", (flow["vars"]!!.obj()!!["subject"] as kotlinx.serialization.json.JsonPrimitive).content)
        // ticket request message carries the answers like the web widget
        val content = body["message"]!!.obj()!!["content"].toString()
        assertTrue(content.contains("Ticket request from the chatbot"), content)
        assertTrue(content.contains("Subject: Payment failed"), content)
        // reset (conversation ended) clears the session and allows loading again
        repo.resetConversation()
        assertEquals(FlowStatus.IDLE, engine.status.value)
        assertFalse(engine.handedOff.value)
        engine.load()
        assertEquals(FlowStatus.READY, engine.status.value)
        assertNotNull(engine.view.value)
        // handoff_request/created reached the analytics endpoint with the privacy-safe schema
        withTimeout(5000) { while (srv.requestsTo("POST", "/api/v1/widget/chatbot_flow_events").isEmpty()) delay(20) }
        val env = parseJson(srv.requestsTo("POST", "/api/v1/widget/chatbot_flow_events").first().body.readUtf8()).obj()!!
        assertTrue(env["sid"] != null && env["events"]!!.arr()!!.isNotEmpty())
        val types = env["events"]!!.arr()!!.map { it.obj()!!["type"].toString().trim('"') }
        assertTrue("flow_start" in types && "handoff_request" in types, types.toString())
        assertFalse(env.toString().contains("Payment failed"), "typed values must never be sent")
    }

    @Test fun inboxWithoutFlowIsNone() = runBlocking<Unit> {
        srv.onJson("GET", "/api/v1/widget/chatbot_flow", """{"payload":{"schema":2,"settings":{"enabled":false},"nodes":[],"edges":[],"menu_order":[],"require_flow":false}}""")
        val repo = repoWithFlow()
        srv.onJson("GET", "/api/v1/widget/chatbot_flow", """{"payload":{"schema":2,"settings":{"enabled":false},"nodes":[],"edges":[],"menu_order":[],"require_flow":false}}""")
        repo.refresh()
        repo.flow.load()
        assertEquals(FlowStatus.NONE, repo.flow.status.value)
        assertFalse(repo.flow.isActive.value)
    }

    @Test fun directStartMarksManualAndCarriesFlowOnFirstMessage() = runBlocking<Unit> {
        val repo = repoWithFlow()
        repo.refresh()
        repo.flow.load()
        repo.flow.prepareDirectStart()
        repo.sendMessage("hello")
        val body = parseJson(srv.requestsTo("POST", "/api/v1/widget/messages").last().body.readUtf8()).obj()!!
        assertEquals("manual", (body["flow"]!!.obj()!!["reason"] as kotlinx.serialization.json.JsonPrimitive).content)
    }
}
