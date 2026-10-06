package chat.hodhod.sdk.internal.flow

// Port of shared/helpers/chatbotFlow/{constants,normalize,migrate,graph}.js.

internal const val SCHEMA_VERSION = 2
internal const val MENU_ID = "\$menu"

internal object Limits {
    const val TRACE = 200
    const val VARS = 40
    const val TEMPLATE_VALUE = 500
}

internal data class RatingRange(val min: Int, val max: Int)

internal val RATING_RANGES = mapOf(
    "stars5" to RatingRange(1, 5), "emoji5" to RatingRange(1, 5), "nps10" to RatingRange(0, 10), "thumbs" to RatingRange(0, 1),
)

/** Node kinds whose `passThrough` is true in kinds.js (the visitor never stops on them). */
internal val PASS_THROUGH_KINDS = setOf("condition", "split", "goto", "webhook", "action")

internal val ALL_KINDS = listOf(
    "topic", "subtopic", "question", "answer", "message", "end", "input", "yesno", "choice", "rating",
    "condition", "split", "goto", "webhook", "action", "agent", "note",
)

internal class FlowNode(val id: String, val data: JMap) {
    val kind: String? get() = data["kind"] as? String
}

internal class FlowEdge(val id: String, val source: String, val target: String, val sourceHandle: String?)

internal data class FeedbackSettings(
    val enabled: Boolean, val question: String, val yesLabel: String, val noLabel: String, val gladMessage: String,
    val askReason: Boolean, val reasonPrompt: String, val reasons: List<Pair<String, String>>,
)

internal data class OfflineSettings(
    val mode: String, val trigger: String, val message: String, val collect: List<String>, val required: List<String>,
    val createTicket: Boolean, val labels: List<String>, val teamId: Int?, val successMessage: String,
)

internal data class UrlRule(val id: String, val match: String, val value: String, val mode: String, val startTopicId: String)
internal data class TriggerSettings(val visitor: String, val hours: String, val locales: List<String>, val urlRules: List<UrlRule>)
internal data class IdleSettings(val enabled: Boolean, val afterSec: Int, val message: String, val action: String)

internal data class FlowSettings(
    val enabled: Boolean, val botName: String, val welcome: String, val typingMs: Int, val fallbackMessage: String,
    val feedback: FeedbackSettings, val offline: OfflineSettings, val triggers: TriggerSettings, val idle: IdleSettings,
)

internal class FlowDoc(
    val nodes: List<FlowNode>, val edges: List<FlowEdge>, val requireFlow: Boolean, val menuOrder: List<String>,
    val settings: FlowSettings, val revision: Int?,
)

@Suppress("UNCHECKED_CAST")
private fun obj(v: Any?): JMap? = v as? Map<String, Any?>
private fun str(v: Any?, d: String = ""): String = if (v is String) v else d
private fun bool(v: Any?, d: Boolean): Boolean = if (v is Boolean) v else d
private fun arr(v: Any?): List<Any?> = (v as? List<*>) ?: emptyList()
private fun strList(v: Any?): List<String> = arr(v).filterIsInstance<String>()

private fun jsRound(d: Double): Double = Math.floor(d + 0.5)

private fun int(v: Any?, min: Int, max: Int, d: Int): Int {
    val n: Any? = if (v is String && jsTrim(v) != "") jsNumber(v) else v
    if (!isFiniteNumber(n)) return d
    return minOf(max.toDouble(), maxOf(min.toDouble(), jsRound((n as Number).toDouble()))).toInt()
}

private fun oneOf(v: Any?, list: List<String>, d: String): String = if (v is String && v in list) v else d

internal fun normalizeTeamId(v: Any?): Int? {
    if (v == null || v == "") return null
    val n = jsToNumber(v)
    return if (isIntegerNumber(n) && n > 0) n.toInt() else null
}

internal fun normalizeSettings(raw: Any?): FlowSettings {
    val s = obj(raw) ?: emptyMap()
    val f = obj(s["feedback"]) ?: emptyMap()
    val o = obj(s["offline"]) ?: emptyMap()
    val t = obj(s["triggers"]) ?: emptyMap()
    val i = obj(s["idle"]) ?: emptyMap()
    val defaultCollect = listOf("name", "phone")
    val collect = strList(o["collect"]).filter { it in listOf("name", "email", "phone") }
    val collectFinal = if (o["collect"] is List<*>) collect else defaultCollect
    return FlowSettings(
        enabled = bool(s["enabled"], true),
        botName = str(s["botName"]),
        welcome = str(s["welcome"]),
        typingMs = int(s["typingMs"], 0, 3000, 0),
        fallbackMessage = str(s["fallbackMessage"]),
        feedback = FeedbackSettings(
            enabled = bool(f["enabled"], true), question = str(f["question"]), yesLabel = str(f["yesLabel"]), noLabel = str(f["noLabel"]),
            gladMessage = str(f["gladMessage"]), askReason = bool(f["askReason"], false), reasonPrompt = str(f["reasonPrompt"]),
            reasons = arr(f["reasons"]).mapNotNull { obj(it) }.map { str(it["id"]) to str(it["label"]) },
        ),
        offline = OfflineSettings(
            mode = oneOf(o["mode"], listOf("none", "message", "form"), "none"),
            trigger = oneOf(o["trigger"], listOf("closed", "no_agents", "either"), "either"),
            message = str(o["message"]),
            collect = collectFinal,
            required = if (o["required"] is List<*>) strList(o["required"]).filter { it in collectFinal } else listOf("phone").filter { it in collectFinal },
            createTicket = bool(o["createTicket"], true),
            labels = if (o["labels"] is List<*>) strList(o["labels"]) else listOf("offline"),
            teamId = normalizeTeamId(o["teamId"]),
            successMessage = str(o["successMessage"]),
        ),
        triggers = TriggerSettings(
            visitor = oneOf(t["visitor"], listOf("all", "new", "returning"), "all"),
            hours = oneOf(t["hours"], listOf("all", "open", "closed"), "all"),
            locales = strList(t["locales"]),
            urlRules = arr(t["urlRules"]).mapNotNull { obj(it) }.map { r ->
                UrlRule(
                    id = str(r["id"]),
                    match = oneOf(r["match"], listOf("contains", "equals", "starts_with", "ends_with", "regex"), "contains"),
                    value = str(r["value"]), mode = oneOf(r["mode"], listOf("show", "hide"), "show"), startTopicId = str(r["startTopicId"]),
                )
            },
        ),
        idle = IdleSettings(
            enabled = bool(i["enabled"], false), afterSec = int(i["afterSec"], 10, 600, 45), message = str(i["message"]),
            action = oneOf(i["action"], listOf("nudge", "offer_agent"), "nudge"),
        ),
    )
}

private fun normalizeNodeData(rawData: Any?): JMap {
    val data = LinkedHashMap<String, Any?>(obj(rawData) ?: emptyMap())
    when (data["kind"]) {
        "topic" -> data["inMenu"] = bool(data["inMenu"], true)
        "answer" -> data["feedback"] = oneOf(data["feedback"], listOf("inherit", "on", "off"), "inherit")
        "message" -> data["autoContinue"] = bool(data["autoContinue"], false)
        "end" -> data["then"] = oneOf(data["then"], listOf("restart", "stay"), "stay")
        "input" -> {
            data["inputType"] = oneOf(data["inputType"], listOf("text", "longtext", "email", "phone", "number", "date", "url"), "text")
            data["required"] = bool(data["required"], true)
            data["confirm"] = bool(data["confirm"], false)
            data["saveTo"] = str(data["saveTo"]).ifEmpty { "conversation_attribute" }
        }
        "choice" -> {
            data["multiple"] = bool(data["multiple"], false)
            data["style"] = oneOf(data["style"], listOf("list", "chips"), "list")
            data["allowOther"] = bool(data["allowOther"], false)
            data["options"] = arr(data["options"]).filter { obj(it) != null }
        }
        "rating" -> data["scale"] = oneOf(data["scale"], listOf("stars5", "emoji5", "nps10", "thumbs"), "stars5")
        "condition" -> data["rules"] = arr(data["rules"]).filter { obj(it) != null }
        "split" -> data["variants"] = arr(data["variants"]).filter { obj(it) != null }
        "action" -> data["actions"] = arr(data["actions"]).filter { obj(it) != null }
        "webhook" -> {
            data["method"] = oneOf(data["method"], listOf("GET", "POST"), "GET")
            data["headers"] = arr(data["headers"]).filter { obj(it) != null }
            data["mappings"] = arr(data["mappings"]).filter { obj(it) != null }
            data["timeoutMs"] = int(data["timeoutMs"], 500, 8000, 5000)
            val c = data["cacheSeconds"]
            data["cacheSeconds"] = if (c is Number && c.toDouble() in listOf(0.0, 30.0, 60.0, 300.0, 900.0, 3600.0)) c else 0
        }
        "agent" -> {
            if (data.containsKey("teamId")) {
                val team = normalizeTeamId(data["teamId"])
                if (team == null) data.remove("teamId") else data["teamId"] = team
            }
            if (data["mode"] == "ticket") {
                val pr = oneOf(data["ticketPriority"], listOf("low", "medium", "high", "urgent"), "")
                if (pr.isEmpty()) data.remove("ticketPriority") else data["ticketPriority"] = pr
                val cat = normalizeTeamId(data["ticketCategoryId"])
                if (cat == null) data.remove("ticketCategoryId") else data["ticketCategoryId"] = cat
            } else {
                listOf("mode", "ticketSubject", "ticketCategoryId", "ticketPriority", "ticketMessage").forEach { data.remove(it) }
            }
        }
        "note" -> data["color"] = oneOf(data["color"], listOf("amber", "violet", "teal", "blue"), "amber")
    }
    return data
}

internal fun edgeId(source: String, target: String, handle: String?): String = "e_${source}_$target${if (!handle.isNullOrEmpty()) "_$handle" else ""}"

/** normalizeFlow(doc): defaults filled, invalid entries dropped. */
internal fun normalizeFlow(doc: Any?): FlowDoc {
    val d = obj(doc) ?: emptyMap()
    val nodes = arr(d["nodes"]).mapNotNull { obj(it) }.filter { it["id"] is String }.map { FlowNode(it["id"] as String, normalizeNodeData(it["data"])) }
    val edges = arr(d["edges"]).mapNotNull { obj(it) }.filter { truthy(it["source"]) && truthy(it["target"]) }.map { e ->
        val handle = e["sourceHandle"] as? String
        val source = jsToString(e["source"])
        val target = jsToString(e["target"])
        FlowEdge(if (truthy(e["id"])) jsToString(e["id"]) else edgeId(source, target, handle), source, target, handle)
    }
    return FlowDoc(
        nodes = nodes, edges = edges, requireFlow = bool(d["require_flow"], false), menuOrder = strList(d["menu_order"]),
        settings = normalizeSettings(d["settings"]), revision = (d["revision"] as? Number)?.takeIf { isIntegerNumber(it) }?.toInt(),
    )
}

internal fun isTooNew(raw: Any?): Boolean = (obj(raw)?.get("schema")).let { it != null && jsToNumber(it) > SCHEMA_VERSION }

@Suppress("UNCHECKED_CAST")
private fun v1to2(raw: JMap): JMap {
    val doc = LinkedHashMap(raw)
    doc["nodes"] = arr(doc["nodes"]).map { n ->
        val node = LinkedHashMap(obj(n) ?: emptyMap())
        val data = LinkedHashMap(obj(node["data"]) ?: emptyMap())
        if (data["kind"] == "input" && data["confirm"] !is Boolean) data["confirm"] = true
        if (data.containsKey("teamId")) data["teamId"] = normalizeTeamId(data["teamId"])
        node["data"] = data
        node
    }
    val messages = obj(doc["messages"]) ?: emptyMap()
    val settings = LinkedHashMap(obj(doc["settings"]) ?: emptyMap())
    val feedback = LinkedHashMap(obj(settings["feedback"]) ?: emptyMap())
    feedback["question"] = str(messages["did_this_help"])
    feedback["yesLabel"] = str(messages["yes_resolved"])
    feedback["noLabel"] = str(messages["no_need_agent"])
    settings["feedback"] = feedback
    doc["settings"] = settings
    doc.remove("messages")
    return doc
}

/** migrateFlow then normalizeFlow (what ChatbotFlowRunner does). Null when the schema is newer than this client understands. */
internal fun migrateAndNormalize(raw: Any?): FlowDoc? {
    val source = obj(raw) ?: emptyMap()
    if (isTooNew(source)) return null
    val schema = source["schema"]
    val isV1 = !truthy(schema) || jsToNumber(schema) < 2
    return normalizeFlow(if (isV1) v1to2(source) else source)
}

/** indexGraph: per-source ordered edges; handle `null` = default handle. */
internal class FlowGraph(nodes: List<FlowNode>, edges: List<FlowEdge>) {
    val byId: Map<String, FlowNode> = LinkedHashMap<String, FlowNode>().also { m -> nodes.forEach { m[it.id] = it } }
    private val outMap = HashMap<String, MutableList<FlowEdge>>()

    init {
        edges.forEach { outMap.getOrPut(it.source) { ArrayList() } += it }
    }

    fun out(id: String): List<FlowEdge> = outMap[id] ?: emptyList()
    fun out(id: String, handle: String?): List<FlowEdge> = out(id).filter { it.sourceHandle == handle }
    fun childrenOf(id: String, handle: String?): List<FlowNode> = out(id, handle).mapNotNull { byId[it.target] }
}
