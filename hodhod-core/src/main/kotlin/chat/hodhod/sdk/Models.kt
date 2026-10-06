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
    val conversationId: Int?,
)

public data class TicketThread(
    val ticket: TicketSummary,
    val messages: List<Message>,
)
