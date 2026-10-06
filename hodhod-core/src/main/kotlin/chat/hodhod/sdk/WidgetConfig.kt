package chat.hodhod.sdk

/** Inbox contact mode (server `contact_mode`). */
public enum class ContactMode(public val wire: String) {
    CHAT("chat"), TICKET("ticket"), BOTH("both"), TICKET_WHEN_OFFLINE("ticket_when_offline");

    public companion object {
        public fun fromWire(s: String?): ContactMode = entries.firstOrNull { it.wire == s } ?: CHAT
    }
}

/** What the home screen should offer right now (mirror of the web widget `useTicketMode().mode`). */
public enum class StartMode {
    /** Live chat only. */
    CHAT,

    /** Ticket form only. */
    TICKET,

    /** Visitor chooses chat or ticket (`both`). */
    CHOICE,
}

/** Result of [WidgetConfig.startMode]. [offlineReason] = mode is TICKET only because `ticket_when_offline` and the team is closed. */
public data class StartDecision(val mode: StartMode, val offlineReason: Boolean)

/** One weekday slot of the inbox working hours (dayOfWeek: 0 = Sunday ... 6 = Saturday, in the inbox timezone). */
public data class WorkingHour(
    val dayOfWeek: Int,
    val openAllDay: Boolean,
    val closedAllDay: Boolean,
    val openHour: Int,
    val openMinutes: Int,
    val closeHour: Int,
    val closeMinutes: Int,
)

/** Localised ticket-form text overrides (`fa`/`en`). */
public data class LocalizedText(val values: Map<String, String>) {
    /** Text for [locale] (language only), falling back to `en` then null. */
    public fun resolve(locale: String): String? = values[locale.substringBefore('-').lowercase()] ?: values["en"]
}

/** Inbox ticket-form settings. `category`: off|optional|required; `email`: optional|required. */
public data class TicketFormConfig(
    val category: String = "optional",
    val email: String = "required",
    val attachments: Boolean = true,
    val title: LocalizedText? = null,
    val hint: LocalizedText? = null,
    val success: LocalizedText? = null,
)

/** One pre-chat form field. `name`: emailAddress|fullName|phoneNumber or a custom attribute key; `type`: text|email|number|list|checkbox|link|date... */
public data class PreChatField(
    val name: String,
    val label: String,
    val type: String,
    val fieldType: String,
    val required: Boolean,
    val enabled: Boolean,
    val placeholder: String? = null,
    val values: List<String> = emptyList(),
    val regexPattern: String? = null,
    val regexCue: String? = null,
)

public data class PreChatForm(
    val enabled: Boolean,
    val message: String?,
    val requireEmail: Boolean,
    val fields: List<PreChatField>,
)

public data class LanguageOption(val code: String, val name: String)

/** Feature flags selectable per inbox (server `selected_feature_flags`). */
public data class EnabledFeatures(
    val attachments: Boolean,
    val emojiPicker: Boolean,
    val endConversation: Boolean,
    val emailTranscript: Boolean,
    val raw: Set<String>,
)

/**
 * Inbox/channel settings loaded at bootstrap (POST /api/v1/widget/config, plus the extras that the server only exposes on
 * `/widget`). Immutable snapshot; use [startMode] / [isInWorkingHours] with the current time.
 */
public data class WidgetConfig(
    val websiteToken: String,
    val websiteName: String,
    val welcomeTitle: String?,
    val welcomeTagline: String?,
    val avatarUrl: String?,
    /** Inbox widget colour as `#RRGGBB`. */
    val widgetColor: String,
    val contactMode: ContactMode,
    val ticketForm: TicketFormConfig,
    val ticketCategories: List<TicketCategory>,
    val workingHoursEnabled: Boolean,
    val workingHours: List<WorkingHour>,
    /** IANA timezone of the inbox (`Asia/Tehran`). */
    val timezone: String,
    /** `+03:30` style offset. */
    val utcOffset: String?,
    /** Company holidays (`YYYY-MM-DD`, in the inbox timezone). */
    val holidays: List<String>,
    val outOfOfficeMessage: String?,
    /** `in_a_few_minutes` | `in_a_few_hours` | `in_a_day`. */
    val replyTime: String?,
    val preChatForm: PreChatForm,
    val csatSurveyEnabled: Boolean,
    /** True when the inbox has a chatbot flow (stage 2 flow engine; until then the SDK offers normal chat/ticket). */
    val hasFlowBot: Boolean,
    val allowMessagesAfterResolved: Boolean,
    val lockToSingleConversation: Boolean,
    val enabledLanguages: List<LanguageOption>,
    val enabledFeatures: EnabledFeatures,
    /** Server account locale (`fa`). */
    val locale: String?,
    val disableBranding: Boolean,
) {
    /** True when the inbox is staffed right now (always true when working hours are disabled). */
    public fun isInWorkingHours(nowMillis: Long = System.currentTimeMillis()): Boolean =
        !workingHoursEnabled || WorkingHoursCalculator.isInWorkingHours(this, nowMillis)

    /** Home-screen mode for [nowMillis]; identical rules to the web widget `useTicketMode`. */
    public fun startMode(nowMillis: Long = System.currentTimeMillis()): StartDecision {
        val offHours = workingHoursEnabled && !isInWorkingHours(nowMillis)
        return when (contactMode) {
            ContactMode.TICKET -> StartDecision(StartMode.TICKET, false)
            ContactMode.BOTH -> StartDecision(StartMode.CHOICE, false)
            ContactMode.TICKET_WHEN_OFFLINE ->
                if (offHours) StartDecision(StartMode.TICKET, true) else StartDecision(StartMode.CHAT, false)
            ContactMode.CHAT -> StartDecision(StartMode.CHAT, false)
        }
    }
}
