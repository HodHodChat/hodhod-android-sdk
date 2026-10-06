package chat.hodhod.sdk.internal

import chat.hodhod.sdk.Agent
import chat.hodhod.sdk.Attachment
import chat.hodhod.sdk.ConnectionState
import chat.hodhod.sdk.ContactInfo
import chat.hodhod.sdk.ConversationState
import chat.hodhod.sdk.ConversationStatus
import chat.hodhod.sdk.HodhodConfig
import chat.hodhod.sdk.HodhodException
import chat.hodhod.sdk.HodhodFlowEngine
import chat.hodhod.sdk.HodhodI18n
import chat.hodhod.sdk.HodhodRepository
import chat.hodhod.sdk.HodhodState
import chat.hodhod.sdk.HodhodUser
import chat.hodhod.sdk.IssueNotice
import chat.hodhod.sdk.Message
import chat.hodhod.sdk.MessageAttachment
import chat.hodhod.sdk.MessageStatus
import chat.hodhod.sdk.MessageType
import chat.hodhod.sdk.TicketActivity
import chat.hodhod.sdk.TicketForm
import chat.hodhod.sdk.TicketSummary
import chat.hodhod.sdk.TicketThread
import chat.hodhod.sdk.WidgetConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Date

/**
 * The real repository: mirrors widget/store/modules (conversation, conversationAttributes, contacts, agent) + ActionCableConnector.
 * Mutable state is guarded by [lock] and published to the StateFlows synchronously ([publish]), so `.value` is always current.
 */
internal class DefaultHodhodRepository(
    private val api: WidgetApi,
    private val store: SessionStore,
    private val config: HodhodConfig,
    private val scope: CoroutineScope,
    private val cableFactory: ((token: String, onState: (ConnectionState) -> Unit, onEvent: (String, JsonObject) -> Unit, onReconnected: () -> Unit) -> CableClient)?,
    private val deviceLocale: () -> String? = { java.util.Locale.getDefault().toLanguageTag() },
    private val log: (String) -> Unit = {},
) : HodhodRepository {

    // ---- published state ----
    private val _config = MutableStateFlow<WidgetConfig?>(null)
    private val _conversation = MutableStateFlow<ConversationState>(ConversationState.None)
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    private val _tickets = MutableStateFlow<List<TicketSummary>>(emptyList())
    private val _connection = MutableStateFlow(ConnectionState.IDLE)
    private val _state = MutableStateFlow<HodhodState>(HodhodState.Idle)
    private val _agents = MutableStateFlow<List<Agent>>(emptyList())
    private val _notices = MutableStateFlow<List<IssueNotice>>(emptyList())
    private val _contact = MutableStateFlow(ContactInfo(null, null, hasName = false, hasEmail = false, hasPhone = false))
    private val _unread = MutableStateFlow(0)
    private val _hasActive = MutableStateFlow(false)
    private val _locale = MutableStateFlow(HodhodI18n.resolve(config.locale, null, deviceLocale()))
    private val _ticketActivity = MutableSharedFlow<TicketActivity>(extraBufferCapacity = 16)

    override val widgetConfig: StateFlow<WidgetConfig?> = _config.asStateFlow()
    override val conversation: StateFlow<ConversationState> = _conversation.asStateFlow()
    override val messages: StateFlow<List<Message>> = _messages.asStateFlow()
    override val tickets: StateFlow<List<TicketSummary>> = _tickets.asStateFlow()
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    override val state: StateFlow<HodhodState> = _state.asStateFlow()
    override val agents: StateFlow<List<Agent>> = _agents.asStateFlow()
    override val issueNotices: StateFlow<List<IssueNotice>> = _notices.asStateFlow()
    override val contact: StateFlow<ContactInfo> = _contact.asStateFlow()
    override val unreadCount: StateFlow<Int> = _unread.asStateFlow()
    override val hasActiveConversation: StateFlow<Boolean> = _hasActive.asStateFlow()
    override val uiLocale: StateFlow<String> = _locale.asStateFlow()
    override val ticketActivity: SharedFlow<TicketActivity> = _ticketActivity
    /** Test hook: where the flow engine runs (main thread in production). */
    internal var flowScopeFactory: () -> CoroutineScope = { CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate) }
    internal var flowSchedulerFactory: () -> chat.hodhod.sdk.internal.flow.FlowScheduler = { chat.hodhod.sdk.internal.flow.HandlerFlowScheduler() }
    private val flowEngine by lazy { chat.hodhod.sdk.internal.flow.DefaultFlowEngine(FlowHostAdapter(), flowScopeFactory(), flowSchedulerFactory(), log) }
    override val flow: HodhodFlowEngine get() = flowEngine
    private var flowCreated = false

    // ---- guarded mutable state ----
    private val lock = Any()
    private var msgs: List<Message> = emptyList()
    private var convId: Int? = null
    private var status: ConversationStatus = ConversationStatus.UNKNOWN
    private var lastSeenAt: Long = 0
    private var hasMore = false
    private var loadingOlder = false
    private var typing = false
    private var endedId: Int? = null
    private var pendingConvAttrs: Map<String, Any?> = emptyMap()
    private var dismissed = setOf<Long>()
    private var ticketConvIds = setOf<Int>()
    private var pendingFlow: Map<String, Any?>? = null

    private val refreshMutex = Mutex()
    private val sendMutex = Mutex()
    private var cable: CableClient? = null
    private var cableToken: String? = null
    private var typingOffJob: Job? = null
    private var remoteTypingJob: Job? = null
    private var lastTypingOnAt = 0L
    private var userTypingOn = false
    private var transcriptSentAt = 0L

    private val clock: () -> Long = System::currentTimeMillis

    val authToken: String? get() = store.get(SessionStore.AUTH_TOKEN)

    // =================================================================================================================
    // Bootstrap / refresh
    // =================================================================================================================

    override suspend fun refresh(): Result<Unit> = refreshMutex.withLock { doRefresh() }

    private suspend fun doRefresh(): Result<Unit> {
        fatal?.let { return Result.failure(it) }
        if (_config.value == null) _state.value = HodhodState.Loading
        try {
            val root = api.postJson("api/v1/widget/config").json.obj() ?: throw HodhodException("server", "bad config response")
            val cfgObj = root.sub("website_channel_config")
            cfgObj?.str("auth_token")?.let { store.put(SessionStore.AUTH_TOKEN, it) }
            root.sub("contact")?.let { c ->
                c.str("pubsub_token")?.let { store.put(SessionStore.PUBSUB_TOKEN, it) }
                c.long("id")?.let { store.put(SessionStore.CONTACT_ID, it.toString()) }
                c.str("identifier")?.let { store.put(SessionStore.IDENTIFIER, it) }
                synchronized(lock) { _contact.value = Parsers.contactInfo(c, _contact.value) }
            }
            var extras: Parsers.Extras? = null
            if (!Parsers.hasAllExtras(root)) {
                extras = try {
                    Parsers.extrasFromHtml(api.getText("widget", mapOf("cw_conversation" to authToken)))
                } catch (e: HodhodException) {
                    log("widget html extras unavailable: ${e.code}")
                    null
                }
            }
            val cfg = Parsers.widgetConfig(root, extras) ?: throw HodhodException("server", "config missing")
            _config.value = cfg
            _locale.value = HodhodI18n.resolve(config.locale, cfg.locale, deviceLocale())
            _state.value = HodhodState.Ready
            publish()
            // The rest is best-effort: a failing sub-request must not fail the whole refresh.
            listOf(
                scope.async { runCatching { loadConversation(initial = true) } },
                scope.async { runCatching { loadContact() } },
                scope.async { runCatching { loadAgents() } },
                scope.async { runCatching { loadNotices() } },
                scope.async { runCatching { loadTicketsInternal() } },
            ).awaitAll()
            ensureCable()
            return Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HodhodException) {
            if (_config.value == null) _state.value = HodhodState.Failed(e.code, e.message)
            return Result.failure(e)
        }
    }

    private suspend fun loadContact() {
        val o = api.get("api/v1/widget/contact").json.obj() ?: return
        synchronized(lock) { _contact.value = Parsers.contactInfo(o, _contact.value) }
    }

    private suspend fun loadAgents() {
        val arr = api.get("api/v1/widget/inbox_members", auth = false).json.obj()?.list("payload") ?: return
        _agents.value = arr.mapNotNull { (it as? JsonObject)?.let(Parsers::agent) }
        publish()
    }

    private suspend fun loadNotices() {
        val arr = api.get("api/v1/widget/issue_notices", auth = false).json.obj()?.list("payload") ?: return
        val all = arr.mapNotNull { (it as? JsonObject)?.let { o -> o.long("id")?.let { id -> IssueNotice(id, o.str("message").orEmpty()) } } }
        _notices.value = all.filter { it.id !in dismissed && it.message.isNotBlank() }
    }

    private suspend fun loadTicketsInternal(): List<TicketSummary> {
        val arr = api.get("api/v1/widget/tickets").json.obj()?.list("payload") ?: return emptyList()
        val list = arr.mapNotNull { (it as? JsonObject)?.let(Parsers::ticket) }
        synchronized(lock) { ticketConvIds = list.mapNotNull { it.conversationId }.toSet() }
        _tickets.value = list
        return list
    }

    /** Load conversation attributes + latest messages (web: conversationAttributes/getAttributes + conversation/fetchOldConversations). */
    private suspend fun loadConversation(initial: Boolean) {
        val attrs = api.get("api/v1/widget/conversations").json.obj()
        val id = attrs?.int("id")
        if (id == null) {
            // No server-side current conversation. Keep an ended-but-not-yet-reset thread (the end screen) intact.
            synchronized(lock) {
                val ended = convId != null && status == ConversationStatus.RESOLVED && !lockToSingle()
                if (!ended && !hasPendingMessages()) clearConversationLocked()
            }
            publish()
            return
        }
        val page = api.get("api/v1/widget/messages").json.obj()
        val payload = page?.list("payload")?.mapNotNull { it as? JsonObject }.orEmpty()
        val seen = page?.sub("meta")?.long("contact_last_seen_at") ?: attrs.long("contact_last_seen_at") ?: 0
        synchronized(lock) {
            convId = id
            status = Parsers.conversationStatus(attrs.str("status"))
            lastSeenAt = seen
            val loaded = payload.map { Parsers.message(it, id) }.filterNot { isDeleted(it) }
            val pending = msgs.filter { it.status != MessageStatus.SENT }
            msgs = (loaded + pending).sortedWith(MESSAGE_ORDER)
            hasMore = payload.size >= PAGE_SIZE
            if (endedId == id) endedId = null
        }
        publish()
    }

    private fun lockToSingle() = _config.value?.lockToSingleConversation == true
    private fun hasPendingMessages() = msgs.any { it.status != MessageStatus.SENT }

    /** After a reconnect: fetch what we missed (web: syncLatestMessages + getAttributes). */
    private suspend fun resync() {
        val after = synchronized(lock) { msgs.lastOrNull { it.serverId != null }?.serverId }
        val attrs = api.get("api/v1/widget/conversations").json.obj()
        val id = attrs?.int("id")
        if (id == null || id != convId) {
            loadConversation(initial = false)
            return
        }
        val page = api.get("api/v1/widget/messages", mapOf("after" to after?.toString())).json.obj()
        val payload = page?.list("payload")?.mapNotNull { it as? JsonObject }.orEmpty()
        synchronized(lock) {
            status = Parsers.conversationStatus(attrs.str("status"))
            payload.map { Parsers.message(it, id) }.filterNot { isDeleted(it) }.forEach { upsertLocked(it) }
        }
        publish()
    }

    // =================================================================================================================
    // Identification (called by the runtime)
    // =================================================================================================================

    suspend fun identify(user: HodhodUser): Result<Unit> {
        if (_config.value == null || authToken == null) refresh().onFailure { return Result.failure(it) }
        return try {
            val body = jsonObjectOf(
                "identifier" to user.identifier,
                "identifier_hash" to user.identifierHash,
                "email" to user.email,
                "name" to user.name,
                "phone_number" to user.phone,
                "avatar_url" to user.avatarUrl,
                "custom_attributes" to user.customAttributes.takeIf { it.isNotEmpty() },
            )
            val resp = api.patchJson("api/v1/widget/contact/set_user", body).json.obj()
            val newToken = resp?.str("widget_auth_token")
            if (newToken != null) {
                // Different contact: new session. Forget the old conversation entirely, then re-bootstrap with the new token.
                store.put(SessionStore.AUTH_TOKEN, newToken)
                synchronized(lock) { clearConversationLocked(); endedId = null }
                _tickets.value = emptyList()
                publish()
                stopCable()
                refresh().onFailure { return Result.failure(it) }
            } else {
                resp?.let { o -> synchronized(lock) { _contact.value = Parsers.contactInfo(o, _contact.value) } }
                runCatching { loadContact() }
            }
            user.identifier?.let { store.put(SessionStore.IDENTIFIER, it) }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HodhodException) {
            Result.failure(e)
        }
    }

    suspend fun setContactCustomAttributes(attrs: Map<String, Any?>): Result<Unit> {
        if (authToken == null) refresh().onFailure { return Result.failure(it) }
        return call { api.patchJson("api/v1/widget/contact", jsonObjectOf("custom_attributes" to attrs)); Unit }
    }

    /** Local sign-out (session store cleared by the runtime). */
    fun resetSession() {
        stopCable()
        synchronized(lock) { clearConversationLocked(); endedId = null; pendingConvAttrs = emptyMap(); ticketConvIds = emptySet() }
        _tickets.value = emptyList()
        _contact.value = ContactInfo(null, null, hasName = false, hasEmail = false, hasPhone = false)
        pendingFlow = null
        if (flowCreated) flowEngine.reset()
        _state.value = HodhodState.Idle
        publish()
    }

    fun shutdown() {
        stopCable()
    }

    @Volatile private var fatal: HodhodException? = null

    fun failBootstrap(e: HodhodException) {
        fatal = e
        _state.value = HodhodState.Failed(e.code, e.message)
    }

    /** App came to the foreground: catch up on what was missed while the socket was closed, then reconnect (no push: the socket is the only live channel). */
    fun onForeground() {
        foreground = true
        if (_state.value is HodhodState.Failed && fatal == null) {
            scope.launch { refresh() } // bootstrap failed earlier (offline start): try again now that the user is here
            return
        }
        if (_state.value != HodhodState.Ready) return
        scope.launch {
            runCatching { resync() }
            ensureCable()
            cable?.takeIf { _connection.value != ConnectionState.CONNECTED }?.reconnectNow()
        }
    }

    @Volatile private var foreground = false

    /**
     * The device (re)gained a network. Offline start (state Failed): bootstrap again, so the error screen goes away by itself.
     * Running session (state Ready): re-sync what was missed and reconnect the socket right away instead of waiting for the backoff.
     */
    fun onNetworkAvailable() {
        if (fatal != null) return
        scope.launch {
            when (_state.value) {
                is HodhodState.Failed -> refresh()
                HodhodState.Ready -> if (foreground && _connection.value != ConnectionState.CONNECTED) {
                    runCatching { resync() }
                    ensureCable()
                    cable?.takeIf { _connection.value != ConnectionState.CONNECTED }?.reconnectNow()
                }
                else -> Unit
            }
        }
    }

    fun onBackground() {
        foreground = false
        stopCable()
        if (flowCreated) (flowEngine as? chat.hodhod.sdk.internal.flow.DefaultFlowEngine)?.flushAnalytics()
    }

    // =================================================================================================================
    // Messages
    // =================================================================================================================

    override suspend fun sendMessage(text: String, attachments: List<Attachment>, clientId: String): Result<Message> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return Result.failure(HodhodException("invalid_param", "empty message"))
        if (authToken == null || _config.value == null) refresh().onFailure { return Result.failure(it) }
        val now = clock() / 1000
        val out = ArrayList<Pair<Message, Attachment?>>()
        if (trimmed.isNotEmpty()) out += optimistic(clientId, trimmed, emptyList(), now) to null
        attachments.forEach { a ->
            val id = if (trimmed.isEmpty() && out.isEmpty()) clientId else HodhodRepository.newClientId()
            out += optimistic(id, null, listOf(localAttachment(a)), now) to a
        }
        synchronized(lock) { out.forEach { upsertLocked(it.first) } }
        publish()
        var firstFailure: Result<Message>? = null
        var last: Result<Message>? = null
        for ((m, a) in out) {
            val r = deliver(m, a)
            last = r
            if (r.isFailure && firstFailure == null) firstFailure = r
        }
        return firstFailure ?: last!!
    }

    override suspend fun retry(clientId: String): Result<Message> {
        val m = synchronized(lock) { msgs.firstOrNull { it.key == clientId && it.status == MessageStatus.FAILED } }
            ?: return Result.failure(HodhodException("not_found"))
        val file = m.attachments.firstOrNull()?.localFile
        val attachment = file?.let { Attachment(it, it.name, m.attachments.first().contentType ?: "application/octet-stream") }
        synchronized(lock) { upsertLocked(m.copy(status = MessageStatus.SENDING)) }
        publish()
        return deliver(m, attachment)
    }

    override fun discardFailed(clientId: String) {
        synchronized(lock) { msgs = msgs.filterNot { it.key == clientId && it.status == MessageStatus.FAILED } }
        publish()
    }

    private suspend fun deliver(m: Message, attachment: Attachment?, flowBody: Map<String, Any?>? = null): Result<Message> = sendMutex.withLock {
        val key = m.key
        // The web widget sends custom attributes / labels only with the first message of a new conversation.
        val first = synchronized(lock) { convId == null }
        try {
            val resp = if (attachment != null) {
                val parts = mutableListOf<FormPart>(
                    FormPart.File("message[attachments][]", attachment),
                    FormPart.Text("message[timestamp]", Date().toString()),
                    FormPart.Text("message[echo_id]", key),
                )
                if (first) pendingConvAttrs.forEach { (k, v) -> parts += FormPart.Text("custom_attributes[$k]", v.toString()) }
                api.postMultipart("api/v1/widget/messages", parts)
            } else {
                val body = jsonObjectOf(
                    "message" to jsonObjectOf("content" to m.content, "timestamp" to Date().toString(), "echo_id" to key, "reply_to" to m.replyToId),
                    "custom_attributes" to if (first) pendingConvAttrs.takeIf { it.isNotEmpty() } else null,
                    // Handoff body of the chatbot flow (live or ticket); the server validates it and never rejects the message for it.
                    "flow" to (flowBody ?: pendingFlow),
                )
                api.postJson("api/v1/widget/messages", body)
            }
            val o = resp.json.obj() ?: throw HodhodException("server", "bad message response")
            val serverMessage = Parsers.message(o).copy(clientId = key, status = MessageStatus.SENT)
            synchronized(lock) {
                if (convId == null) {
                    convId = serverMessage.conversationId
                    status = ConversationStatus.OPEN
                    pendingConvAttrs = emptyMap()
                    pendingFlow = null
                    if (endedId == convId) endedId = null
                } else if (status == ConversationStatus.RESOLVED) {
                    status = ConversationStatus.OPEN // server re-opens a resolved conversation on a visitor message
                }
                upsertLocked(serverMessage)
            }
            publish()
            if (first) scope.launch { runCatching { loadConversation(initial = false) } }
            Result.success(serverMessage)
        } catch (e: CancellationException) {
            synchronized(lock) { upsertLocked(m.copy(status = MessageStatus.FAILED)) }
            publish()
            throw e
        } catch (e: HodhodException) {
            synchronized(lock) { upsertLocked(m.copy(status = MessageStatus.FAILED)) }
            publish()
            val code = if (e.httpStatus == 403 && e.code == "forbidden") "conversation_resolved" else e.code
            Result.failure(if (code != e.code) HodhodException(code, e.message, e.httpStatus) else e)
        }
    }

    private fun optimistic(clientId: String, text: String?, atts: List<MessageAttachment>, now: Long) = Message(
        id = clientId, serverId = null, clientId = clientId, conversationId = convId, content = text,
        messageType = MessageType.INCOMING, contentType = null, contentAttributes = emptyMap(), createdAt = now,
        attachments = atts, sender = null, status = MessageStatus.SENDING,
    )

    private fun localAttachment(a: Attachment) = MessageAttachment(
        id = null, fileType = fileTypeOf(a.mimeType), dataUrl = null, thumbUrl = null, fileSize = a.file.length(),
        extension = a.fileName.substringAfterLast('.', ""), contentType = a.mimeType, width = null, height = null,
        transcribedText = null, localFile = a.file,
    )

    override suspend fun loadOlder(): Result<Unit> {
        val before = synchronized(lock) {
            if (loadingOlder || !hasMore || convId == null) return Result.success(Unit)
            loadingOlder = true
            msgs.firstOrNull { it.serverId != null }?.serverId
        }
        publish()
        return try {
            val page = api.get("api/v1/widget/messages", mapOf("before" to before?.toString())).json.obj()
            val payload = page?.list("payload")?.mapNotNull { it as? JsonObject }.orEmpty()
            synchronized(lock) {
                loadingOlder = false
                if (payload.isEmpty()) hasMore = false
                payload.map { Parsers.message(it, convId) }.filterNot { isDeleted(it) }.forEach { upsertLocked(it) }
                if (payload.size < PAGE_SIZE) hasMore = false
            }
            publish()
            Result.success(Unit)
        } catch (e: CancellationException) {
            synchronized(lock) { loadingOlder = false }; publish(); throw e
        } catch (e: HodhodException) {
            synchronized(lock) { loadingOlder = false }; publish()
            Result.failure(e)
        }
    }

    override suspend fun markRead(): Result<Unit> {
        val has = synchronized(lock) {
            lastSeenAt = clock() / 1000
            convId != null
        }
        publish()
        if (!has) return Result.success(Unit)
        return call { api.postJson("api/v1/widget/conversations/update_last_seen", jsonObjectOf("contact_last_seen_at" to clock() / 1000.0)); Unit }
    }

    override fun setTyping(on: Boolean) {
        if (synchronized(lock) { convId == null }) return
        val now = clock()
        if (on) {
            typingOffJob?.cancel()
            typingOffJob = scope.launch { delay(TYPING_IDLE_MS); sendTyping(false) }
            if (!userTypingOn || now - lastTypingOnAt > TYPING_REPEAT_MS) {
                userTypingOn = true
                lastTypingOnAt = now
                scope.launch { sendTyping(true) }
            }
        } else if (userTypingOn) {
            typingOffJob?.cancel()
            scope.launch { sendTyping(false) }
        }
    }

    private suspend fun sendTyping(on: Boolean) {
        if (!on) userTypingOn = false
        runCatching { api.postJson("api/v1/widget/conversations/toggle_typing", jsonObjectOf("typing_status" to if (on) "on" else "off")) }
    }

    // =================================================================================================================
    // End / reset / CSAT / transcript
    // =================================================================================================================

    override suspend fun endConversation(): Result<Unit> {
        if (synchronized(lock) { convId == null }) return Result.failure(HodhodException("no_conversation"))
        try {
            api.get("api/v1/widget/conversations/toggle_status")
        } catch (e: CancellationException) {
            throw e
        } catch (e: HodhodException) {
            // 404 = the agent already closed it: exactly the outcome we wanted (web: resolveConversation).
            if (e.httpStatus != 404) return Result.failure(e)
        }
        synchronized(lock) { status = ConversationStatus.RESOLVED }
        publish()
        return Result.success(Unit)
    }

    override fun resetConversation() {
        synchronized(lock) {
            if (convId != null) endedId = convId
            clearConversationLocked()
            pendingConvAttrs = emptyMap()
            pendingFlow = null
        }
        publish()
        // Web `resetConversation` also clears the saved flow session so the visitor starts the flow over.
        if (flowCreated) flowEngine.reset()
    }

    override suspend fun submitCsat(rating: Int, feedback: String?): Result<Unit> {
        if (rating !in 1..5) return Result.failure(HodhodException("invalid_param", "rating must be 1..5"))
        var csat = findCsatMessage()
        var tries = 0
        while (csat == null && tries < 4) {
            // The server creates the survey message asynchronously after the conversation is resolved.
            delay(800)
            runCatching { resync() }
            csat = findCsatMessage()
            tries++
        }
        val target = csat ?: return Result.failure(HodhodException("not_found", "no csat message"))
        val id = target.serverId ?: return Result.failure(HodhodException("not_found"))
        val response = mapOf("rating" to rating, "feedback_message" to feedback?.trim()?.takeIf { it.isNotEmpty() })
        return try {
            api.patchJson(
                "api/v1/widget/messages/$id",
                jsonObjectOf("message" to jsonObjectOf("submitted_values" to jsonObjectOf("csat_survey_response" to response.filterValues { it != null }))),
            )
            synchronized(lock) {
                val updated = target.copy(contentAttributes = target.contentAttributes + mapOf(
                    "csat_survey_response" to response.filterValues { it != null },
                    "submitted_values" to mapOf("csat_survey_response" to response.filterValues { it != null }),
                ))
                upsertLocked(updated)
            }
            publish()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HodhodException) {
            Result.failure(e)
        }
    }

    private fun findCsatMessage(): Message? = synchronized(lock) { msgs.lastOrNull { it.contentType == "input_csat" } }

    override suspend fun sendTranscriptByEmail(): Result<Unit> {
        if (clock() - transcriptSentAt < TRANSCRIPT_COOLDOWN_MS) return Result.failure(HodhodException("rate_limited"))
        val r = call { api.postJson("api/v1/widget/conversations/transcript"); Unit }
        if (r.isSuccess) transcriptSentAt = clock()
        return r
    }

    // =================================================================================================================
    // Tickets / pre-chat
    // =================================================================================================================

    override suspend fun createTicket(form: TicketForm): Result<TicketSummary> {
        val subject = form.subject.trim()
        val description = form.description.trim()
        val cfg = _config.value
        if (subject.isEmpty()) return Result.failure(HodhodException("subject_required"))
        if (subject.length > 120) return Result.failure(HodhodException("subject_too_long"))
        if (description.isEmpty()) return Result.failure(HodhodException("description_required"))
        if (description.length > 5000) return Result.failure(HodhodException("description_too_long"))
        val email = form.email?.trim()?.takeIf { it.isNotEmpty() }
        if (cfg != null && cfg.ticketForm.email == "required" && email == null && !_contact.value.hasEmail) {
            return Result.failure(HodhodException("email_required"))
        }
        if (cfg != null && cfg.ticketForm.category == "required" && form.categoryId == null) {
            return Result.failure(HodhodException("category_required"))
        }
        if (authToken == null || cfg == null) refresh().onFailure { return Result.failure(it) }
        val parts = mutableListOf<FormPart>(
            FormPart.Text("ticket[subject]", subject),
            FormPart.Text("ticket[description]", description),
        )
        form.categoryId?.let { parts += FormPart.Text("ticket[category_id]", it.toString()) }
        form.name?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += FormPart.Text("ticket[contact][name]", it) }
        email?.let { parts += FormPart.Text("ticket[contact][email]", it) }
        form.website?.takeIf { it.isNotEmpty() }?.let { parts += FormPart.Text("ticket[website]", it) }
        if (cfg?.ticketForm?.attachments != false) {
            form.attachments.take(5).forEach { parts += FormPart.File("ticket[attachments][]", it) }
        }
        parts += FormPart.Text("message[referer_url]", "")
        return try {
            val o = api.postMultipart("api/v1/widget/tickets", parts).json.obj() ?: throw HodhodException("server")
            val ticket = o.sub("ticket")?.let(Parsers::ticket) ?: throw HodhodException("server", "bad ticket response")
            o.sub("contact")?.let { c -> synchronized(lock) { _contact.value = Parsers.contactInfo(c, _contact.value) } }
            // The cable can deliver the ticket conversation's first events (message.created) before this HTTP response
            // registered its id; such early events were taken for a chat. Drop that phantom conversation now.
            val phantom = synchronized(lock) {
                ticket.conversationId?.let { ticketConvIds = ticketConvIds + it }
                (ticket.conversationId != null && convId == ticket.conversationId).also { if (it) clearConversationLocked() }
            }
            _tickets.value = listOf(ticket) + _tickets.value.filterNot { it.number == ticket.number }
            if (phantom) publish()
            Result.success(ticket)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HodhodException) {
            Result.failure(e)
        }
    }

    override suspend fun loadTickets(): Result<List<TicketSummary>> {
        if (authToken == null) refresh().onFailure { return Result.failure(it) }
        return call { loadTicketsInternal() }
    }

    override suspend fun loadTicket(number: Int): Result<TicketThread> = call {
        val o = api.get("api/v1/widget/tickets/$number").json.obj() ?: throw HodhodException("server")
        val ticket = o.sub("ticket")?.let(Parsers::ticket) ?: throw HodhodException("server")
        val messages = o.list("payload")?.mapNotNull { (it as? JsonObject)?.let { m -> Parsers.message(m, ticket.conversationId) } }.orEmpty()
            .filterNot { isDeleted(it) }.sortedWith(MESSAGE_ORDER)
        TicketThread(ticket, messages)
    }

    override suspend fun replyToTicket(number: Int, text: String, attachments: List<Attachment>): Result<Message> {
        val content = text.trim()
        if (content.isEmpty() && attachments.isEmpty()) return Result.failure(HodhodException("description_required"))
        if (content.length > 5000) return Result.failure(HodhodException("description_too_long"))
        val parts = mutableListOf<FormPart>(FormPart.Text("ticket[content]", content))
        attachments.take(5).forEach { parts += FormPart.File("ticket[attachments][]", it) }
        return call {
            val o = api.postMultipart("api/v1/widget/tickets/$number/reply", parts).json.obj() ?: throw HodhodException("server")
            val ticket = o.sub("ticket")?.let(Parsers::ticket)
            if (ticket != null) _tickets.value = _tickets.value.map { if (it.number == number) ticket else it }
            Parsers.message(o, ticket?.conversationId).copy(clientId = null)
        }
    }

    override suspend fun submitPreChat(fields: Map<String, String>): Result<Unit> {
        if (authToken == null || _config.value == null) refresh().onFailure { return Result.failure(it) }
        val form = _config.value?.preChatForm
        val contactAttrs = mutableMapOf<String, Any?>()
        val convAttrs = mutableMapOf<String, Any?>()
        fields.forEach { (name, value) ->
            if (name in STANDARD_FIELDS) return@forEach
            val def = form?.fields?.firstOrNull { it.name == name }
            if (def?.fieldType == "conversation_attribute") convAttrs[name] = value else contactAttrs[name] = value
        }
        val body = jsonObjectOf(
            "email" to fields["emailAddress"]?.trim()?.takeIf { it.isNotEmpty() },
            "name" to fields["fullName"]?.trim()?.takeIf { it.isNotEmpty() },
            "phone_number" to fields["phoneNumber"]?.trim()?.takeIf { it.isNotEmpty() },
            "custom_attributes" to contactAttrs.takeIf { it.isNotEmpty() },
        )
        return call {
            api.patchJson("api/v1/widget/contact", body)
            synchronized(lock) { pendingConvAttrs = pendingConvAttrs + convAttrs }
            loadContact()
            Unit
        }
    }

    override fun dismissIssueNotice(id: Long) {
        dismissed = dismissed + id
        _notices.value = _notices.value.filterNot { it.id == id }
    }

    // =================================================================================================================
    // Websocket
    // =================================================================================================================

    private fun ensureCable() {
        val factory = cableFactory ?: return
        val token = store.get(SessionStore.PUBSUB_TOKEN) ?: return
        if (cable != null && cableToken == token) {
            cable?.start()
            return
        }
        stopCable()
        cableToken = token
        cable = factory(token, { _connection.value = it }, ::onCableEvent) { scope.launch { runCatching { resync() } } }.also { it.start() }
    }

    private fun stopCable() {
        cable?.stop()
        cable = null
        cableToken = null
        _connection.value = ConnectionState.IDLE
    }

    private fun onCableEvent(event: String, data: JsonObject) {
        when (event) {
            "message.created", "message.updated" -> onMessageEvent(data, event == "message.created")
            "conversation.typing_on" -> onTypingOn(data)
            "conversation.typing_off" -> setRemoteTyping(false)
            "conversation.status_changed" -> onStatusChanged(data)
            "conversation.created" -> scope.launch { runCatching { loadConversation(initial = false) } }
            "presence.update" -> onPresence(data)
            "contact.merged" -> data.str("pubsub_token")?.let {
                store.put(SessionStore.PUBSUB_TOKEN, it)
                ensureCable()
            }
            "contact.deleted" -> log("contact deleted on server")
            else -> Unit
        }
    }

    private fun onMessageEvent(data: JsonObject, created: Boolean) {
        if (data.bool("private") == true) return
        val msg = Parsers.message(data)
        val cid = msg.conversationId
        var isTicket = false
        val accept = synchronized(lock) {
            when {
                cid != null && cid == endedId -> false // late event of a conversation the widget already left
                cid != null && cid in ticketConvIds -> { isTicket = true; false }
                convId != null && cid != null && cid != convId -> false // another conversation
                else -> true
            }
        }
        if (isTicket) {
            val number = _tickets.value.firstOrNull { it.conversationId == cid }?.number
            if (number != null) _ticketActivity.tryEmit(TicketActivity(number, msg))
            scope.launch { runCatching { loadTicketsInternal() } }
            return
        }
        if (!accept) return
        synchronized(lock) {
            if (isDeleted(msg)) {
                msgs = msgs.filterNot { it.serverId == msg.serverId }
            } else {
                if (convId == null && cid != null) {
                    convId = cid
                    status = ConversationStatus.OPEN
                }
                val merged = if (!created) mergeUpdate(msg) else msg
                upsertLocked(merged)
            }
        }
        publish()
    }

    private fun mergeUpdate(update: Message): Message {
        val old = msgs.firstOrNull { it.serverId == update.serverId } ?: return update
        return update.copy(
            clientId = old.clientId, attachments = update.attachments.ifEmpty { old.attachments },
            contentAttributes = old.contentAttributes + update.contentAttributes, sender = update.sender ?: old.sender,
        )
    }

    private fun onTypingOn(data: JsonObject) {
        val activeId = synchronized(lock) { convId }
        val typingConv = data.sub("conversation")?.int("id")
        if (data.bool("is_private") == true) return
        if (typingConv != null && activeId != null && typingConv != activeId) return
        setRemoteTyping(true)
    }

    private fun setRemoteTyping(on: Boolean) {
        remoteTypingJob?.cancel()
        synchronized(lock) { typing = on }
        publish()
        if (on) remoteTypingJob = scope.launch { delay(REMOTE_TYPING_TIMEOUT_MS); synchronized(lock) { typing = false }; publish() }
    }

    private fun onStatusChanged(data: JsonObject) {
        val id = data.int("id") ?: return
        val newStatus = Parsers.conversationStatus(data.str("status"))
        var reopen = false
        synchronized(lock) {
            if (id == endedId && newStatus != ConversationStatus.RESOLVED) {
                // Agent re-opened a conversation the widget had left: it becomes current again.
                endedId = null
                reopen = true
            } else if (id == convId) {
                status = newStatus
                if (newStatus == ConversationStatus.RESOLVED) typing = false
            } else if (convId == null && newStatus != ConversationStatus.RESOLVED) {
                convId = id
                status = newStatus
            }
        }
        publish()
        if (reopen) scope.launch { runCatching { loadConversation(initial = false) } }
    }

    private fun onPresence(data: JsonObject) {
        val users = data.sub("users") ?: return
        _agents.value = _agents.value.map { a -> users.str(a.id.toString())?.let { a.copy(availability = it) } ?: a.copy(availability = "offline") }
    }

    // =================================================================================================================
    // Internals
    // =================================================================================================================

    private fun isDeleted(m: Message) = m.contentAttributes["deleted"] == true

    private fun clearConversationLocked() {
        msgs = emptyList(); convId = null; status = ConversationStatus.UNKNOWN; lastSeenAt = 0; hasMore = false
        loadingOlder = false; typing = false
    }

    /** Insert or replace by server id / client id, keeping chronological order (web: pushMessageToConversation). */
    private fun upsertLocked(m: Message) {
        val list = msgs.toMutableList()
        val idx = list.indexOfFirst { existing ->
            (m.serverId != null && existing.serverId == m.serverId) ||
                (m.clientId != null && existing.clientId == m.clientId && !(m.serverId != null && existing.serverId != null)) ||
                (m.clientId == null && existing.clientId == null && existing.id == m.id)
        }
        val merged = if (idx >= 0) m.copy(clientId = m.clientId ?: list[idx].clientId).also { list[idx] = it } else m.also { list += it }
        // Race: a reload (conversation.created) can put the server copy in the list while the optimistic copy is still pending;
        // when the send response then carries the client id, the optimistic leftover must go (two items would share one key).
        if (merged.serverId != null && merged.clientId != null) {
            list.removeAll { it !== merged && it.serverId == null && it.clientId == merged.clientId }
        }
        msgs = list.sortedWith(MESSAGE_ORDER)
    }

    /**
     * The server can store two different messages with the same `echo_id` (a ticket conversion copies the visitor's message with its
     * content attributes). [Message.key] must stay unique for the UI lists, so later duplicates fall back to their own id.
     */
    private fun uniqueKeys(list: List<Message>): List<Message> {
        val seen = HashSet<String>()
        var changed = false
        val out = list.map { m ->
            if (seen.add(m.key)) m else { changed = true; m.copy(clientId = null).also { seen.add(it.key) } }
        }
        return if (changed) out else list
    }

    private fun publish() = synchronized(lock) {
        val cfg = _config.value
        val cid = convId
        _messages.value = uniqueKeys(msgs)
        val unread = unreadLocked()
        val state: ConversationState = when {
            cid == null -> ConversationState.None
            status == ConversationStatus.RESOLVED && cfg?.lockToSingleConversation != true ->
                ConversationState.Ended(cid, csatSubmittedLocked(), cfg?.csatSurveyEnabled == true)
            else -> ConversationState.Active(
                cid, status, assigneeLocked(), hasMore, unread, typing, loadingOlder,
            )
        }
        _conversation.value = state
        _unread.value = unread
        _hasActive.value = msgs.isNotEmpty() && state !is ConversationState.Ended
    }

    /** Web getters.getUnreadMessageCount: agent messages newer than contact_last_seen_at (all of them when never seen). */
    private fun unreadLocked(): Int = msgs.count { it.messageType == MessageType.OUTGOING && (lastSeenAt <= 0 || it.createdAt > lastSeenAt) }

    private fun assigneeLocked(): Agent? = msgs.lastOrNull { it.messageType == MessageType.OUTGOING && it.sender?.type == "user" }?.sender?.let {
        it.id?.let { id -> Agent(id, it.name.orEmpty(), it.avatarUrl, null) }
    }

    private fun csatSubmittedLocked(): Boolean {
        val m = msgs.lastOrNull { it.contentType == "input_csat" } ?: return false
        val direct = (m.contentAttributes["csat_survey_response"] as? Map<*, *>)?.get("rating")
        val submitted = ((m.contentAttributes["submitted_values"] as? Map<*, *>)?.get("csat_survey_response") as? Map<*, *>)?.get("rating")
        return direct != null || submitted != null
    }

    private suspend fun <T> call(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: HodhodException) {
        Result.failure(e)
    }

    /** What the flow engine needs from this repository. */
    private inner class FlowHostAdapter : chat.hodhod.sdk.internal.flow.FlowHost {
        init {
            flowCreated = true
        }

        override val config get() = _config.value
        override val agentsOnline get() = _agents.value.any { it.availability == "online" }
        override val contact get() = _contact.value
        override val locale get() = _locale.value
        override val store get() = this@DefaultHodhodRepository.store

        override suspend fun fetchFlow(): JsonObject? = try {
            api.get("api/v1/widget/chatbot_flow").json.obj()?.sub("payload")
        } catch (e: HodhodException) {
            log("flow fetch failed: ${e.code}")
            null
        }

        override suspend fun runWebhook(nodeId: String, sessionId: String, variables: Map<String, Any?>): JsonObject? = try {
            api.postJson(
                "api/v1/widget/chatbot_flow/webhook",
                jsonObjectOf("node_id" to nodeId, "session_id" to sessionId, "variables" to variables),
            ).json.obj()?.sub("payload")
        } catch (e: HodhodException) {
            null
        }

        override suspend fun postEvents(body: JsonObject): Pair<Int?, kotlinx.serialization.json.JsonElement?> = try {
            val r = api.postJson("api/v1/widget/chatbot_flow_events", body, auth = false)
            r.code to r.json
        } catch (e: HodhodException) {
            e.httpStatus to null
        }

        override suspend fun sendHandoff(content: String, flow: Map<String, Any?>): Result<Any?> {
            val clientId = HodhodRepository.newClientId()
            if (authToken == null || _config.value == null) refresh().onFailure { return Result.failure(it) }
            val m = optimistic(clientId, content, emptyList(), clock() / 1000)
            synchronized(lock) { upsertLocked(m) }
            publish()
            val r = deliver(m, null, flow)
            if (r.isFailure) discardFailed(clientId)
            return r.map { it.conversationId }
        }

        override fun setPendingFlow(flow: Map<String, Any?>?) {
            pendingFlow = flow
        }
    }

    companion object {
        const val PAGE_SIZE = 20
        const val TYPING_IDLE_MS = 5_000L
        const val TYPING_REPEAT_MS = 4_000L
        const val REMOTE_TYPING_TIMEOUT_MS = 30_000L
        const val TRANSCRIPT_COOLDOWN_MS = 30_000L
        val STANDARD_FIELDS = setOf("emailAddress", "fullName", "phoneNumber")

        /** Chronological; same-second ties by server id, optimistic messages last. */
        val MESSAGE_ORDER: Comparator<Message> = compareBy<Message>({ it.createdAt }, { it.serverId ?: Long.MAX_VALUE })

        fun fileTypeOf(mime: String): String = when {
            mime.startsWith("image/") -> "image"
            mime.startsWith("audio/") -> "audio"
            mime.startsWith("video/") -> "video"
            else -> "file"
        }
    }
}
