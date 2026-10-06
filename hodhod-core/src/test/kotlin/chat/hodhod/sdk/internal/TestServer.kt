package chat.hodhod.sdk.internal

import chat.hodhod.sdk.HodhodConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** MockWebServer with simple routing + a fake ActionCable endpoint at /cable. */
internal class TestServer : Dispatcher() {
    val server = MockWebServer()
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = CopyOnWriteArrayList<Triple<String, String, (RecordedRequest) -> MockResponse>>()

    /** Server-side websockets of the fake cable (latest last). */
    val sockets = CopyOnWriteArrayList<WebSocket>()
    val cableFrames = CopyOnWriteArrayList<String>()
    @Volatile var cableAutoConfirm = true
    @Volatile var cableSendPings = true
    @Volatile var cableConnections = 0

    init {
        server.dispatcher = this
        server.start()
        installDefaults()
    }

    fun url(): String = server.url("/").toString().trimEnd('/')

    fun on(method: String, path: String, handler: (RecordedRequest) -> MockResponse) {
        routes.add(0, Triple(method, path, handler))
    }

    fun onJson(method: String, path: String, body: String, code: Int = 200) = on(method, path) { json(body, code) }

    fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests.add(request)
        val path = request.path!!.substringBefore('?')
        if (path == "/cable") return cableResponse()
        val r = routes.firstOrNull { it.first == request.method && it.second == path }
        return r?.third?.invoke(request) ?: json("{}", 404)
    }

    private fun cableResponse(): MockResponse {
        cableConnections++
        return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets.add(webSocket)
                webSocket.send("""{"type":"welcome"}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                cableFrames.add(text)
                if (text.contains("\"subscribe\"") && cableAutoConfirm) {
                    val id = parseJson(text).obj()!!.str("identifier")!!
                    webSocket.send(jsonObjectOf("identifier" to id, "type" to "confirm_subscription").toString())
                    if (cableSendPings) webSocket.send("""{"type":"ping","message":${System.currentTimeMillis() / 1000}}""")
                }
            }
        })
    }

    /** Broadcast an event to the newest connected cable client. */
    fun broadcast(event: String, data: String) {
        val id = jsonObjectOf("channel" to "RoomChannel", "pubsub_token" to "PUBSUB").toString()
        val msg = """{"identifier":${JsonPrimitiveOf(id)},"message":{"event":"$event","data":$data}}"""
        sockets.last().send(msg)
    }

    fun requestsTo(method: String, path: String) = requests.filter { it.method == method && it.path!!.substringBefore('?') == path }

    fun stop() {
        sockets.forEach { runCatching { it.cancel() } }
        runCatching { server.shutdown() }
    }

    private fun installDefaults() {
        onJson("POST", "/api/v1/widget/config", Fixtures.config())
        on("GET", "/widget") { MockResponse().setBody(Fixtures.widgetHtml()) }
        onJson("GET", "/api/v1/widget/conversations", "{}")
        onJson("GET", "/api/v1/widget/messages", """{"payload":[],"meta":{"contact_last_seen_at":0}}""")
        onJson("GET", "/api/v1/widget/contact", """{"id":7,"has_email":false,"has_name":false,"has_phone_number":false,"identifier":null,"pre_chat_identified":false}""")
        onJson("GET", "/api/v1/widget/inbox_members", """{"payload":[{"id":3,"name":"Sara","avatar_url":"","availability_status":"online"}]}""")
        onJson("GET", "/api/v1/widget/issue_notices", """{"payload":[{"id":9,"message":"Payments degraded"}]}""")
        onJson("GET", "/api/v1/widget/tickets", """{"payload":[]}""")
    }

    @Suppress("FunctionName")
    private fun JsonPrimitiveOf(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()
}

internal object Fixtures {
    fun config(contactMode: String = "chat", extra: String = ""): String = """
    {"website_channel_config":{"allow_messages_after_resolved":true,"api_host":"http://x","auth_token":"AUTH1","avatar_url":"",
    "contact_mode":"$contactMode","csat_survey_enabled":true,"disable_branding":false,"enabled_features":["attachments","emoji_picker","end_conversation"],
    "enabled_languages":[{"name":"English (en)","iso_639_1_code":"en"},{"name":"فارسی (fa)","iso_639_1_code":"fa"}],"locale":"fa",
    "out_of_office_message":"closed","pre_chat_form_enabled":true,
    "pre_chat_form_options":{"pre_chat_message":"Tell us","pre_chat_fields":[
      {"name":"fullName","type":"text","label":"Name","enabled":true,"required":true,"field_type":"standard","placeholder":"Your name"},
      {"name":"emailAddress","type":"email","label":"Email","enabled":true,"required":false,"field_type":"standard"},
      {"name":"plan","type":"text","label":"Plan","enabled":true,"required":false,"field_type":"conversation_attribute"}]},
    "reply_time":"in_a_few_minutes","ticket_form":{"category":"optional","email":"required","attachments":true,"title":{"fa":"تیکت","en":"Ticket"}},
    "timezone":"Asia/Tehran","utc_off_set":"+03:30","website_name":"Hodhod","website_token":"WT","welcome_tagline":"tag","welcome_title":"Hi",
    "widget_color":"#7A4FD1","working_hours":[],"working_hours_enabled":false$extra},
    "contact":{"email":null,"id":7,"identifier":null,"name":"blue-sunset-829","phone_number":null,"pubsub_token":"PUBSUB"},
    "global_config":{"MAXIMUM_FILE_UPLOAD_SIZE":40}}
    """.trimIndent()

    fun widgetHtml(): String = """
    <script>
      window.chatwootWebChannel = {
        avatarUrl: '',
        hasFlowBot: true,
        locale: 'fa',
        websiteName: 'x',
        ticketForm: {"category":"optional","email":"required","attachments":true},
        ticketCategories: [{"id":4,"name":"Billing","color":"#f00"}],
        larkHolidays: ["2026-11-13"],
        lockToSingleConversation: false,
        allowMessagesAfterResolved: true,
        disableBranding: false,
      }
      window.chatwootPubsubToken = 'PUBSUB'
    </script>
    """.trimIndent()

    fun message(id: Long, content: String, type: Int = 1, conv: Int = 5, createdAt: Long = 1_000 + id, extra: String = "") =
        """{"id":$id,"content":"$content","message_type":$type,"content_type":"text","content_attributes":{},"created_at":$createdAt,"conversation_id":$conv,
        "sender":{"id":3,"name":"Sara","avatar_url":"","type":"user"}$extra}"""

    fun conversation(id: Int = 5, status: String = "open") = """{"id":$id,"inbox_id":1,"contact_last_seen_at":0,"status":"$status"}"""
}

internal fun testConfig(server: TestServer, locale: String? = null) =
    HodhodConfig(baseUrl = server.url(), websiteToken = "WT", locale = locale, allowCleartext = true)

internal fun testRepo(
    server: TestServer,
    store: SessionStore = InMemorySessionStore(),
    cable: Boolean = false,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    cfg: HodhodConfig = testConfig(server),
    staleMs: Long = 15_000,
): DefaultHodhodRepository {
    val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    val wsHttp = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    val base = server.server.url("/")
    lateinit var repo: DefaultHodhodRepository
    val api = WidgetApi(base, "WT", http, { repo.uiLocale.value }, { store.get(SessionStore.AUTH_TOKEN) })
    repo = DefaultHodhodRepository(
        api, store, cfg, scope,
        cableFactory = if (cable) { token, onState, onEvent, onRe ->
            CableClient(wsHttp, cableUrl(server.url()), server.url(), token, scope, onState, onEvent, onRe, Backoff(50, 200), staleMs, 60_000)
        } else null,
        deviceLocale = { "en-US" },
    )
    return repo
}

/** Poll [cond] up to [timeoutMs]. */
internal suspend fun awaitUntil(timeoutMs: Long = 5_000, message: String = "condition", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        delay(20)
    }
    throw AssertionError("timeout waiting for $message")
}
