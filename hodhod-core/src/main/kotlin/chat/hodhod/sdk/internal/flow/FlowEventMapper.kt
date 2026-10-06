package chat.hodhod.sdk.internal.flow

// Port of widget/helpers/flowEventMapper.js: runtime event -> analytics wire event (privacy allow-list; no labels/typed values ever leave).

private const val MAX_DWELL_MS = 3_600_000L
private const val MAX_INT = 2_147_483_647L
private val ID_PATTERN = Regex("^[A-Za-z0-9_:.$-]{1,64}\\z")
private const val DONE_REF = "done"
private const val OTHER_REF = "other"

private val HANDOFF_REASONS = mapOf(
    "agent_node" to "agent_node", "not_helpful" to "no_help", "dead_end" to "dead_end", "unconnected" to "dead_end",
    "offline_form" to "offline", "idle" to "idle", "manual" to "direct_button",
)
private val RESOLVED_VIA = mapOf("answer_yes" to "feedback_yes", "end" to "end_node", "implicit_end" to "implicit_end")
private val INPUT_FAILURES = mapOf(
    "required" to "required", "format" to "type", "min" to "range", "max" to "range", "minlength" to "min_length", "maxlength" to "max_length",
    "regex" to "pattern",
)
private val WEBHOOK_FAILURES = mapOf(
    "blocked" to "blocked", "timeout" to "timeout", "http" to "http_error", "too_large" to "invalid_response", "invalid_json" to "invalid_response",
    "throttled" to "throttled", "budget" to "throttled",
)
private val AUTO_VIA = listOf("auto", "goto", "trigger")
private val SILENT_VIA = listOf("back", "resume")
private val VIA_OPTION_KINDS = listOf("choice", "yesno", "answer_feedback")

/** Per-session mapper state (current node, entry time, handle we left the previous node with). */
internal class MapperState {
    var sessionId: String? = null
    var current: String? = null
    var currentSince: Long? = null
    var currentPassthrough = false
    var pendingVia: String? = null
    var terminal = false

    fun reset() {
        sessionId = null
        current = null
        currentSince = null
        currentPassthrough = false
        pendingVia = null
        terminal = false
    }
}

internal class MapResult(val events: List<Map<String, Any?>>, val flushNow: Boolean = false, val terminal: Boolean = false, val ctx: Map<String, Any?>? = null)

private fun cleanId(v: Any?): String? = (v as? String)?.takeIf { ID_PATTERN.matches(it) }

private fun cleanInt(v: Any?, max: Long = MAX_INT): Long? {
    val n = jsToNumber(v)
    return if (v != null && n.isFinite() && n >= 0 && n <= max) Math.floor(n + 0.5).toLong() else null
}

private fun syncSession(state: MapperState, sessionId: String?) {
    if (sessionId.isNullOrEmpty()) return
    if (state.sessionId != null && state.sessionId != sessionId) state.reset()
    state.sessionId = sessionId
}

private fun requiredNode(state: MapperState, nodeId: String?): String? = cleanId(nodeId?.takeIf { it.isNotEmpty() } ?: state.current ?: MENU_ID)

private fun dwellOnCurrent(state: MapperState, ts: Long): Long? {
    val since = state.currentSince
    if (state.current == null || since == null) return null
    if (state.currentPassthrough) return null
    val ms = ts - since
    return if (ms in 0 until MAX_DWELL_MS) ms else null
}

private fun moveTo(state: MapperState, node: String?, ts: Long, passthrough: Boolean = false) {
    state.current = node ?: MENU_ID
    state.currentSince = ts
    state.currentPassthrough = passthrough
    state.pendingVia = null
}

private fun wire(evt: FlowRuntimeEvent, type: String, vararg fields: Pair<String, Any?>): Map<String, Any?> {
    val out = linkedMapOf<String, Any?>("seq" to evt.seq, "type" to type, "t" to evt.ts)
    fields.forEach { (k, v) -> if (v != null) out[k] = v }
    return out
}

private fun optionRefs(props: Map<String, Any?>, ids: List<String>): List<String> {
    if (props["option_kind"] == "other") return listOf(OTHER_REF)
    if (ids.isNotEmpty()) return ids
    val doneWithoutPick = (props["option_ids"] as? List<*>)?.isEmpty() == true
    return if (doneWithoutPick) listOf(DONE_REF) else emptyList()
}

private val NOTHING = MapResult(emptyList())

@Suppress("UNCHECKED_CAST")
internal fun mapFlowEvent(state: MapperState, evt: FlowRuntimeEvent): MapResult {
    syncSession(state, evt.sessionId)
    val props = evt.props
    val node = requiredNode(state, evt.nodeId)
    val ownNode = cleanId(evt.nodeId)

    when (evt.name) {
        "flow_start" -> {
            val context = props["context"] as? Map<String, Any?> ?: emptyMap()
            moveTo(state, MENU_ID, evt.ts)
            state.terminal = false
            return MapResult(
                listOf(wire(evt, "flow_start", "ref" to if (props["trigger"] == "restart") "restart" else "open")),
                ctx = mapOf("locale" to context["locale"], "page" to context["page_path"]),
            )
        }
        "node_view" -> {
            if (node == null) return NOTHING
            if (props["via"] in SILENT_VIA) {
                moveTo(state, node, evt.ts)
                return NOTHING
            }
            val passthrough = props["passthrough"] == true
            val left = cleanId(props["from_node_id"] ?: state.current)
            val out = wire(
                evt, "node_view", "node" to node, "from" to if (left == node) null else left,
                "ref" to cleanId(props["handle"] ?: state.pendingVia),
                "auto" to if (passthrough || props["via"] in AUTO_VIA) true else null, "dwell_ms" to dwellOnCurrent(state, evt.ts),
            )
            moveTo(state, node, evt.ts, passthrough)
            return MapResult(listOf(out))
        }
        "option_select" -> {
            if (node == null) return NOTHING
            val ids = ((props["option_ids"] as? List<*>) ?: listOf(props["option_id"])).mapNotNull { cleanId(it) }
            state.pendingVia = if (props["option_kind"] in VIA_OPTION_KINDS) ids.lastOrNull() else null
            if (props["option_kind"] == "answer_feedback") return NOTHING
            return MapResult(optionRefs(props, ids).map { wire(evt, "option_select", "node" to node, "ref" to it) })
        }
        "input_submit" -> return if (node != null) MapResult(listOf(wire(evt, "input_submit", "node" to node))) else NOTHING
        "input_invalid" -> return if (node != null) MapResult(listOf(wire(evt, "input_invalid", "node" to node, "ref" to (INPUT_FAILURES[props["reason"]] ?: "other")))) else NOTHING
        "input_skip" -> return if (node != null) MapResult(listOf(wire(evt, "input_skip", "node" to node))) else NOTHING
        "feedback" -> return if (node != null) MapResult(listOf(wire(evt, "feedback", "node" to node, "ok" to (props["helpful"] == true)))) else NOTHING
        "feedback_reason" -> return if (node != null) MapResult(listOf(wire(evt, "feedback_reason", "node" to node, "ref" to cleanId(props["reason_id"])))) else NOTHING
        "back" -> {
            val from = requiredNode(state, (props["from_node_id"] as? String)?.takeIf { it.isNotEmpty() } ?: evt.nodeId) ?: return NOTHING
            val to = cleanId(props["to_node_id"])
            val out = wire(evt, "back", "node" to from, "ref" to to, "dwell_ms" to dwellOnCurrent(state, evt.ts))
            moveTo(state, to, evt.ts)
            return MapResult(listOf(out))
        }
        "restart" -> {
            val out = wire(evt, "restart", "node" to cleanId(props["from_node_id"]), "ref" to cleanId(props["via"]), "dwell_ms" to dwellOnCurrent(state, evt.ts))
            moveTo(state, MENU_ID, evt.ts)
            return MapResult(listOf(out))
        }
        "link_click" -> {
            if (node == null) return NOTHING
            val host = (props["host"] as? String)?.takeIf { it.isNotEmpty() }?.let { cleanId("host:$it") }
            val buttonId = props["button_id"]
            return MapResult(listOf(wire(evt, "link_click", "node" to node, "ref" to if (truthy(buttonId)) cleanId(buttonId) else host)))
        }
        "rating_submit" -> {
            val v = cleanInt(props["value"])
            val ref = cleanId(props["scale"])
            return if (node != null && ref != null && v != null) MapResult(listOf(wire(evt, "rating_submit", "node" to node, "ref" to ref, "val" to v))) else NOTHING
        }
        "condition_eval" -> {
            val ref = cleanId(props["matched"])
            if (node == null || ref == null) return NOTHING
            state.pendingVia = ref
            return MapResult(listOf(wire(evt, "condition_eval", "node" to node, "ref" to ref)))
        }
        "split_assign" -> {
            val ref = cleanId(props["variant_id"])
            if (node == null || ref == null) return NOTHING
            state.pendingVia = ref
            return MapResult(listOf(wire(evt, "split_assign", "node" to node, "ref" to ref)))
        }
        "webhook_call" -> {
            if (node == null) return NOTHING
            val ok = props["status"] == "success"
            state.pendingVia = if (ok) "success" else "error"
            return MapResult(
                listOf(wire(evt, "webhook_call", "node" to node, "ok" to ok, "ref" to if (ok) "ok" else (WEBHOOK_FAILURES[props["reason"]] ?: "other"), "dwell_ms" to cleanInt(props["duration_ms"], MAX_DWELL_MS))),
            )
        }
        "handoff_request" -> {
            if (node == null) return NOTHING
            val out = wire(evt, "handoff_request", "node" to node, "ref" to (HANDOFF_REASONS[props["reason"]] ?: "other"), "dwell_ms" to dwellOnCurrent(state, evt.ts))
            state.terminal = true
            return MapResult(listOf(out), flushNow = true, terminal = true)
        }
        "flow_resolved" -> {
            val out = wire(evt, "flow_resolved", "node" to ownNode, "ref" to (RESOLVED_VIA[props["via"]] ?: "end_node"), "dwell_ms" to dwellOnCurrent(state, evt.ts))
            state.terminal = true
            return MapResult(listOf(out), flushNow = true, terminal = true)
        }
        "dead_end" -> return if (node != null) MapResult(listOf(wire(evt, "dead_end", "node" to node, "ref" to cleanId(props["handle"])))) else NOTHING
        "idle_prompt" -> return MapResult(listOf(wire(evt, "idle_prompt", "node" to ownNode, "ok" to props["offered_agent"]?.let { it == true })))
        "offline_shown" -> return MapResult(listOf(wire(evt, "offline_shown", "node" to ownNode, "ref" to cleanId(props["trigger"]))))
        "offline_submit" -> return MapResult(listOf(wire(evt, "offline_submit", "node" to ownNode, "val" to (props["fields"] as? List<*>)?.size)))
        else -> return NOTHING
    }
}
