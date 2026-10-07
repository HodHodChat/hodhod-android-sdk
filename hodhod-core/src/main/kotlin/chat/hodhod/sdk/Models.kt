package chat.hodhod.sdk

import java.io.File

/** Who sent a [Message]. `type` is `user` (agent), `agent_bot`, or `contact`. */
public data class Sender(
    val id: Long?,
    val name: String?,
    val avatarUrl: String?,
    val type: String?,
)

/** Direction/kind of a [Message] (server `message_type`: 0 incoming(from visitor), 1 outgoing(from agent/bot), 2 activity, 3 template). */
public enum class MessageType { INCOMING, OUTGOING, ACTIVITY, TEMPLATE }

/** Delivery state of a visitor message. Server messages are always [SENT]. */
public enum class MessageStatus { SENDING, SENT, FAILED }

/** File sent by the visitor (input to [HodhodRepository.sendMessage] / [TicketForm] / ticket reply). */
public class Attachment(
    public val file: File,
    public val fileName: String = file.name,
    public val mimeType: String = "application/octet-stream",
) {
    override fun toString(): String = "Attachment($fileName, $mimeType)"
}

/** File attached to a received/sent [Message]. `fileType`: image, audio, video, file, location, fallback, contact, embed. */
public data class MessageAttachment(
    val id: Long?,
    val fileType: String,
    val dataUrl: String?,
    val thumbUrl: String?,
    val fileSize: Long?,
    val extension: String?,
    val contentType: String?,
    val width: Int?,
    val height: Int?,
    val transcribedText: String?,
    /** Local file while the message is still SENDING/FAILED (preview before upload completes). */
    val localFile: File? = null,
)

/**
 * A chat message. [id] is the stable list key: the [clientId] for optimistic messages, replaced by the server id (as string)
 * once confirmed -- but [clientId] is retained, so LazyColumn keys should use [key].
 *
 * @param contentType server `content_type`: text, input_email, input_select, cards, form, article, csat, ... (null = text).
 * @param contentAttributes raw server `content_attributes` as plain Kotlin maps/lists/primitives (items, submitted_values, deleted, in_reply_to, ...).
 * @param createdAt unix seconds.
 */
public data class Message(
    val id: String,
    val serverId: Long?,
    val clientId: String?,
    val conversationId: Int?,
    val content: String?,
    val messageType: MessageType,
    val contentType: String?,
    val contentAttributes: Map<String, Any?>,
    val createdAt: Long,
    val attachments: List<MessageAttachment>,
    val sender: Sender?,
    val status: MessageStatus,
    val replyToId: Long? = null,
) {
    /** Stable key for lists (does not change when a sending message is confirmed). */
    val key: String get() = clientId ?: id

    /** True for messages written by the visitor (this device). */
    val isFromContact: Boolean get() = messageType == MessageType.INCOMING
}

/** Support agent of the inbox (home screen avatars / presence). */
public data class Agent(
    val id: Long,
    val name: String,
    val avatarUrl: String?,
    /** `online`, `busy`, `offline`. */
    val availability: String?,
)

/** Server conversation status. */
public enum class ConversationStatus { OPEN, PENDING, SNOOZED, RESOLVED, UNKNOWN }

/**
 * State of the visitor's chat, mirroring the web widget store (conversationAttributes + conversation).
 * - [None]: no conversation (home screen / first message creates one).
 * - [Active]: ongoing conversation.
 * - [Ended]: conversation is resolved (and the inbox does not lock to a single conversation): UI shows the end screen
 *   (CSAT, transcript) and then calls [HodhodRepository.resetConversation]; `messages` still holds the old thread until reset.
 */
public sealed interface ConversationState {
    public data object None : ConversationState

    public data class Active(
        val id: Int,
        val status: ConversationStatus,
        val assignee: Agent?,
        val hasMore: Boolean,
        val unreadCount: Int,
        val agentTyping: Boolean,
        val isLoadingOlder: Boolean = false,
    ) : ConversationState

    public data class Ended(
        val id: Int,
        val csatSubmitted: Boolean,
        /** True when the inbox has CSAT enabled (UI shows the survey card). */
        val csatEnabled: Boolean,
    ) : ConversationState
}

/** Identity facts the server knows about the visitor (to decide which form fields are needed). */
public data class ContactInfo(
    val id: Long?,
    val identifier: String?,
    val hasName: Boolean,
    val hasEmail: Boolean,
    val hasPhone: Boolean,
    val name: String? = null,
    val email: String? = null,
    /** Pre-chat form already satisfied for this contact (server `pre_chat_identified`/has_* data). */
    val preChatSatisfied: Boolean = false,
    /** Phone number on file (used by flow templates `{{contact.phone}}`). */
    val phone: String? = null,
)

/** Active incident banner (`/issue_notices`). */
public data class IssueNotice(val id: Long, val message: String)

/** Inbox announcement flavour: [NOTICE] = yellow information, [ALERT] = red warning. */
public enum class AnnouncementKind { NOTICE, ALERT }

/**
 * One run of announcement text. [bold] = emphasised, [href] = link target (already restricted to http/https/mailto/tel by the parser,
 * never null-checked again by the UI beyond the same allow-list).
 */
public data class TextSegment(val text: String, val bold: Boolean = false, val href: String? = null)

/** A block of an [Announcement]. Unknown server block types are dropped by the parser. */
public sealed interface AnnouncementBlock {
    /** A paragraph made of [segments]. */
    public data class Text(val segments: List<TextSegment>) : AnnouncementBlock

    /** An image ([url] is https only), optional [alt] text and optional tap target [href] (http/https/mailto/tel). */
    public data class Image(val url: String, val alt: String? = null, val href: String? = null) : AnnouncementBlock
}

/**
 * Inbox announcement shown at the start of the widget (`announcements` of the public widget config). The server already filters by
 * enabled + schedule and orders them (at most 2). [updatedAt] is an opaque version: dismissing is remembered per `(id, updatedAt)`,
 * so an edited announcement is shown again.
 */
public data class Announcement(
    /** Server-generated identifier (UUID). */
    val id: String,
    val kind: AnnouncementKind,
    val dismissible: Boolean,
    val blocks: List<AnnouncementBlock>,
    val updatedAt: String,
)

/** Ticket form submission (`/api/v1/widget/tickets`). [website] is the honeypot -- keep null. */
public class TicketForm(
    public val subject: String,
    public val description: String,
    public val categoryId: Int? = null,
    public val name: String? = null,
    public val email: String? = null,
    public val attachments: List<Attachment> = emptyList(),
    public val website: String? = null,
)

/** Ticket status (server enum open/in_progress/waiting_on_customer/resolved/closed). */
public enum class TicketStatus(public val wire: String) {
    OPEN("open"), IN_PROGRESS("in_progress"), WAITING("waiting_on_customer"), RESOLVED("resolved"), CLOSED("closed"), UNKNOWN("");

    /** Open, in progress or waiting for the visitor (the server's "open" filter). */
    public val isActive: Boolean get() = this == OPEN || this == IN_PROGRESS || this == WAITING || this == UNKNOWN

    public companion object {
        public fun fromWire(s: String?): TicketStatus = entries.firstOrNull { it.wire == s && s != "" } ?: UNKNOWN
    }
}

public data class TicketCategory(val id: Int, val name: String, val color: String?)

public data class TicketSummary(
    val number: Int,
    val subject: String,
    val status: TicketStatus,
    val category: TicketCategory?,
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long?,
    /** Display id of the ticket's conversation (`conversation_display_id`, older servers: `conversation_id`). */
    val conversationId: Int?,
    /** Where the ticket came from: `widget` (created by the visitor in the app/widget) or e.g. `conversation` (converted from a chat by an agent). Null on older servers. */
    val source: String? = null,
    /** Server's open/closed verdict (`is_open`); older servers: derived from [status] (open, in progress, waiting = open). */
    val isOpen: Boolean = status.isActive,
    /** Time of the last public agent reply (epoch seconds), when the server reports it. */
    val lastAgentReplyAt: Long? = null,
) {
    /** Same as [conversationId]; named like the server field. */
    val conversationDisplayId: Int? get() = conversationId

    /** True when an agent converted a chat into this ticket (it was not created through the ticket form). */
    val isFromConversation: Boolean get() = source != null && source != "widget"
}

/** Ticket list filter (`status=open|closed|all`). Open = open / in progress / waiting for the visitor; closed = resolved / closed / merged. */
public enum class TicketFilter(public val wire: String) { OPEN("open"), CLOSED("closed"), ALL("all") }

/** Ticket counters of the visitor (list `meta`). */
public data class TicketCounts(val open: Int, val closed: Int, val total: Int)

/** Light counters for the Home badge (`/tickets/summary`); [TicketSummaryCounts.NONE] until known. */
public data class TicketSummaryCounts(val open: Int, val total: Int) {
    public companion object {
        public val NONE: TicketSummaryCounts = TicketSummaryCounts(0, 0)
    }
}

/** One page of the visitor's tickets for a [TicketFilter] (newest first) with the counters of all filters. */
public data class TicketList(
    val filter: TicketFilter,
    val tickets: List<TicketSummary>,
    val counts: TicketCounts,
    val page: Int,
    val perPage: Int,
    /** More pages may follow (`page * perPage < total of the filter`). */
    val hasMore: Boolean,
)

public data class TicketThread(
    val ticket: TicketSummary,
    val messages: List<Message>,
)
