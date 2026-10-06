package chat.hodhod.sdk.internal.flow

/** Timer abstraction (real: Handler/coroutines; tests: a deterministic fake clock). */
internal interface FlowScheduler {
    fun setTimeout(ms: Long, fn: () -> Unit): Any
    fun clearTimeout(id: Any)
}

/** Runtime event (flow-options spec 2.11): the single source analytics read. JSON shape = [toMap]. */
internal class FlowRuntimeEvent(
    val name: String, val ts: Long, val seq: Int, val sessionId: String, val botId: Any?, val revision: Int?,
    val props: Map<String, Any?>, val nodeId: String?, val kind: String?,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "name" to name, "ts" to ts, "seq" to seq, "session_id" to sessionId,
        "flow" to mapOf("bot_id" to botId, "revision" to revision), "props" to props,
    ).also { m ->
        if (nodeId != null) m["node_id"] = nodeId
        if (kind != null) m["kind"] = kind
    }
}

/** What the engine hands to the host when the visitor asks for a human (web `onRequestAgent` payload). */
internal class HandoffPayload(
    val reason: String, val teamId: Any?, val ticket: Boolean, val flowPath: String,
    val summarySteps: List<Map<String, Any?>>, val trace: List<Map<String, String>>, val vars: Map<String, Any?>,
    val sessionId: String, val revision: Int?, val offline: Map<String, String>?,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "reason" to reason, "teamId" to teamId, "ticket" to ticket, "flowPath" to flowPath,
        "summary" to mapOf("steps" to summarySteps), "trace" to trace, "vars" to vars, "sessionId" to sessionId, "revision" to revision,
    ).also { if (offline != null) it["offline"] = offline }
}

internal class AgentResult(val ok: Boolean = true, val conversationId: Any? = null)

/** Result of the server webhook proxy for one webhook node (`{ok, status, vars, cached, reason}`). */
internal class WebhookResult(val ok: Boolean, val vars: Map<String, Any?> = emptyMap(), val reason: String? = null, val cached: Boolean = false)

/** Session store for the machine (`{load, save, clear}`). */
internal interface FlowPersistence {
    fun load(): JMap?
    fun save(state: JMap)
    fun clear()
}

internal class BotInfo(val name: String?, val avatarUrl: String?)

/** Handoff body sent in `flow` of POST /widget/messages (web `toFlowParam`). */
internal fun HandoffPayload.toFlowParam(): Map<String, Any?> = linkedMapOf<String, Any?>(
    "session_id" to sessionId, "schema" to SCHEMA_VERSION, "revision" to revision, "reason" to reason, "trace" to trace, "vars" to vars,
).also { if (offline != null) it["offline"] = offline }
