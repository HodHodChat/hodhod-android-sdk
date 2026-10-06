package chat.hodhod.sdk

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for the chat UI. All `suspend` functions return [Result] (never throw except [kotlinx.coroutines.CancellationException]).
 * Flows are hot [StateFlow]s and safe to collect from the main thread.
 *
 * Failure codes ([HodhodException.code]): `network`, `unauthorized`, `suspended`, `forbidden`, `chat_disabled`, `ticket_disabled`, `rate_limited`,
 * `ticket_closed`, `spam`, `invalid_param`, `description_required`, `description_too_long`, `subject_required`, `email_required`,
 * `conversation_resolved`, `not_found`, `server`, `no_conversation`, `payment_required`.
 */
public interface HodhodRepository {
    /** Inbox settings; null until the bootstrap succeeded ([Hodhod.state] = Ready). */
    public val widgetConfig: StateFlow<WidgetConfig?>

    /** Chat state (none / active / ended). */
    public val conversation: StateFlow<ConversationState>

    /** Messages of the current conversation, oldest first, including optimistic (SENDING/FAILED) ones. */
    public val messages: StateFlow<List<Message>>

    /** Visitor's tickets, newest first (loaded by [refresh] / [loadTickets]). */
    public val tickets: StateFlow<List<TicketSummary>>

    /** Websocket state. */
    public val connection: StateFlow<ConnectionState>

    /** Bootstrap state (same flow as [Hodhod.state]). */
    public val state: StateFlow<HodhodState>

    /** Inbox agents with availability for the home screen. */
    public val agents: StateFlow<List<Agent>>

    /** Ongoing incident banners (up to 3). Refreshed by [refresh]. */
    public val issueNotices: StateFlow<List<IssueNotice>>

    /** What the server knows about the visitor (drives prechat gate and ticket form name/email fields). */
    public val contact: StateFlow<ContactInfo>

    /** Unread agent messages (same flow as [Hodhod.unreadCount]); cleared by [markRead]. */
    public val unreadCount: StateFlow<Int>

    /** True when a chat is ongoing: there are messages and the conversation is not ended (web `getHasActiveConversation`). */
    public val hasActiveConversation: StateFlow<Boolean>

    /** Effective UI locale (`fa`, `en`, ...) from [HodhodI18n.resolve]; use [HodhodI18n.isRtl] for the layout direction. */
    public val uiLocale: StateFlow<String>

    /** New agent message on one of the visitor's tickets (live over the websocket); reload the thread / list when it matches. */
    public val ticketActivity: SharedFlow<TicketActivity>

    /** Flow engine (stage 2). */
    public val flow: HodhodFlowEngine

    /**
     * Bootstrap (if not done) and (re)load config, contact, conversation + latest messages, agents, issue notices, tickets;
     * connects the websocket. Idempotent; call when the chat UI opens and for pull-to-refresh / retry.
     */
    public suspend fun refresh(): Result<Unit>

    /**
     * Send a visitor message (optimistic): it appears immediately in [messages] with [MessageStatus.SENDING]; on failure it stays
     * as [MessageStatus.FAILED] and can be retried with [retry]. Creates the conversation on the server when none exists.
     * Each attachment is uploaded as its own message (like the web widget); [text] is sent first.
     * Blank text with no attachments is rejected.
     */
    public suspend fun sendMessage(text: String, attachments: List<Attachment> = emptyList(), clientId: String = HodhodRepository.newClientId()): Result<Message>

    /** Retry a [MessageStatus.FAILED] message identified by its [Message.key]. */
    public suspend fun retry(clientId: String): Result<Message>

    /** Remove a failed optimistic message. */
    public fun discardFailed(clientId: String)

    /** Load the previous page (before the oldest message id). Updates [ConversationState.Active.hasMore]. */
    public suspend fun loadOlder(): Result<Unit>

    /** Mark everything read (contact last seen = now) and clear [unreadCount]. */
    public suspend fun markRead(): Result<Unit>

    /** Tell the agents the visitor is typing (throttled internally; auto-off after 5 s of silence). */
    public fun setTyping(on: Boolean)

    /** Visitor ends the chat (toggle_status; needs the inbox `end_conversation` feature). Moves to [ConversationState.Ended] (or stays active when locked). */
    public suspend fun endConversation(): Result<Unit>

    /** CSAT for the ended/last conversation. [rating] 1..5. Sent as the csat message's submitted_values. */
    public suspend fun submitCsat(rating: Int, feedback: String?): Result<Unit>

    /** E-mail the transcript to the visitor's e-mail on file (needs contact email + account feature; `payment_required` / `rate_limited` otherwise). */
    public suspend fun sendTranscriptByEmail(): Result<Unit>

    /** Create a widget ticket; also refreshes [tickets]. */
    public suspend fun createTicket(form: TicketForm): Result<TicketSummary>

    /** Reload [tickets]. */
    public suspend fun loadTickets(): Result<List<TicketSummary>>

    /** Ticket with its messages. */
    public suspend fun loadTicket(number: Int): Result<TicketThread>

    /** Reply on a ticket; returns the created message. Refreshes [tickets]. */
    public suspend fun replyToTicket(number: Int, text: String, attachments: List<Attachment> = emptyList()): Result<Message>

    /**
     * Pre-chat form submission: identifies the visitor (email/name/phone, custom attributes) via contact update. Keys: `emailAddress`,
     * `fullName`, `phoneNumber`, or custom attribute names. The first message creates the conversation afterwards.
     */
    public suspend fun submitPreChat(fields: Map<String, String>): Result<Unit>

    /**
     * Back to the initial home state after a chat ended (identical to the web widget `resetConversation`): remembers the ended id (late
     * websocket events of it are ignored), clears messages/pending metadata/unread, state = None. Tickets untouched.
     */
    public fun resetConversation()

    /** Dismiss a banner for this session. */
    public fun dismissIssueNotice(id: Long)

    public companion object {
        /** Fresh random client id for optimistic messages. */
        public fun newClientId(): String = java.util.UUID.randomUUID().toString()
    }
}

/** Failure carried inside [Result.failure]. */
public class HodhodException(public val code: String, message: String? = null, public val httpStatus: Int? = null, cause: Throwable? = null) :
    Exception(message ?: code, cause)

/** A message arrived on ticket [ticketNumber] (it is not part of [HodhodRepository.messages]). */
public data class TicketActivity(val ticketNumber: Int, val message: Message)
