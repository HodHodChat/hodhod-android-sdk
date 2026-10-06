package chat.hodhod.sdk.internal.flow

import chat.hodhod.sdk.FlowControl

// Port of shared/composables/chatbotFlow/{view,handoff}.js: view-model controls and the offline step helpers.

internal typealias Translate = (key: String, params: Map<String, Any?>) -> String

/** Overlay = a non-node screen layered on the current stop (terminal, pending webhook, agent connecting, offline form...). */
internal data class Overlay(
    val type: String,
    val variant: String? = null,
    val glad: String? = null,
    val primaryRestart: Boolean = false,
    val label: String = "",
    val ticket: Boolean = false,
    val trigger: String? = null,
    val reason: String? = null,
    val teamId: Any? = null,
    val values: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val submitting: Boolean = false,
    val error: String = "",
    val prev: Overlay? = null,
)

/** → 'closed' | 'no_agents' | null */
internal fun offlineTrigger(s: FlowSettings, ctx: FlowContext): String? {
    val o = s.offline
    if (o.mode == "none") return null
    val closed = !ctx.businessOpen
    val noAgents = !closed && !ctx.agentsOnline
    if (o.trigger == "closed") return if (closed) "closed" else null
    if (o.trigger == "no_agents") return if (noAgents) "no_agents" else null
    if (closed) return "closed"
    return if (noAgents) "no_agents" else null
}

internal class OfflineField(val name: String, val required: Boolean)

private fun knownContact(ctx: FlowContext, name: String): String = when (name) {
    "name" -> ctx.contactName
    "email" -> ctx.contactEmail
    "phone" -> ctx.contactPhone
    else -> ""
}

internal fun offlineFieldDefs(s: FlowSettings, ctx: FlowContext): List<OfflineField> {
    val o = s.offline
    val defs = o.collect.filter { knownContact(ctx, it).isEmpty() }.map { OfflineField(it, it in o.required) }
    return defs + OfflineField("message", true)
}

internal class OfflineValidation(val ok: Boolean, val values: Map<String, String>, val errors: Map<String, InputResult>, val channelMissing: Boolean)

internal fun validateOffline(s: FlowSettings, ctx: FlowContext, raw: Map<String, String>): OfflineValidation {
    val types = mapOf("name" to "text", "email" to "email", "phone" to "phone")
    val values = LinkedHashMap<String, String>()
    val errors = LinkedHashMap<String, InputResult>()
    for (def in offlineFieldDefs(s, ctx)) {
        val node: JMap = if (def.name == "message") mapOf("inputType" to "longtext", "required" to true, "maxLength" to 2000)
        else mapOf("inputType" to types[def.name], "required" to def.required, "maxLength" to 100)
        val res = validateInput(node, raw[def.name])
        if (!res.ok) errors[def.name] = res else if (res.value.isNotEmpty()) values[def.name] = res.value
    }
    val hasChannel = values["email"] != null || values["phone"] != null || ctx.contactEmail.isNotEmpty() || ctx.contactPhone.isNotEmpty()
    return OfflineValidation(errors.isEmpty() && hasChannel, values, errors, !hasChannel)
}

private val FORMAT_KEYS = mapOf(
    "email" to "INPUT.ERROR_EMAIL", "phone" to "INPUT.ERROR_PHONE", "number" to "INPUT.ERROR_NUMBER",
    "date" to "INPUT.ERROR_DATE", "url" to "INPUT.ERROR_URL",
)

internal fun inputErrorText(t: Translate, inputType: String?, res: InputResult): String = when (res.reason) {
    "required" -> t("INPUT.ERROR_REQUIRED", emptyMap())
    "minlength" -> t("INPUT.ERROR_MIN_LENGTH", mapOf("min" to res.params["min"]))
    "maxlength" -> t("INPUT.ERROR_MAX_LENGTH", mapOf("max" to res.params["max"]))
    "min" -> if (inputType == "number") t("INPUT.ERROR_NUMBER_MIN", mapOf("min" to res.params["min"])) else t("INPUT.ERROR_DATE", emptyMap())
    "max" -> if (inputType == "number") t("INPUT.ERROR_NUMBER_MAX", mapOf("max" to res.params["max"])) else t("INPUT.ERROR_DATE", emptyMap())
    "format" -> t(FORMAT_KEYS[inputType] ?: "INPUT_INVALID", emptyMap())
    else -> (res.params["cue"] as? String)?.takeIf { it.isNotEmpty() } ?: t("INPUT_INVALID", emptyMap())
}

/** State snapshot the control is derived from (everything `buildControl(s)` receives in JS). */
internal class ControlInput(
    val overlay: Overlay?, val node: FlowNode?, val rows: List<Map<String, Any?>>, val ready: Boolean, val settings: FlowSettings,
    val context: FlowContext, val t: Translate, val render: (String?) -> String,
    val inputStage: String, val inputDraft: String, val pendingInput: String?, val inputError: String,
    val feedbackStage: String, val multiSel: List<String>, val otherOpen: Boolean, val otherValue: String,
    val ratingValue: Long?, val idleAgent: Boolean,
)

@Suppress("UNCHECKED_CAST")
private fun optionList(d: JMap): List<JMap> = (d["options"] as? List<Any?>).orEmpty().mapNotNull { it as? JMap }

private fun ctl(type: String, vararg pairs: Pair<String, Any?>): FlowControl = FlowControl(type, linkedMapOf(*pairs).filterValues { it !== Undefined }.let { LinkedHashMap(it) })

/** Marker for JS `undefined` (omitted from the map). */
internal object Undefined

private fun undefIf(cond: Boolean, v: Any?): Any? = if (cond) Undefined else v

private fun feedbackControl(s: ControlInput, node: FlowNode): FlowControl {
    val f = s.settings.feedback
    return ctl(
        "feedback", "nodeId" to node.id, "stage" to s.feedbackStage,
        "question" to s.render(f.question).ifEmpty { s.t("DID_THIS_HELP", emptyMap()) },
        "yesLabel" to s.render(f.yesLabel).ifEmpty { s.t("YES_RESOLVED", emptyMap()) },
        "noLabel" to s.render(f.noLabel).ifEmpty { s.t("NO_NEED_AGENT", emptyMap()) },
        "reasonPrompt" to s.render(f.reasonPrompt).ifEmpty { s.t("REASON_PROMPT", emptyMap()) },
        "reasons" to f.reasons.map { (id, label) -> mapOf("id" to id, "label" to s.render(label)) },
    )
}

private fun inputControl(s: ControlInput, node: FlowNode): FlowControl {
    val d = node.data
    val type = (d["inputType"] as? String)?.takeIf { it.isNotEmpty() } ?: "text"
    val defaultMax = if (type == "longtext") 2000 else 500
    val required = d["required"] != false
    val textual = type == "text" || type == "longtext"
    return ctl(
        "input", "nodeId" to node.id, "stage" to s.inputStage, "inputType" to type, "label" to s.render(d["label"] as? String),
        "placeholder" to s.render(d["placeholder"] as? String), "required" to required, "skippable" to !required,
        "skipLabel" to s.render(d["skipLabel"] as? String).ifEmpty { s.t("SKIP", emptyMap()) },
        "maxLength" to undefIf(!textual, if (truthy(d["maxLength"])) d["maxLength"] else defaultMax),
        "min" to undefIf(!d.containsKey("min"), d["min"]),
        "max" to undefIf(!d.containsKey("max"), d["max"]),
        "value" to s.inputDraft, "pendingValue" to s.pendingInput, "error" to s.inputError,
    )
}

private fun choiceControl(s: ControlInput, node: FlowNode): FlowControl {
    val d = node.data
    val options = optionList(d).mapIndexed { i, o -> mapOf("id" to o["id"], "label" to s.render(o["label"] as? String), "index" to i + 1) }
    val style = (d["style"] as? String)?.takeIf { it.isNotEmpty() } ?: "list"
    val base = arrayOf(
        "nodeId" to node.id, "style" to style, "allowOther" to (d["allowOther"] == true),
        "otherLabel" to s.render(d["otherLabel"] as? String).ifEmpty { s.t("OTHER", emptyMap()) }, "otherValue" to s.otherValue,
    )
    if (d["multiple"] != true) return ctl("choice", *base, "options" to options, "otherOpen" to s.otherOpen)
    val max = if (truthy(d["maxSelect"])) d["maxSelect"] else options.size
    val min = if (truthy(d["minSelect"])) d["minSelect"] else 1
    val picked = s.multiSel.size + if (s.otherOpen && jsTrim(s.otherValue).isNotEmpty()) 1 else 0
    return ctl(
        "multichoice", *base,
        "options" to options.map { mapOf("id" to it["id"], "label" to it["label"], "selected" to (it["id"] in s.multiSel)) },
        "otherOpen" to s.otherOpen, "min" to min, "max" to max,
        "canSubmit" to (picked >= jsToNumber(min) && picked <= jsToNumber(max)),
        "doneLabel" to s.render(d["doneLabel"] as? String).ifEmpty { s.t("DONE", emptyMap()) },
    )
}

private fun ratingControl(s: ControlInput, node: FlowNode): FlowControl {
    val d = node.data
    val scale = (d["scale"] as? String)?.takeIf { it.isNotEmpty() } ?: "stars5"
    val range = RATING_RANGES[d["scale"]] ?: RATING_RANGES.getValue("stars5")
    return ctl(
        "rating", "nodeId" to node.id, "scale" to scale, "min" to range.min, "max" to range.max, "value" to s.ratingValue,
        "lowLabel" to s.render(d["lowLabel"] as? String), "highLabel" to s.render(d["highLabel"] as? String),
    )
}

private fun offlineControl(s: ControlInput, o: Overlay): FlowControl {
    val off = s.settings.offline
    val form = off.mode == "form"
    val fields = if (form) offlineFieldDefs(s.settings, s.context).map {
        mapOf("name" to it.name, "required" to it.required, "value" to (o.values[it.name] ?: ""), "error" to (o.errors[it.name] ?: ""))
    } else emptyList()
    return ctl(
        "offline", "mode" to off.mode, "trigger" to o.trigger, "message" to s.render(off.message), "fields" to fields,
        "submitting" to o.submitting, "error" to o.error,
        "successMessage" to s.render(off.successMessage).ifEmpty { s.t("OFFLINE.SUCCESS", emptyMap()) },
    )
}

private fun overlayControl(s: ControlInput, o: Overlay): FlowControl? = when (o.type) {
    "auto" -> null
    "pending" -> ctl("pending", "label" to o.label)
    "deadend" -> ctl("deadend", "text" to s.render(s.settings.fallbackMessage).ifEmpty { s.t("DEAD_END_TEXT", emptyMap()) })
    "terminal" -> ctl("terminal", "variant" to o.variant, "glad" to (o.glad ?: Undefined), "primaryRestart" to o.primaryRestart)
    "offline" -> offlineControl(s, o)
    "agent" -> ctl("agent", "variant" to "connecting", "label" to o.label, "ticket" to o.ticket)
    else -> ctl("handoff-error")
}

/** The current control, or null while the blocks are still being "typed" (not [ControlInput.ready]). */
internal fun buildControl(s: ControlInput): FlowControl? {
    val o = s.overlay
    if (o != null && (o.type == "agent" || o.type == "handoff-error")) return overlayControl(s, o)
    if (!s.ready) return null
    var control: FlowControl? = null
    val node = s.node
    if (o != null) control = overlayControl(s, o)
    else if (node == null) control = ctl("menu", "rows" to s.rows)
    else when (node.kind) {
        "topic", "subtopic" -> control = ctl("menu", "rows" to s.rows)
        "answer" -> control = feedbackControl(s, node)
        "message" -> control = ctl("continue", "nodeId" to node.id, "label" to s.render(node.data["continueLabel"] as? String).ifEmpty { s.t("CONTINUE", emptyMap()) })
        "input" -> control = inputControl(s, node)
        "yesno" -> control = ctl(
            "yesno", "nodeId" to node.id, "yesLabel" to s.render(node.data["yesLabel"] as? String).ifEmpty { s.t("YES", emptyMap()) },
            "noLabel" to s.render(node.data["noLabel"] as? String).ifEmpty { s.t("NO", emptyMap()) },
        )
        "choice" -> control = choiceControl(s, node)
        "rating" -> control = ratingControl(s, node)
    }
    return if (control != null && s.idleAgent) FlowControl(control.type, control.fields + ("extra" to mapOf("agentButton" to true))) else control
}
