package chat.hodhod.sdk

import kotlinx.coroutines.flow.StateFlow

/** Bot identity shown above the flow (name + avatar). */
public data class FlowIdentity(val name: String, val avatarUrl: String?)

/** Extra link button under a bot message (`https:`/`http:`/`mailto:`/`tel:` only; already template-rendered). */
public data class FlowButton(val id: String?, val label: String, val url: String)

/**
 * One bot bubble. [rich] is the markdown-lite token tree (never HTML):
 * block = `{type:"p", children:[token]}` or `{type:"ul"|"ol", items:[[token]]}`;
 * token = `{type:"text", value}` | `{type:"br"}` | `{type:"code", value}` | `{type:"strong"|"em", children}` | `{type:"link", href, children, newTab}`.
 * [kind] is `bot` or `idle` (the inactivity nudge).
 */
public data class FlowBlock(
    val key: String,
    val nodeId: String?,
    val rich: List<Map<String, Any?>>,
    val kind: String,
    val image: String? = null,
    val imageAlt: String? = null,
    val buttons: List<FlowButton> = emptyList(),
)

/** Back / restart availability. [agentLink] = offer the "talk to an agent" shortcut under an end/info terminal. */
public data class FlowNav(val canBack: Boolean, val canRestart: Boolean, val agentLink: Boolean)

/**
 * What the visitor can do now. [type] and [fields] mirror the web view-model (`shared/composables/chatbotFlow/view.js`):
 * `menu`(rows[id,label,emoji,index,kind]), `choice`(options[id,label,index], style, allowOther, otherLabel, otherValue, otherOpen),
 * `multichoice`(options[id,label,selected], min, max, canSubmit, doneLabel, ...), `input`(stage typing|confirm, inputType, label, placeholder,
 * required, skippable, skipLabel, maxLength?, min?, max?, value, pendingValue, error), `yesno`(yesLabel, noLabel), `rating`(scale
 * stars5|emoji5|nps10|thumbs, min, max, value, lowLabel, highLabel), `continue`(label), `feedback`(stage asking|reason, question, yesLabel, noLabel,
 * reasonPrompt, reasons[id,label]), `offline`(mode, trigger, message, fields[name,required,value,error], submitting, error, successMessage),
 * `pending`(label), `deadend`(text), `terminal`(variant info|end|resolved, glad?, primaryRestart), `agent`(variant, label, ticket),
 * `handoff-error`. When the idle nudge offers an agent, `fields["extra"]` = `{agentButton:true}`.
 */
public class FlowControl(public val type: String, public val fields: Map<String, Any?>) {
    public fun str(key: String): String = (fields[key] as? String).orEmpty()
    public fun bool(key: String): Boolean = fields[key] == true
    public fun int(key: String): Int? = (fields[key] as? Number)?.toInt()

    @Suppress("UNCHECKED_CAST")
    public fun list(key: String): List<Map<String, Any?>> = (fields[key] as? List<Map<String, Any?>>).orEmpty()

    override fun toString(): String = "FlowControl($type)"
}

/** Everything the runner draws: bot bubbles + the current control. */
public data class FlowView(
    val identity: FlowIdentity?,
    val breadcrumb: String,
    val blocks: List<FlowBlock>,
    val typing: Boolean,
    val control: FlowControl?,
    val nav: FlowNav,
    val announce: String,
    val focusKey: String,
)

/** Loading state of the flow for the inbox. */
public enum class FlowStatus {
    /** Not loaded yet. */
    IDLE,

    /** Fetching the definition. */
    LOADING,

    /** The inbox has no (usable) flow, the triggers hide it or the schema is newer than this SDK: offer normal chat/ticket. */
    NONE,

    /** The flow is running; draw [HodhodFlowEngine.view]. */
    READY,
}

/**
 * Chatbot-flow engine (port of the web `useChatbotFlow` + `ChatbotFlowRunner`). The host UI calls [load] when the home screen opens, draws
 * [view] while [status] is READY and forwards the visitor's actions. When the visitor asks for a human the engine starts the conversation
 * (live) or creates the ticket exactly like the web widget: the first message is posted with the `flow` handoff body and the server applies
 * team / labels / attributes / ticket from the stored definition. Analytics are sent to `/api/v1/widget/chatbot_flow_events`.
 *
 * All methods must be called on the main thread.
 */
public interface HodhodFlowEngine {
    /** True when an engine implementation is installed and the inbox has an active flow. */
    public val isActive: StateFlow<Boolean>

    /** Loading state (see [FlowStatus]). */
    public val status: StateFlow<FlowStatus>

    /** Current screen, non-null while [status] is READY. */
    public val view: StateFlow<FlowView?>

    /** The flow must be completed (the direct "start chat" shortcut is hidden until the visitor reaches a handoff). */
    public val requireFlow: StateFlow<Boolean>

    /** True after the handoff succeeded and the conversation exists (host switches to the chat). */
    public val handedOff: StateFlow<Boolean>

    /** Fetch the definition, evaluate triggers, resume a saved session or start. Idempotent while a session is active. */
    public suspend fun load()

    /** Chrome-text translator: key relative to `CHATBOT_FLOW.*` (e.g. `DEAD_END_TEXT`, `INPUT.ERROR_MIN_LENGTH`) with `{name}` params. */
    public fun setTranslator(translate: (key: String, params: Map<String, Any?>) -> String)

    /** Skip typing/delay animations (system "remove animations"); takes effect for the next flow load. */
    public fun setReducedMotion(reduced: Boolean)

    public fun selectOption(nodeId: String)
    public fun chooseOption(optionId: String)
    public fun toggleOption(optionId: String)
    public fun setOtherText(text: String)
    public fun submitOther()
    public fun submitMulti()
    public fun answerYesNo(yes: Boolean)
    public fun setInputValue(text: String)
    public fun submitInput()
    public fun editInput()
    public fun confirmInput()
    public fun skipInput()
    public fun submitRating(value: Int)
    public fun continueNext()
    public fun answerFeedback(helpful: Boolean)
    public fun chooseReason(reasonId: String?)
    public fun submitOffline(fields: Map<String, String>)
    public fun leaveMessage()
    public fun requestAgent(reason: String = "manual")
    public fun retryHandoff()
    public fun goBack()
    public fun restart(via: String = "nav")
    public fun markLinkClick(buttonId: String?, url: String?)
    public fun markActivity()

    /**
     * The visitor tapped the direct "start chat" card while a flow session exists: marks it (`handoff_request` reason manual) and stores the
     * pending flow body for the first message (web `prepareDirectStart`).
     */
    public fun prepareDirectStart()

    /** Conversation ended / logout: forget the saved session and load again from scratch (web `clearFlowState`). */
    public fun reset()
}
