package chat.hodhod.sdk.internal

import chat.hodhod.sdk.Agent
import chat.hodhod.sdk.Announcement
import chat.hodhod.sdk.AnnouncementBlock
import chat.hodhod.sdk.AnnouncementKind
import chat.hodhod.sdk.ContactInfo
import chat.hodhod.sdk.ContactMode
import chat.hodhod.sdk.ConversationStatus
import chat.hodhod.sdk.EnabledFeatures
import chat.hodhod.sdk.LanguageOption
import chat.hodhod.sdk.LocalizedText
import chat.hodhod.sdk.Message
import chat.hodhod.sdk.MessageAttachment
import chat.hodhod.sdk.MessageStatus
import chat.hodhod.sdk.MessageType
import chat.hodhod.sdk.PreChatField
import chat.hodhod.sdk.PreChatForm
import chat.hodhod.sdk.Sender
import chat.hodhod.sdk.TextSegment
import chat.hodhod.sdk.TicketCategory
import chat.hodhod.sdk.TicketFormConfig
import chat.hodhod.sdk.TicketStatus
import chat.hodhod.sdk.TicketSummary
import chat.hodhod.sdk.WidgetConfig
import chat.hodhod.sdk.WorkingHour
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object Parsers {
    fun sender(o: JsonObject?): Sender? = o?.let {
        Sender(it.long("id"), it.str("available_name") ?: it.str("name"), it.str("avatar_url") ?: it.str("thumbnail"), it.str("type"))
    }

    fun attachment(o: JsonObject): MessageAttachment = MessageAttachment(
        id = o.long("id"),
        fileType = o.str("file_type") ?: "file",
        dataUrl = o.str("data_url")?.takeIf { it.isNotEmpty() },
        thumbUrl = o.str("thumb_url")?.takeIf { it.isNotEmpty() },
        fileSize = o.long("file_size"),
        extension = o.str("extension"),
        contentType = o.str("content_type"),
        width = o.int("width"),
        height = o.int("height"),
        transcribedText = o.str("transcribed_text")?.takeIf { it.isNotEmpty() },
    )

    private fun messageType(o: JsonObject): MessageType = when (o.int("message_type")) {
        0 -> MessageType.INCOMING
        1 -> MessageType.OUTGOING
        2 -> MessageType.ACTIVITY
        3 -> MessageType.TEMPLATE
        else -> when (o.str("message_type")) {
            "incoming" -> MessageType.INCOMING
            "outgoing" -> MessageType.OUTGOING
            "activity" -> MessageType.ACTIVITY
            "template" -> MessageType.TEMPLATE
            else -> MessageType.OUTGOING
        }
    }

    /** A message object from REST (`payload[]`, create response) or from a websocket `message.created/updated` event. */
    fun message(o: JsonObject, fallbackConversationId: Int? = null): Message {
        val serverId = o.long("id")
        val attrs = o.sub("content_attributes")?.toPlain() as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val contentAttributes = (attrs as? Map<String, Any?>) ?: emptyMap()
        val sender = sender(o.sub("sender")) ?: o.str("sender_type")?.let { t ->
            Sender(o.long("sender_id"), null, null, t.lowercase().replace("agentbot", "agent_bot"))
        }
        val echo = o.str("echo_id")
        return Message(
            id = serverId?.toString() ?: echo.orEmpty(),
            serverId = serverId,
            clientId = echo?.takeIf { it.isNotEmpty() },
            conversationId = o.int("conversation_id") ?: fallbackConversationId,
            content = o.str("content"),
            messageType = messageType(o),
            contentType = o.str("content_type")?.takeIf { it != "text" },
            contentAttributes = contentAttributes,
            createdAt = o.long("created_at") ?: (System.currentTimeMillis() / 1000),
            attachments = o.list("attachments")?.mapNotNull { (it as? JsonObject)?.let(::attachment) }.orEmpty(),
            sender = sender,
            status = MessageStatus.SENT,
            replyToId = (contentAttributes["in_reply_to"] as? Number)?.toLong(),
        )
    }

    fun conversationStatus(s: String?): ConversationStatus = when (s) {
        "open" -> ConversationStatus.OPEN
        "pending" -> ConversationStatus.PENDING
        "snoozed" -> ConversationStatus.SNOOZED
        "resolved" -> ConversationStatus.RESOLVED
        else -> ConversationStatus.UNKNOWN
    }

    fun agent(o: JsonObject): Agent? {
        val id = o.long("id") ?: return null
        return Agent(id, o.str("name") ?: "", o.str("avatar_url")?.takeIf { it.isNotEmpty() }, o.str("availability_status"))
    }

    fun ticket(o: JsonObject): TicketSummary = TicketSummary(
        number = o.int("number") ?: 0,
        subject = o.str("subject").orEmpty(),
        status = TicketStatus.fromWire(o.str("status")),
        category = o.sub("category")?.let { c -> c.int("id")?.let { TicketCategory(it, c.str("name").orEmpty(), c.str("color")) } },
        createdAt = o.long("created_at") ?: 0,
        updatedAt = o.long("updated_at") ?: 0,
        resolvedAt = o.long("resolved_at"),
        conversationId = o.int("conversation_display_id") ?: o.int("conversation_id"),
        source = o.str("source")?.takeIf { it.isNotEmpty() },
        isOpen = o.bool("is_open") ?: TicketStatus.fromWire(o.str("status")).isActive,
        lastAgentReplyAt = o.long("last_agent_reply_at"),
    )

    fun contactInfo(o: JsonObject, previous: ContactInfo? = null): ContactInfo = ContactInfo(
        id = o.long("id") ?: previous?.id,
        identifier = if (o.containsKey("identifier")) o.str("identifier") else previous?.identifier,
        hasName = o.bool("has_name") ?: o.str("name")?.let { it.isNotBlank() } ?: previous?.hasName ?: false,
        hasEmail = o.bool("has_email") ?: o.str("email")?.let { it.isNotBlank() } ?: previous?.hasEmail ?: false,
        hasPhone = o.bool("has_phone_number") ?: o.str("phone_number")?.let { it.isNotBlank() } ?: previous?.hasPhone ?: false,
        name = o.str("name") ?: previous?.name,
        email = o.str("email") ?: previous?.email,
        preChatSatisfied = o.bool("pre_chat_identified") ?: previous?.preChatSatisfied ?: false,
        phone = o.str("phone_number") ?: previous?.phone,
    )

    private fun localized(o: JsonElement?): LocalizedText? =
        o.obj()?.let { obj -> obj.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { k to it } }.toMap() }
            ?.takeIf { it.isNotEmpty() }?.let(::LocalizedText)

    private fun ticketForm(o: JsonObject?): TicketFormConfig = TicketFormConfig(
        category = o?.str("category") ?: "optional",
        email = o?.str("email") ?: "required",
        attachments = o?.bool("attachments") ?: true,
        title = localized(o?.get("title")),
        hint = localized(o?.get("hint")),
        success = localized(o?.get("success")),
    )

    private fun preChat(enabled: Boolean, o: JsonObject?): PreChatForm = PreChatForm(
        enabled = enabled,
        message = o?.str("pre_chat_message"),
        requireEmail = o?.bool("require_email") ?: false,
        fields = o?.list("pre_chat_fields")?.mapNotNull { it as? JsonObject }?.map { f ->
            PreChatField(
                name = f.str("name").orEmpty(),
                label = f.str("label") ?: f.str("name").orEmpty(),
                type = f.str("type") ?: "text",
                fieldType = f.str("field_type") ?: "standard",
                required = f.bool("required") ?: false,
                enabled = f.bool("enabled") ?: false,
                placeholder = f.str("placeholder"),
                values = f.list("values")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty(),
                regexPattern = f.str("regex_pattern"),
                regexCue = f.str("regex_cue"),
            )
        }.orEmpty(),
    )

    private fun workingHours(a: JsonArray?): List<WorkingHour> = a?.mapNotNull { it as? JsonObject }?.mapNotNull { w ->
        val dow = w.int("day_of_week") ?: return@mapNotNull null
        WorkingHour(
            dow, w.bool("open_all_day") ?: false, w.bool("closed_all_day") ?: false,
            w.int("open_hour") ?: 0, w.int("open_minutes") ?: 0, w.int("close_hour") ?: 0, w.int("close_minutes") ?: 0,
        )
    }.orEmpty()

    /** Extras only exposed in the `/widget` HTML (or, in newer servers, in the config JSON). */
    data class Extras(
        val ticketCategories: List<TicketCategory>?,
        val holidays: List<String>?,
        val lockToSingleConversation: Boolean?,
        val hasFlowBot: Boolean?,
    )

    fun extrasFromJson(c: JsonObject): Extras = Extras(
        ticketCategories = c.list("ticket_categories")?.let(::categories),
        holidays = c.list("lark_holidays")?.mapNotNull { (it as? JsonPrimitive)?.content },
        lockToSingleConversation = c.bool("lock_to_single_conversation"),
        hasFlowBot = c.bool("has_flow_bot"),
    )

    private fun categories(a: JsonArray): List<TicketCategory> = a.mapNotNull { it as? JsonObject }.mapNotNull { c ->
        c.int("id")?.let { TicketCategory(it, c.str("name").orEmpty(), c.str("color")) }
    }

    /** Parses `window.chatwootWebChannel = { key: value, ... }` (values are JSON, 'single quoted' strings, true/false). */
    fun extrasFromHtml(html: String): Extras {
        val start = html.indexOf("window.chatwootWebChannel")
        if (start < 0) return Extras(null, null, null, null)
        val end = html.indexOf("window.chatwootPubsubToken", start).let { if (it < 0) html.length else it }
        val values = HashMap<String, String>()
        val line = Regex("^\\s*([A-Za-z]+):\\s*(.*?),?\\s*$")
        html.substring(start, end).lines().forEach { l -> line.find(l)?.let { values[it.groupValues[1]] = it.groupValues[2] } }
        fun json(key: String): JsonElement? = values[key]?.let { parseJson(it) }
        return Extras(
            ticketCategories = json("ticketCategories").arr()?.let(::categories),
            holidays = json("larkHolidays").arr()?.mapNotNull { (it as? JsonPrimitive)?.content },
            lockToSingleConversation = values["lockToSingleConversation"]?.toBooleanStrictOrNull(),
            hasFlowBot = values["hasFlowBot"]?.toBooleanStrictOrNull(),
        )
    }

    fun widgetConfig(root: JsonObject, extras: Extras?, devImageHosts: Set<String> = emptySet()): WidgetConfig? {
        val c = root.sub("website_channel_config") ?: return null
        val own = extrasFromJson(c)
        val flags = c.list("enabled_features")?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().toSet()
        val categories = own.ticketCategories ?: extras?.ticketCategories ?: emptyList()
        return WidgetConfig(
            websiteToken = c.str("website_token").orEmpty(),
            websiteName = c.str("website_name").orEmpty(),
            welcomeTitle = c.str("welcome_title"),
            welcomeTagline = c.str("welcome_tagline"),
            avatarUrl = c.str("avatar_url")?.takeIf { it.isNotEmpty() },
            widgetColor = c.str("widget_color") ?: "#1F93FF",
            contactMode = ContactMode.fromWire(c.str("contact_mode")),
            ticketForm = ticketForm(c.sub("ticket_form")),
            ticketCategories = categories,
            workingHoursEnabled = c.bool("working_hours_enabled") ?: false,
            workingHours = workingHours(c.list("working_hours")),
            timezone = c.str("timezone") ?: "UTC",
            utcOffset = c.str("utc_off_set"),
            holidays = own.holidays ?: extras?.holidays ?: emptyList(),
            outOfOfficeMessage = c.str("out_of_office_message"),
            replyTime = c.str("reply_time"),
            preChatForm = preChat(c.bool("pre_chat_form_enabled") ?: false, c.sub("pre_chat_form_options")),
            csatSurveyEnabled = c.bool("csat_survey_enabled") ?: false,
            hasFlowBot = own.hasFlowBot ?: extras?.hasFlowBot ?: false,
            allowMessagesAfterResolved = c.bool("allow_messages_after_resolved") ?: true,
            lockToSingleConversation = own.lockToSingleConversation ?: extras?.lockToSingleConversation ?: false,
            enabledLanguages = c.list("enabled_languages")?.mapNotNull { it as? JsonObject }?.mapNotNull { l ->
                l.str("iso_639_1_code")?.let { LanguageOption(it, l.str("name").orEmpty()) }
            }.orEmpty(),
            enabledFeatures = EnabledFeatures(
                attachments = "attachments" in flags,
                emojiPicker = "emoji_picker" in flags,
                endConversation = "end_conversation" in flags,
                emailTranscript = true, // no per-inbox flag: shown when the visitor has an e-mail (web ChatFooter); server may answer 402/429
                raw = flags,
            ),
            locale = c.str("locale"),
            disableBranding = c.bool("disable_branding") ?: false,
            announcements = announcements(c.list("announcements") ?: root.list("announcements"), devImageHosts),
        )
    }

    private const val MAX_ANNOUNCEMENTS = 2
    private const val MAX_BLOCKS = 20
    private const val MAX_SEGMENTS = 100
    private const val MAX_TEXT = 4000
    private val URL_SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** Link target allow-list: http/https (with a host), mailto, tel. Anything else (javascript:, intent:, file:, data:, ...) is dropped. */
    fun safeHref(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.length > 2048 || s.any { it.code <= 0x20 || it.code == 0x7f }) return null
        return when (URL_SCHEME.find(s)?.groupValues?.get(1)?.lowercase()) {
            "http", "https" -> s.takeIf { Regex("^[A-Za-z]+://[^/?#]+", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            "mailto", "tel" -> s.takeIf { it.substringAfter(':').isNotEmpty() }
            else -> null
        }
    }

    /**
     * Image sources must be https with a host. Development exception: with `allowCleartext` the caller passes [devHosts] (the configured
     * server, localhost) and an `http` ActiveStorage upload of the Rails server itself is accepted too (a local server serves its own blobs over http).
     */
    fun safeImageUrl(raw: String?, devHosts: Set<String> = emptySet()): String? {
        val s = safeHref(raw) ?: return null
        if (s.startsWith("https://", ignoreCase = true)) return s
        if (devHosts.isEmpty() || !s.startsWith("http://", ignoreCase = true)) return null
        val uri = runCatching { java.net.URI(s) }.getOrNull() ?: return null
        return s.takeIf { uri.host?.lowercase() in devHosts && uri.path.orEmpty().startsWith("/rails/active_storage/") }
    }

    private fun segment(o: JsonObject): TextSegment? {
        val text = o.str("text")?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() } ?: return null
        return TextSegment(text, bold = o.bool("bold") == true, href = safeHref(o.str("href")))
    }

    private fun announcementBlock(o: JsonObject, devHosts: Set<String>): AnnouncementBlock? = when (o.str("type")) {
        "text" -> o.list("segments")?.take(MAX_SEGMENTS)?.mapNotNull { (it as? JsonObject)?.let(::segment) }?.takeIf { it.isNotEmpty() }
            ?.let { AnnouncementBlock.Text(it) }
        "image" -> safeImageUrl(o.str("url"), devHosts)?.let { AnnouncementBlock.Image(it, o.str("alt")?.trim()?.takeIf { a -> a.isNotEmpty() }?.take(MAX_TEXT), safeHref(o.str("href"))) }
        else -> null // unknown block types (newer server) are ignored
    }

    /** Tolerant parse of the public config `announcements` (missing / not a list / broken entries -> skipped; at most [MAX_ANNOUNCEMENTS]). */
    fun announcements(arr: JsonArray?, devImageHosts: Set<String> = emptySet()): List<Announcement> = arr?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val blocks = o.list("blocks")?.take(MAX_BLOCKS)?.mapNotNull { (it as? JsonObject)?.let { b -> announcementBlock(b, devImageHosts) } }.orEmpty()
        if (blocks.isEmpty()) return@mapNotNull null
        Announcement(
            id = id,
            kind = if (o.str("kind") == "alert") AnnouncementKind.ALERT else AnnouncementKind.NOTICE,
            dismissible = o.bool("dismissible") ?: true,
            blocks = blocks,
            updatedAt = o.str("updated_at").orEmpty(),
        )
    }?.take(MAX_ANNOUNCEMENTS).orEmpty()

    /** True when the config JSON already carries every extra, so the `/widget` HTML fetch can be skipped. */
    fun hasAllExtras(root: JsonObject): Boolean {
        val c = root.sub("website_channel_config") ?: return false
        val e = extrasFromJson(c)
        return e.ticketCategories != null && e.holidays != null && e.lockToSingleConversation != null && e.hasFlowBot != null
    }
}
