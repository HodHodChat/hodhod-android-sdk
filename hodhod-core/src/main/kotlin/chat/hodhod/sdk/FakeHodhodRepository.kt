package chat.hodhod.sdk

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * In-memory [HodhodRepository] for Compose previews, UI tests and demos. Behaves like the real one (optimistic send, ended state, reset,
 * tickets) without any network. Drive it from tests with the `simulate*` helpers.
 */
public class FakeHodhodRepository(
    config: WidgetConfig? = sampleConfig(),
    initialMessages: List<Message> = emptyList(),
    initialTickets: List<TicketSummary> = emptyList(),
    initialAgents: List<Agent> = sampleAgents(),
    startState: HodhodState = if (config != null) HodhodState.Ready else HodhodState.Idle,
) : HodhodRepository {
    private val _config = MutableStateFlow(config)
    private val _messages = MutableStateFlow(initialMessages)
    private val _conversation = MutableStateFlow<ConversationState>(
        if (initialMessages.isEmpty()) ConversationState.None
        else ConversationState.Active(1, ConversationStatus.OPEN, null, hasMore = false, unreadCount = 0, agentTyping = false),
    )
    private val _tickets = MutableStateFlow(initialTickets)
    private val _connection = MutableStateFlow(ConnectionState.CONNECTED)
    private val _state = MutableStateFlow(startState)
    private val _agents = MutableStateFlow(initialAgents)
    private val _notices = MutableStateFlow<List<IssueNotice>>(emptyList())
    private val _contact = MutableStateFlow(ContactInfo(1, null, hasName = false, hasEmail = false, hasPhone = false))
    private val _unread = MutableStateFlow(0)
    private val _hasActive = MutableStateFlow(initialMessages.isNotEmpty())
    private var nextId = 1000L
    private var ticketNo = initialTickets.maxOfOrNull { it.number } ?: 0

    /** When true the next [sendMessage] fails (status FAILED, retryable). */
    public var failNextSend: Boolean = false

    /** Artificial latency applied to suspend calls. */
    public var latencyMs: Long = 0

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
    private val _locale = MutableStateFlow("en")
    override val uiLocale: StateFlow<String> = _locale.asStateFlow()
    override val ticketActivity: SharedFlow<TicketActivity> = MutableSharedFlow()
    override val flow: HodhodFlowEngine = chat.hodhod.sdk.internal.flow.NoopFlowEngine

    private suspend fun delayIfNeeded() {
        if (latencyMs > 0) kotlinx.coroutines.delay(latencyMs)
    }

    override suspend fun refresh(): Result<Unit> {
        delayIfNeeded()
        if (_config.value != null) _state.value = HodhodState.Ready
        return Result.success(Unit)
    }

    override suspend fun sendMessage(text: String, attachments: List<Attachment>, clientId: String): Result<Message> {
        if (text.isBlank() && attachments.isEmpty()) return Result.failure(HodhodException("invalid_param"))
        val temp = outgoing(text, clientId, MessageStatus.SENDING)
        _messages.update { it + temp }
        if (_conversation.value is ConversationState.None) activate()
        delayIfNeeded()
        if (failNextSend) {
            failNextSend = false
            val failed = temp.copy(status = MessageStatus.FAILED)
            replace(clientId, failed)
            return Result.failure(HodhodException("network"))
        }
        val sent = temp.copy(id = (nextId++).toString(), serverId = nextId, status = MessageStatus.SENT)
        replace(clientId, sent)
        return Result.success(sent)
    }

    override suspend fun retry(clientId: String): Result<Message> {
        val m = _messages.value.firstOrNull { it.key == clientId && it.status == MessageStatus.FAILED }
            ?: return Result.failure(HodhodException("not_found"))
        _messages.update { list -> list.filterNot { it.key == clientId } }
        return sendMessage(m.content.orEmpty(), emptyList(), clientId)
    }

    override fun discardFailed(clientId: String) {
        _messages.update { list -> list.filterNot { it.key == clientId && it.status == MessageStatus.FAILED } }
    }

    override suspend fun loadOlder(): Result<Unit> = Result.success(Unit)

    override suspend fun markRead(): Result<Unit> {
        _unread.value = 0
        (_conversation.value as? ConversationState.Active)?.let { _conversation.value = it.copy(unreadCount = 0) }
        return Result.success(Unit)
    }

    override fun setTyping(on: Boolean) {}

    override suspend fun endConversation(): Result<Unit> {
        val cur = _conversation.value as? ConversationState.Active ?: return Result.failure(HodhodException("no_conversation"))
        val cfg = _config.value
        if (cfg?.lockToSingleConversation == true) {
            _conversation.value = cur.copy(status = ConversationStatus.RESOLVED)
        } else {
            _conversation.value = ConversationState.Ended(cur.id, csatSubmitted = false, csatEnabled = cfg?.csatSurveyEnabled == true)
            _hasActive.value = false
        }
        return Result.success(Unit)
    }

    override suspend fun submitCsat(rating: Int, feedback: String?): Result<Unit> {
        val cur = _conversation.value as? ConversationState.Ended ?: return Result.failure(HodhodException("no_conversation"))
        _conversation.value = cur.copy(csatSubmitted = true)
        return Result.success(Unit)
    }

    override suspend fun sendTranscriptByEmail(): Result<Unit> = Result.success(Unit)

    override suspend fun createTicket(form: TicketForm): Result<TicketSummary> {
        if (form.subject.isBlank()) return Result.failure(HodhodException("subject_required"))
        if (form.description.isBlank()) return Result.failure(HodhodException("description_required"))
        val now = System.currentTimeMillis() / 1000
        val t = TicketSummary(++ticketNo, form.subject, TicketStatus.OPEN, _config.value?.ticketCategories?.firstOrNull { it.id == form.categoryId }, now, now, null, 100 + ticketNo)
        _tickets.update { listOf(t) + it }
        return Result.success(t)
    }

    override suspend fun loadTickets(): Result<List<TicketSummary>> = Result.success(_tickets.value)

    override suspend fun loadTicket(number: Int): Result<TicketThread> {
        val t = _tickets.value.firstOrNull { it.number == number } ?: return Result.failure(HodhodException("not_found"))
        return Result.success(TicketThread(t, ticketMessages[number].orEmpty()))
    }

    override suspend fun replyToTicket(number: Int, text: String, attachments: List<Attachment>): Result<Message> {
        val m = outgoing(text, HodhodRepository.newClientId(), MessageStatus.SENT)
        ticketMessages[number] = ticketMessages[number].orEmpty() + m
        return Result.success(m)
    }

    override suspend fun submitPreChat(fields: Map<String, String>): Result<Unit> {
        _contact.update {
            it.copy(
                hasEmail = it.hasEmail || fields.containsKey("emailAddress"),
                hasName = it.hasName || fields.containsKey("fullName"),
                hasPhone = it.hasPhone || fields.containsKey("phoneNumber"),
                email = fields["emailAddress"] ?: it.email,
                name = fields["fullName"] ?: it.name,
                preChatSatisfied = true,
            )
        }
        return Result.success(Unit)
    }

    override fun resetConversation() {
        _messages.value = emptyList()
        _conversation.value = ConversationState.None
        _hasActive.value = false
        _unread.value = 0
    }

    override fun dismissIssueNotice(id: Long) {
        _notices.update { l -> l.filterNot { it.id == id } }
    }

    // ---- test/preview helpers ----

    private val ticketMessages = mutableMapOf<Int, List<Message>>()

    /** Deliver an agent message as if it arrived over the websocket. */
    public fun simulateAgentMessage(text: String, sender: Sender = Sender(1, "Sara", null, "user")) {
        if (_conversation.value is ConversationState.None) activate()
        val m = Message(
            (nextId++).toString(), nextId, null, 1, text, MessageType.OUTGOING, null, emptyMap(),
            System.currentTimeMillis() / 1000, emptyList(), sender, MessageStatus.SENT,
        )
        _messages.update { it + m }
        _unread.update { it + 1 }
        (_conversation.value as? ConversationState.Active)?.let { _conversation.value = it.copy(unreadCount = it.unreadCount + 1) }
    }

    /** Toggle the agent typing indicator. */
    public fun simulateTyping(on: Boolean) {
        (_conversation.value as? ConversationState.Active)?.let { _conversation.value = it.copy(agentTyping = on) }
    }

    /** Agent resolves the conversation (server-side). */
    public fun simulateResolved() {
        val cur = _conversation.value as? ConversationState.Active ?: return
        _conversation.value = ConversationState.Ended(cur.id, csatSubmitted = false, csatEnabled = _config.value?.csatSurveyEnabled == true)
        _hasActive.value = false
    }

    public fun setLocale(locale: String) {
        _locale.value = locale
    }

    public fun setConnection(state: ConnectionState) {
        _connection.value = state
    }

    public fun setWidgetConfig(config: WidgetConfig?) {
        _config.value = config
        _state.value = if (config != null) HodhodState.Ready else HodhodState.Idle
    }

    public fun setIssueNotices(notices: List<IssueNotice>) {
        _notices.value = notices
    }

    public fun setState(state: HodhodState) {
        _state.value = state
    }

    private fun activate() {
        _conversation.value = ConversationState.Active(1, ConversationStatus.OPEN, null, hasMore = false, unreadCount = 0, agentTyping = false)
        _hasActive.value = true
    }

    private fun replace(key: String, with: Message) {
        _messages.update { list -> list.map { if (it.key == key) with else it } }
    }

    private fun outgoing(text: String, clientId: String, status: MessageStatus) = Message(
        clientId, null, clientId, 1, text, MessageType.INCOMING, null, emptyMap(),
        System.currentTimeMillis() / 1000, emptyList(), null, status,
    )

    public companion object {
        /** Sample inbox config for previews; tweak with `copy`. */
        public fun sampleConfig(mode: ContactMode = ContactMode.CHAT): WidgetConfig = WidgetConfig(
            websiteToken = "sample-token",
            websiteName = "Hodhod",
            welcomeTitle = "Hi there",
            welcomeTagline = "We usually reply in a few minutes",
            avatarUrl = null,
            widgetColor = "#1F93FF",
            contactMode = mode,
            ticketForm = TicketFormConfig(),
            ticketCategories = listOf(TicketCategory(1, "Billing", "#F59E0B"), TicketCategory(2, "Technical", "#3B82F6")),
            workingHoursEnabled = false,
            workingHours = emptyList(),
            timezone = "UTC",
            utcOffset = "+00:00",
            holidays = emptyList(),
            outOfOfficeMessage = null,
            replyTime = "in_a_few_minutes",
            preChatForm = PreChatForm(false, null, false, emptyList()),
            csatSurveyEnabled = true,
            hasFlowBot = false,
            allowMessagesAfterResolved = true,
            lockToSingleConversation = false,
            enabledLanguages = listOf(LanguageOption("en", "English"), LanguageOption("fa", "فارسی")),
            enabledFeatures = EnabledFeatures(attachments = true, emojiPicker = true, endConversation = true, emailTranscript = true, raw = emptySet()),
            locale = "en",
            disableBranding = false,
        )

        public fun sampleAgents(): List<Agent> = listOf(Agent(1, "Sara", null, "online"), Agent(2, "Ali", null, "busy"))
    }
}
