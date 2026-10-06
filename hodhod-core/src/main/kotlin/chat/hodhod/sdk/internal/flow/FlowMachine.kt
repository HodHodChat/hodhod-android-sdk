package chat.hodhod.sdk.internal.flow

import chat.hodhod.sdk.FlowBlock
import chat.hodhod.sdk.FlowButton
import chat.hodhod.sdk.FlowControl
import chat.hodhod.sdk.FlowIdentity
import chat.hodhod.sdk.FlowNav
import chat.hodhod.sdk.FlowView

// Line-by-line port of shared/composables/useChatbotFlow.js (+ chatbotFlow/{trail,persistence,idle}.js). The machine is single-threaded and
// deterministic: every environment dependency (timers, clock, uuid, webhook, handoff, storage) is injected. Async results are queued as
// "microtasks" and run by [drain] at the end of every entry point (public action, timer callback, delivered result).

private const val MAX_CHAIN = 100
private const val MAX_VISITS = 50
private const val MAX_EVENTS_DEBUG = 200
private const val AUTO_CONTINUE_MS = 350L
private const val RATING_NEXT_MS = 600L
private const val OTHER_MAX = 120
private const val WEBHOOK_VARS = 20
private const val PICK_LABEL_MAX = 80
private const val SAVE_VERSION = 2
private const val RESUME_TTL_MS = 25 * 60 * 1000L
private const val WRITE_DEBOUNCE_MS = 100L
private val INTERACTIVE_CONTROLS = setOf("menu", "choice", "multichoice", "yesno", "input", "rating", "feedback", "continue", "deadend")
private val AUTO_OPEN_KINDS = setOf("answer", "input", "yesno", "choice", "message", "rating", "condition", "split", "action", "webhook", "goto", "end")
private val PATH_KINDS = setOf("topic", "subtopic", "question", "input", "yesno", "choice", "rating")
private val GENERATED_NAME = Regex("^[a-z]+-[a-z]+-\\d+\\z")

private const val UNSET = "\u0000unset"
private const val NO_NODE = "\u0000none"

private fun usable(v: Any?): Boolean = v is String || v is Number || (v is List<*> && v.all { it is String })

internal fun realContactName(name: String?): String = if (!name.isNullOrEmpty() && !GENERATED_NAME.matches(name)) name else ""
internal fun urlPath(url: String): String = parseUrl(url)?.pathname ?: ""
internal fun urlHost(url: String?): String = if (url == null) "" else parseUrl(url)?.hostname ?: ""

internal fun pathLabel(text: String): String = jsTrim(Regex("[$JS_WS_CLASS]+").replace(text, " ").replace(">", "›"))

internal class TrailEntry(val id: String, val auto: Boolean, val via: String, val handle: String?, val key: Int, val revisit: Boolean) {
    fun copy(handle: String?) = TrailEntry(id, auto, via, handle, key, revisit)
}

private class Block(val key: String, val nodeId: String?, val rich: List<Map<String, Any?>>, val kind: String, val ms: Long) {
    var image: String? = null
    var imageAlt: String? = null
    var buttons: List<FlowButton> = emptyList()
}

internal class FlowMachine(
    private val doc: FlowDoc,
    private val context: () -> FlowContext,
    private val t: Translate,
    initialSessionId: String?,
    private val flowId: Any? = null,
    private val revision: Int? = null,
    private val onRequestAgent: ((HandoffPayload, (Result<AgentResult?>) -> Unit) -> Unit)? = null,
    private val onEvent: ((FlowRuntimeEvent) -> Unit)? = null,
    private val runWebhook: ((FlowNode, Map<String, Any?>, (WebhookResult?) -> Unit) -> Unit)? = null,
    private val persistence: FlowPersistence? = null,
    private val scheduler: FlowScheduler,
    private val reducedMotion: Boolean = false,
    private val instant: Boolean = false,
    private val bot: BotInfo? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { java.util.UUID.randomUUID().toString() },
    private val isHidden: () -> Boolean = { false },
    /** Called after every state change so the host can re-read [view]. */
    private val onChange: () -> Unit = {},
) {
    private val cfg = doc.settings
    private val graph = FlowGraph(doc.nodes, doc.edges)
    private fun ctx() = context()
    private fun noMotion() = instant || reducedMotion
    private fun getNode(id: String?): FlowNode? = if (id == null) null else graph.byId[id]
    private fun kindOf(n: FlowNode?): String? = n?.kind

    // ---- session ----
    var sessionId: String = initialSessionId ?: newUuid()
        private set
    var startedAt: Long = now()
        private set
    var restarts = 0
        private set
    private var seq = 0
    private var started = false
    private var destroyed = false
    private var terminalReached = false
    private var manualMarked = false
    private var keyCounter = 0
    private fun nextKey() = ++keyCounter

    // ---- path state ----
    var trail: List<TrailEntry> = emptyList()
        private set
    private var values: Map<String, JMap> = emptyMap()
    private var picks: Map<String, String> = emptyMap()
    private var varsSet: Map<String, JMap> = emptyMap()
    private var varOrder: List<String> = emptyList()
    private var splits: Map<String, String> = emptyMap()
    private var webhookStatus: Map<String, String> = emptyMap()
    private var visited: Set<String> = emptySet()
    private val visitCounts = HashMap<String, Int>()
    private val webhookCache = HashMap<String, WebhookResult>()
    private var feedbackHelpful: Boolean? = null
    private var feedbackReason = ""
    private var post: Map<Int, List<String>> = emptyMap()
    private var prelude: List<Triple<String, String, String>> = emptyList()
    private var pendingPrelude: List<Triple<String, String, String>> = emptyList()
    private var menuEpoch = 0

    // ---- current stop ----
    private var overlay: Overlay? = null
    private var inputDraft = ""
    private var inputStage = "typing"
    private var pendingInput: String? = null
    private var inputError = ""
    private var feedbackStage = "asking"
    private var multiSel: List<String> = emptyList()
    private var otherOpen = false
    private var otherValue = ""
    private var ratingValue: Long? = null
    private var idleShown = false
    private var idleAgent = false
    private var focusSerial = 0
    var handoffState: String? = null
        private set
    private val eventLog = ArrayList<FlowRuntimeEvent>()

    // ---- timers ----
    private var stepToken = 0
    private var webhookToken = 0
    private var revealTimer: Any? = null
    private var autoTimer: Any? = null
    private var revealCbs = ArrayList<() -> Unit>()
    private val revealed = LinkedHashSet<String>()
    private var typing = false
    private var lastHandoff: Triple<String, Any?, Pair<Boolean, Map<String, String>?>>? = null
    private var lastHandoffOffline: Map<String, String>? = null

    // ---- microtasks ----
    private val micro = ArrayDeque<() -> Unit>()
    private var draining = false
    private fun enqueue(fn: () -> Unit) {
        micro.addLast(fn)
    }

    fun drain() {
        if (draining) return
        draining = true
        try {
            while (micro.isNotEmpty()) micro.removeFirst()()
        } finally {
            draining = false
        }
        onChange()
    }

    private fun timer(ms: Long, fn: () -> Unit): Any = scheduler.setTimeout(ms) {
        fn()
        drain()
    }

    // ================= derived =================
    private val rootTopics: List<FlowNode>
        get() {
            val topics = doc.nodes.filter { it.kind == "topic" && it.data["inMenu"] != false }
            val listed = doc.menuOrder.mapNotNull { id -> topics.firstOrNull { it.id == id } }
            return listed + topics.filter { it.id !in doc.menuOrder }
        }

    private fun builtins(): Map<String, Any?> {
        val c = ctx()
        val url = c.pageUrl
        return linkedMapOf(
            "contact.name" to realContactName(c.contactName), "contact.email" to c.contactEmail, "contact.phone" to c.contactPhone,
            "page.url" to url, "page.path" to urlPath(url), "page.host" to urlHost(url), "locale" to c.locale, "device" to c.device,
            "visitor.returning" to if (c.returning) "true" else "false", "hours.state" to if (c.businessOpen) "open" else "closed",
            "agents.online" to if (c.agentsOnline) "yes" else "no", "session.id" to sessionId,
            "bot.name" to cfg.botName.ifEmpty { bot?.name.orEmpty() },
            "feedback.helpful" to (feedbackHelpful?.let { if (it) "yes" else "no" } ?: ""), "feedback.reason" to feedbackReason,
        )
    }

    private fun userVars(): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        varOrder.forEach { id ->
            val v = values[id]
            if (v != null && truthy(v["variable"]) && v["value"] != Undefined) out[v["variable"] as String] = v["value"]
            varsSet[id]?.let { out.putAll(it) }
        }
        return out
    }

    fun variables(): Map<String, Any?> = LinkedHashMap(userVars()).also { it.putAll(builtins()) }

    private fun render(text: String?): String = renderTemplate(text ?: "", variables(), "text")
    private fun richOf(text: String?): List<Map<String, Any?>> = renderRich(text ?: "", variables())
    private fun safeUrl(tpl: String?, protocols: List<String>): String {
        val url = renderTemplate(tpl ?: "", variables(), "url")
        return if (isAllowedUrl(url, protocols)) url else ""
    }

    private fun lastEntry(): TrailEntry? = trail.lastOrNull()
    private fun currentNode(): FlowNode? = lastEntry()?.let { getNode(it.id) }
    private fun hasNext(n: FlowNode, handle: String?) = graph.childrenOf(n.id, handle).isNotEmpty()
    private fun isPass(n: FlowNode): Boolean =
        n.kind in PASS_THROUGH_KINDS || (n.kind == "message" && n.data["autoContinue"] == true && hasNext(n, null))

    private fun menuKids(n: FlowNode): List<FlowNode> {
        val kids = graph.childrenOf(n.id, null)
        val visible = kids.filter { !isPass(it) }
        return visible.ifEmpty { kids }
    }

    private fun rowNodes(): List<FlowNode> = currentNode()?.let { menuKids(it) } ?: rootTopics

    private fun effectiveMs(entry: TrailEntry?, d: JMap): Long {
        if (noMotion() || entry?.revisit == true) return 0
        val ms = jsToNumber(d["typingMs"] ?: cfg.typingMs)
        return if (ms.isFinite()) ms.toLong() else 0
    }

    private fun textBlock(key: String, nodeId: String?, text: String?, ms: Long, kind: String = "bot") = Block(key, nodeId, richOf(text), kind, ms)

    private fun mediaBlock(key: String, node: FlowNode, ms: Long): Block? {
        val d = node.data
        val block = textBlock(key, node.id, d["label"] as? String, ms)
        val image = if (truthy(d["image"])) safeUrl(d["image"] as? String, listOf("https:")) else ""
        if (image.isNotEmpty()) {
            block.image = image
            block.imageAlt = render(d["imageAlt"] as? String)
        }
        @Suppress("UNCHECKED_CAST")
        val buttons = ((d["buttons"] as? List<Any?>).orEmpty()).mapNotNull { it as? JMap }.map { b ->
            FlowButton(b["id"] as? String, render(b["label"] as? String), safeUrl(b["url"] as? String, listOf("https:", "http:", "mailto:", "tel:")))
        }.filter { it.label.isNotEmpty() && it.url.isNotEmpty() }
        if (buttons.isNotEmpty()) block.buttons = buttons
        return if (truthy(d["label"]) || image.isNotEmpty()) block else null
    }

    private fun entryBlocks(entry: TrailEntry): List<Block> {
        val n = getNode(entry.id) ?: return emptyList()
        val d = n.data
        val ms = effectiveMs(entry, d)
        val key = "${entry.key}:0"
        var list: List<Block?> = emptyList()
        when (d["kind"]) {
            "topic", "subtopic" -> if (truthy(d["prompt"])) list = listOf(textBlock(key, n.id, d["prompt"] as? String, ms))
            "answer", "message", "end" -> list = listOf(mediaBlock(key, n, ms))
            "input", "yesno", "choice", "rating" -> if (truthy(d["label"])) list = listOf(textBlock(key, n.id, d["label"] as? String, ms))
            "webhook" -> {
                val status = webhookStatus[n.id]
                if (status == "success" && truthy(d["responseMessage"])) list = listOf(textBlock(key, n.id, d["responseMessage"] as? String, ms))
                else if (status == "error") {
                    val msg = if (truthy(d["errorMessage"])) d["errorMessage"] as? String else t("WEBHOOK.ERROR", emptyMap())
                    list = listOf(textBlock(key, n.id, msg, ms))
                }
            }
        }
        val out = ArrayList(list.filterNotNull())
        (post[entry.key] ?: emptyList()).forEachIndexed { i, p -> out += textBlock("${entry.key}:p$i", n.id, p, 0) }
        return out
    }

    private fun stepBlocksFull(): List<Block> {
        val list = ArrayList<Block>()
        prelude.forEach { (key, nodeId, text) -> list += textBlock(key, nodeId, text, 0) }
        val entries = trail
        if (entries.isEmpty()) {
            if (cfg.welcome.isNotEmpty()) {
                val first = menuEpoch <= 1
                list += textBlock("welcome:$menuEpoch", MENU_ID, cfg.welcome, if (first && !noMotion()) cfg.typingMs.toLong() else 0)
            }
        } else {
            var start = entries.size - 1
            while (start > 0 && entries[start].auto) start -= 1
            entries.drop(start).forEach { list += entryBlocks(it) }
        }
        if (idleShown) {
            list += textBlock(
                "idle:$focusSerial", null, cfg.idle.message.ifEmpty { t("IDLE.DEFAULT_MESSAGE", emptyMap()) }, 0, "idle",
            )
        }
        return list
    }

    private fun ready(): Boolean = !typing && stepBlocksFull().all { it.key in revealed }

    private fun pathSteps(): List<String> = trail.flatMap { entry ->
        val node = graph.byId[entry.id]
        val label = if (node != null && truthy(node.data["label"])) render(node.data["label"] as? String) else ""
        if (label.isEmpty() || node?.kind !in PATH_KINDS) emptyList()
        else {
            val pick = picks[entry.id]
            if (!pick.isNullOrEmpty()) listOf(pathLabel(label), pathLabel(pick)) else listOf(pathLabel(label))
        }
    }

    fun flowPath(): String = pathSteps().map { pathLabel(it) }.filter { it.isNotEmpty() }.joinToString(" > ")

    private fun controlInput(r: Boolean): ControlInput = ControlInput(
        overlay = overlay, node = currentNode(),
        rows = rowNodes().mapIndexed { i, n ->
            mapOf("id" to n.id, "label" to render(n.data["label"] as? String), "emoji" to ((n.data["emoji"] as? String)?.takeIf { it.isNotEmpty() } ?: ""), "index" to i + 1, "kind" to n.kind)
        },
        ready = r, settings = cfg, context = ctx(), t = t, render = ::render, inputStage = inputStage, inputDraft = inputDraft,
        pendingInput = pendingInput, inputError = inputError, feedbackStage = feedbackStage, multiSel = multiSel, otherOpen = otherOpen,
        otherValue = otherValue, ratingValue = ratingValue, idleAgent = idleAgent,
    )

    fun control(): FlowControl? = buildControl(controlInput(ready()))

    fun view(): FlowView {
        val full = stepBlocksFull()
        val blocks = full.filter { it.key in revealed }
        val o = overlay
        val idle = handoffState == null
        val r = !typing && full.all { it.key in revealed }
        val ctl = buildControl(controlInput(r))
        return FlowView(
            identity = if (cfg.botName.isNotEmpty()) FlowIdentity(cfg.botName, bot?.avatarUrl?.takeIf { it.isNotEmpty() }) else null,
            breadcrumb = pathSteps().joinToString("  ›  "),
            blocks = blocks.map {
                FlowBlock(it.key, it.nodeId, it.rich, it.kind, it.image, it.imageAlt, it.buttons)
            },
            typing = typing,
            control = ctl,
            nav = FlowNav(
                canBack = trail.isNotEmpty() && idle, canRestart = trail.isNotEmpty() && idle,
                agentLink = idle && r && o?.type == "terminal" && (o.variant == "info" || o.variant == "end"),
            ),
            announce = if (blocks.isNotEmpty()) richPlainText(blocks.last().rich) else "",
            focusKey = "f$focusSerial",
        )
    }

    // ================= events =================
    private fun emit(name: String, props: Map<String, Any?> = emptyMap(), nodeId: String = UNSET) {
        seq += 1
        val id: String? = when (nodeId) {
            UNSET -> lastEntry()?.id ?: MENU_ID
            NO_NODE -> null
            else -> nodeId
        }
        val kind = if (id != null) kindOf(getNode(id)) else null
        val evt = FlowRuntimeEvent(name, now(), seq, sessionId, ctx().botId, revision, props, id?.takeIf { it.isNotEmpty() }, kind)
        eventLog += evt
        while (eventLog.size > MAX_EVENTS_DEBUG) eventLog.removeAt(0)
        try {
            onEvent?.invoke(evt)
        } catch (_: Exception) {
            // analytics must never break the UI
        }
    }

    fun eventLog(): List<FlowRuntimeEvent> = eventLog

    private fun contextProps(): Map<String, Any?> {
        val c = ctx()
        return linkedMapOf(
            "locale" to c.locale, "page_path" to urlPath(c.pageUrl), "device" to c.device, "returning" to c.returning,
            "referrer_host" to c.referrerHost, "inbox_id" to c.inboxId, "bot_id" to c.botId, "revision" to revision,
        )
    }

    // ================= persistence =================
    private var saveTimer: Any? = null

    private fun serializeState(): JMap? {
        if (!(started && handoffState != "created")) return null
        return linkedMapOf(
            "v" to SAVE_VERSION, "flowId" to flowId, "revision" to revision, "sessionId" to sessionId, "seq" to seq, "started" to started,
            "terminal" to terminalReached, "savedAt" to now(),
            "trail" to trail.map { linkedMapOf("id" to it.id, "auto" to it.auto, "via" to it.via, "handle" to it.handle) },
            "values" to values, "picks" to picks, "varsSet" to varsSet, "splits" to splits, "webhook" to webhookStatus,
        )
    }

    private fun scheduleSave() {
        if (persistence == null || saveTimer != null) return
        saveTimer = timer(WRITE_DEBOUNCE_MS) {
            saveTimer = null
            serializeState()?.let { persistence.save(it) }
        }
    }

    private fun cancelSave() {
        saveTimer?.let { scheduler.clearTimeout(it) }
        saveTimer = null
    }

    private fun clearSaved() {
        cancelSave()
        persistence?.clear()
    }

    // ================= reveal / pump =================
    private fun markRevealed(key: String) {
        revealed += key
    }

    private fun cancelReveal() {
        revealTimer?.let { scheduler.clearTimeout(it) }
        revealTimer = null
        typing = false
    }

    private fun pump() {
        if (revealTimer != null) return
        val next = stepBlocksFull().firstOrNull { it.key !in revealed }
        if (next != null) {
            if (next.ms <= 0) {
                markRevealed(next.key)
                pump()
                return
            }
            typing = true
            val token = stepToken
            revealTimer = timer(next.ms) {
                revealTimer = null
                if (token != stepToken || destroyed) return@timer
                markRevealed(next.key)
                typing = false
                settle()
            }
            return
        }
        typing = false
        if (revealCbs.isNotEmpty()) {
            val cbs = revealCbs
            revealCbs = ArrayList()
            cbs.forEach { it() }
            pump()
        }
    }

    private fun afterReveal(cb: () -> Unit) {
        revealCbs += cb
    }

    private fun later(ms: Long, fn: () -> Unit) {
        val token = stepToken
        autoTimer = timer(ms) {
            autoTimer = null
            if (token != stepToken || destroyed) return@timer
            fn()
            settle()
        }
    }

    // ---- idle ----
    private var idleTimer: Any? = null
    private var idleFired = false
    private fun idleStop() {
        idleTimer?.let { scheduler.clearTimeout(it) }
        idleTimer = null
    }

    private fun idleStart() {
        idleStop()
        idleTimer = timer(cfg.idle.afterSec * 1000L) {
            idleTimer = null
            if (isHidden()) {
                idleStart()
                return@timer
            }
            idleFired = true
            idleShown = true
            val offer = cfg.idle.action == "offer_agent"
            idleAgent = offer
            markRevealed("idle:$focusSerial")
            emit("idle_prompt", mapOf("offered_agent" to offer))
            settle()
        }
    }

    private fun idleCanRun(): Boolean = ready() && handoffState == null && control()?.type in INTERACTIVE_CONTROLS
    private fun idleSync() {
        if (!cfg.idle.enabled || idleFired || !idleCanRun()) {
            idleStop()
            return
        }
        if (idleTimer == null) idleStart()
    }

    private fun idleTouch() {
        if (idleTimer != null) idleStart()
    }

    private fun idleResetStep() {
        idleFired = false
        idleStop()
    }

    private fun settle() {
        if (destroyed) return
        pump()
        idleSync()
        scheduleSave()
    }

    // ================= stop-state helpers =================
    private fun clearStopState() {
        overlay = null
        inputDraft = ""
        inputStage = "typing"
        pendingInput = null
        inputError = ""
        feedbackStage = "asking"
        multiSel = emptyList()
        otherOpen = false
        otherValue = ""
        ratingValue = null
    }

    private fun startStep() {
        stepToken += 1
        webhookToken += 1
        cancelReveal()
        autoTimer?.let { scheduler.clearTimeout(it) }
        autoTimer = null
        revealCbs = ArrayList()
        prelude = pendingPrelude
        pendingPrelude = emptyList()
        idleShown = false
        idleAgent = false
        idleResetStep()
        focusSerial += 1
    }

    private fun dropNodeState(id: String) {
        values = values - id
        picks = picks - id
        varsSet = varsSet - id
        webhookStatus = webhookStatus - id
        varOrder = varOrder.filter { it != id }
    }

    private fun touchVar(id: String) {
        if (id !in varOrder) varOrder = varOrder + id
    }

    private fun setValue(id: String, entry: JMap) {
        values = values + (id to entry)
        touchVar(id)
    }

    private fun setVarsSet(id: String, vars: JMap) {
        varsSet = varsSet + (id to vars)
        touchVar(id)
    }

    private fun updateLast(handle: String?) {
        if (trail.isEmpty()) return
        trail = trail.dropLast(1) + trail.last().copy(handle)
    }

    // ================= session rotation =================
    private fun rotate() {
        sessionId = newUuid()
        seq = 0
        splits = emptyMap()
        visited = emptySet()
        visitCounts.clear()
        terminalReached = false
        manualMarked = false
        restarts += 1
        startedAt = now()
        emit("flow_start", mapOf("context" to contextProps(), "trigger" to "restart"), NO_NODE)
    }

    private fun maybeRotate() {
        if (terminalReached) rotate()
    }

    private fun markResolved(via: String) {
        if (terminalReached) return
        terminalReached = true
        emit("flow_resolved", mapOf("via" to via))
    }

    private fun showMenu(from: String, via: String = "next") {
        menuEpoch += 1
        emit(
            "node_view",
            mapOf("from_node_id" to from, "handle" to "next", "via" to via, "depth" to 0, "passthrough" to false, "revisit" to (menuEpoch > 1)),
            MENU_ID,
        )
    }

    private fun terminal(variant: String, glad: String? = null, primaryRestart: Boolean = false) {
        overlay = Overlay("terminal", variant = variant, glad = glad, primaryRestart = primaryRestart)
    }

    private fun deadEnd(handle: String?) {
        overlay = Overlay("deadend")
        emit("dead_end", mapOf("handle" to (handle ?: "next")))
    }

    private fun feedbackOn(n: FlowNode): Boolean =
        n.data["feedback"] == "on" || (n.data["feedback"] != "off" && cfg.feedback.enabled)

    // ================= handoff =================
    private fun handoffVars(): Map<String, Any?> = capVars(userVars().filterValues { usable(it) }, Limits.VARS)

    private fun capVars(vars: Map<String, Any?>, cap: Int): Map<String, Any?> {
        val keys = vars.keys.toList()
        return LinkedHashMap<String, Any?>().also { out -> keys.takeLast(cap).forEach { out[it] = vars[it] } }
    }

    private fun buildTrace(): List<Map<String, String>> = trail.map { if (!it.handle.isNullOrEmpty()) mapOf("n" to it.id, "h" to it.handle) else mapOf("n" to it.id) }

    private fun proceedHandoff(reason: String, teamId: Any? = null, offline: Map<String, String>? = null, ticket: Boolean = false) {
        lastHandoff = Triple(reason, teamId, ticket to offline)
        handoffState = "requested"
        val startedMs = now()
        val node = currentNode()
        overlay = Overlay("agent", label = if (kindOf(node) == "agent") render(node!!.data["label"] as? String) else "", ticket = ticket)
        emit("handoff_request", mapOf("reason" to reason))
        val steps = trail.mapNotNull { getNode(it.id) }.filter { truthy(it.data["label"]) }.map { n ->
            val m = linkedMapOf<String, Any?>("id" to n.id, "k" to n.kind, "l" to render(n.data["label"] as? String).take(PICK_LABEL_MAX))
            val pick: Any? = picks[n.id] ?: values[n.id]?.get("value")
            if (pick != null && pick != Undefined) m["pick"] = pick
            m
        }
        val payload = HandoffPayload(
            reason, teamId, ticket, flowPath(), steps, buildTrace(), handoffVars(), sessionId, revision, offline,
        )
        val handler = onRequestAgent ?: return
        val deliver: (Result<AgentResult?>) -> Unit = { result ->
            enqueue {
                if (!destroyed) {
                    val res = result.getOrNull()
                    if (result.isSuccess && res?.ok != false) {
                        handoffState = "created"
                        emit(
                            "handoff_created",
                            mapOf(
                                "conversation_id" to res?.conversationId, "reason" to reason, "kind" to if (ticket) "ticket" else "live",
                                "elapsed_ms" to now() - startedMs,
                            ),
                        )
                        clearSaved()
                        settle()
                    } else {
                        // Deviation: the web code throws inside the fulfilled handler for {ok:false} (visitor stuck on "connecting");
                        // we treat it like a rejection and show the retry screen.
                        handoffState = null
                        overlay = Overlay("handoff-error")
                        settle()
                    }
                }
            }
        }
        try {
            handler(payload, deliver)
        } catch (e: Exception) {
            deliver(Result.failure(e))
        }
    }

    private fun showOffline(reason: String, teamId: Any?, trigger: String) {
        val prev = overlay
        overlay = Overlay("offline", trigger = trigger, reason = reason, teamId = teamId, prev = prev)
        emit("offline_shown", mapOf("mode" to cfg.offline.mode, "trigger" to trigger), NO_NODE)
    }

    fun requestAgent(reason: String = "manual", teamId: Any? = null, ticket: Boolean = false) {
        if (destroyed || handoffState != null || overlay?.type == "offline") return
        val trigger = if (ticket) null else offlineTrigger(cfg, ctx())
        if (trigger != null) showOffline(reason, teamId, trigger) else proceedHandoff(reason, teamId, null, ticket)
        settle()
    }

    /** Public entry for the UI (runs drain). */
    fun requestAgentAction(reason: String = "manual") = act { requestAgent(reason) }

    // ================= node entry =================
    private fun follow(n: FlowNode, handle: String?, auto: Boolean) {
        updateLast(handle)
        val child = graph.childrenOf(n.id, handle).firstOrNull()
        if (child != null) {
            enter(child, auto, if (auto) "auto" else "next")
            return
        }
        val kind = kindOf(n)
        if (!auto && kind in listOf("input", "yesno", "choice")) {
            requestAgent("unconnected")
        } else if (kind == "rating" || kind == "message") {
            terminal("info")
            markResolved("implicit_end")
        } else {
            deadEnd(handle)
        }
    }

    private fun clearToMenu(from: String) {
        trail = emptyList()
        picks = emptyMap()
        pendingPrelude = emptyList()
        clearStopState()
        startStep()
        showMenu(from, "goto")
    }

    private fun jsonKey(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { jsonKey(it.key.toString()) + ":" + jsonKey(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { jsonKey(it) }
        else -> jsToString(v)
    }

    private fun webhookActivate(n: FlowNode, resume: Boolean) {
        val d = n.data
        val known = webhookStatus[n.id]
        if (resume && known != null) {
            follow(n, known, true)
            return
        }
        val vars = capVars(userVars().filterValues { usable(it) }, WEBHOOK_VARS)
        val cacheKey = "${n.id}:${jsonKey(vars)}"
        fun finish(res: WebhookResult?, cached: Boolean, startedMs: Long) {
            val ok = res?.ok == true
            webhookStatus = webhookStatus + (n.id to if (ok) "success" else "error")
            if (ok) {
                setVarsSet(n.id, res!!.vars.filterValues { usable(it) })
                webhookCache[cacheKey] = res
            }
            overlay = null
            emit(
                "webhook_call",
                linkedMapOf<String, Any?>("status" to if (ok) "success" else "error").also {
                    if (!ok) it["reason"] = res?.reason?.takeIf { r -> r.isNotEmpty() } ?: "config"
                    it["duration_ms"] = now() - startedMs
                    it["cached"] = cached || res?.cached == true
                },
            )
            follow(n, if (ok) "success" else "error", true)
        }
        webhookCache[cacheKey]?.let {
            finish(it, true, now())
            return
        }
        overlay = Overlay("pending", label = render(d["label"] as? String).ifEmpty { t("WEBHOOK.LOADING", emptyMap()) })
        webhookToken += 1
        val token = webhookToken
        val startedMs = now()
        val handler = runWebhook
        val done: (WebhookResult?) -> Unit = { res ->
            enqueue {
                if (token == webhookToken && !destroyed) {
                    finish(res, false, startedMs)
                    settle()
                }
            }
        }
        enqueue {
            if (handler == null) done(WebhookResult(false, reason = "config"))
            else try {
                handler(n, vars, done)
            } catch (e: Exception) {
                done(WebhookResult(false, reason = "config"))
            }
        }
    }

    private fun activate(n: FlowNode, resume: Boolean = false) {
        val d = n.data
        when (d["kind"]) {
            "topic", "subtopic" -> {
                val kids = menuKids(n)
                if (kids.isEmpty()) deadEnd(null)
                else if (kids.size == 1 && kindOf(kids[0]) in AUTO_OPEN_KINDS) enter(kids[0], true, "auto")
            }
            "question" -> {
                val kids = graph.childrenOf(n.id, null).filter { kindOf(it) != "topic" }
                val child = kids.firstOrNull { kindOf(it) == "answer" } ?: kids.firstOrNull()
                if (child != null) enter(child, true, "auto") else deadEnd(null)
            }
            "answer" -> if (!feedbackOn(n)) terminal("info")
            "message" -> {
                if (!hasNext(n, null)) {
                    terminal("info")
                    if (!resume) markResolved("implicit_end")
                } else if (d["autoContinue"] == true) {
                    overlay = Overlay("auto")
                    afterReveal { later(if (noMotion()) 0 else AUTO_CONTINUE_MS) { follow(n, null, true) } }
                }
            }
            "choice" -> if (optionsOf(d).isEmpty()) deadEnd(null)
            "condition" -> {
                val b = pickBranch(n, ctx(), variables())
                emit("condition_eval", mapOf("matched" to b.handle, "rule_type" to b.rule?.get("type")))
                follow(n, b.handle, true)
            }
            "split" -> {
                var variant = splits[n.id]?.takeIf { it.isNotEmpty() }
                val first = variant == null
                if (first) {
                    variant = pickVariant(d["variants"] as? List<Any?>, bucketOf(sessionId, n.id))
                    splits = splits + (n.id to variant.orEmpty())
                }
                @Suppress("UNCHECKED_CAST")
                val label = ((d["variants"] as? List<Any?>).orEmpty().mapNotNull { it as? JMap }.firstOrNull { it["id"] == variant })?.get("label")
                if (truthy(d["variable"])) setVarsSet(n.id, mapOf(d["variable"] as String to (if (truthy(label)) label else "")))
                if (first) emit("split_assign", mapOf("variant_id" to variant))
                follow(n, variant, true)
            }
            "action" -> {
                @Suppress("UNCHECKED_CAST")
                val actions = (d["actions"] as? List<Any?>).orEmpty().mapNotNull { it as? JMap }
                val local = LinkedHashMap(variables())
                val assigned = LinkedHashMap<String, Any?>()
                actions.filter { it["type"] == "set_variable" && truthy(it["variable"]) }.forEach { a ->
                    val v = renderTemplate((a["value"] as? String).orEmpty(), local, "text").take(Limits.TEMPLATE_VALUE)
                    local[a["variable"] as String] = v
                    assigned[a["variable"] as String] = v
                }
                if (assigned.isNotEmpty()) setVarsSet(n.id, assigned)
                @Suppress("UNCHECKED_CAST")
                val types = (d["types"] as? List<Any?>) ?: actions.mapNotNull { it["type"] }.distinct()
                emit("action_applied", mapOf("action_types" to types, "deferred" to true))
                follow(n, null, true)
            }
            "webhook" -> webhookActivate(n, resume)
            "goto" -> {
                if (d["targetId"] == MENU_ID) {
                    clearToMenu(n.id)
                } else {
                    val target = getNode(d["targetId"] as? String)
                    if (target == null || kindOf(target) == "note") deadEnd(null) else enter(target, true, "goto")
                }
            }
            "end" -> {
                terminal("end", primaryRestart = d["then"] == "restart")
                if (!resume) markResolved("end")
            }
            "agent" -> requestAgent("agent_node", d["teamId"].takeIf { truthy(it) }, d["mode"] == "ticket")
            "input", "yesno", "rating" -> Unit
            else -> deadEnd(null)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun optionsOf(d: JMap): List<JMap> = (d["options"] as? List<Any?>).orEmpty().mapNotNull { it as? JMap }

    private fun chainLength(): Int {
        var count = 0
        for (i in trail.indices.reversed()) {
            count += 1
            if (!trail[i].auto) break
        }
        return count
    }

    private fun enter(n: FlowNode, auto: Boolean, via: String) {
        val last = lastEntry()
        if (trail.size >= Limits.TRACE || (visitCounts[n.id] ?: 0) >= MAX_VISITS || chainLength() >= MAX_CHAIN) {
            deadEnd(null)
            return
        }
        val revisit = n.id in visited
        val entry = TrailEntry(n.id, auto, via, null, nextKey(), revisit)
        visitCounts[n.id] = (visitCounts[n.id] ?: 0) + 1
        trail = trail + entry
        visited = visited + n.id
        clearStopState()
        if (!auto) startStep()
        emit(
            "node_view",
            mapOf(
                "from_node_id" to (last?.id ?: MENU_ID), "handle" to (last?.handle ?: "next"), "via" to via, "depth" to trail.size,
                "passthrough" to isPass(n), "revisit" to revisit,
            ),
            n.id,
        )
        activate(n)
    }

    // ================= visitor actions =================
    private fun <T> act(fn: () -> T): T? {
        if (destroyed) return null
        val r = fn()
        settle()
        drain()
        return r
    }

    private fun atMenuStop(): Boolean {
        val n = currentNode()
        return overlay == null && (n == null || kindOf(n) in listOf("topic", "subtopic"))
    }

    private fun lastOfKind(kind: String): FlowNode? {
        val n = currentNode()
        return if (overlay == null && kindOf(n) == kind) n else null
    }

    fun start(nodeId: String? = null) = act {
        if (started) return@act
        started = true
        startedAt = now()
        val target = nodeId?.let { getNode(it) }
        emit("flow_start", mapOf("context" to contextProps(), "trigger" to if (target != null) "url_rule" else "default"), NO_NODE)
        if (target != null && kindOf(target) != "note") enter(target, false, "trigger") else showMenu(MENU_ID)
    }

    fun selectOption(nodeId: String) = act {
        val rows = rowNodes()
        val index = rows.indexOfFirst { it.id == nodeId }
        if (index < 0 || !atMenuStop()) return@act
        maybeRotate()
        val node = rows[index]
        val kind = kindOf(node)
        emit(
            "option_select",
            mapOf(
                "option_kind" to if (kind == "topic" || kind == "subtopic") kind else "question", "option_id" to node.id, "index" to index + 1,
                "label" to render(node.data["label"] as? String).take(PICK_LABEL_MAX),
            ),
            lastEntry()?.id ?: MENU_ID,
        )
        if (lastEntry() != null) updateLast(null)
        enter(node, false, "menu")
    }

    private fun commitChoice(n: FlowNode, ids: List<String>, labels: List<String>, value: Any?, saved: String, handle: String, kind: String, label: String) {
        val d = n.data
        setValue(
            n.id,
            linkedMapOf(
                "variable" to (d["variable"].takeIf { truthy(it) }), "label" to (d["label"] ?: Undefined), "value" to value,
                "display" to labels.joinToString(", "), "saveTo" to ((d["saveTo"] as? String)?.takeIf { it.isNotEmpty() } ?: "conversation_attribute"),
            ).filterValues { it !== Undefined },
        )
        picks = picks + (n.id to saved)
        val props = linkedMapOf<String, Any?>("option_kind" to kind)
        if (ids.size == 1 && d["multiple"] != true) props["option_id"] = ids[0] else props["option_ids"] = ids
        if (ids.size == 1) props["index"] = optionsOf(d).indexOfFirst { it["id"] == ids[0] } + 1
        props["label"] = label.take(PICK_LABEL_MAX)
        emit("option_select", props)
        follow(n, handle, false)
    }

    fun chooseOption(optionId: String) = act {
        val n = lastOfKind("choice")
        if (n == null || n.data["multiple"] == true) return@act
        val d = n.data
        if (optionId == "other") {
            if (d["allowOther"] == true) otherOpen = true
            return@act
        }
        val opt = optionsOf(d).firstOrNull { it["id"] == optionId } ?: return@act
        maybeRotate()
        val label = render(opt["label"] as? String)
        commitChoice(n, listOf(optionId), listOf(label), render((opt["value"] ?: opt["label"]) as? String), label, optionId, "choice", label)
    }

    fun setOtherText(text: String) = act {
        otherValue = text.take(OTHER_MAX)
        inputError = ""
        idleTouch()
    }

    fun toggleOption(optionId: String) = act {
        val n = lastOfKind("choice")
        if (n == null || n.data["multiple"] != true) return@act
        val d = n.data
        if (optionId == "other") {
            if (d["allowOther"] != true) return@act
            otherOpen = !otherOpen
            if (!otherOpen) otherValue = ""
            return@act
        }
        if (optionsOf(d).none { it["id"] == optionId }) return@act
        if (optionId in multiSel) {
            multiSel = multiSel.filter { it != optionId }
        } else {
            val max = if (truthy(d["maxSelect"])) jsToNumber(d["maxSelect"]) else optionsOf(d).size.toDouble()
            val picked = multiSel.size + if (otherOpen && jsTrim(otherValue).isNotEmpty()) 1 else 0
            if (picked < max) multiSel = multiSel + optionId
        }
    }

    fun submitMulti() = act {
        val n = lastOfKind("choice")
        if (n == null || n.data["multiple"] != true || control()?.bool("canSubmit") != true) return@act
        maybeRotate()
        val d = n.data
        val chosen = optionsOf(d).filter { it["id"] in multiSel }
        val other = if (otherOpen) jsTrim(otherValue) else ""
        val labels = chosen.map { render(it["label"] as? String) } + (if (other.isNotEmpty()) listOf(other) else emptyList())
        val ids = chosen.map { it["id"] as String } + (if (other.isNotEmpty()) listOf("other") else emptyList())
        commitChoice(
            n, ids, labels, chosen.map { render((it["value"] ?: it["label"]) as? String) } + (if (other.isNotEmpty()) listOf(other) else emptyList()),
            (chosen.map { render(it["label"] as? String) } +
                (if (other.isNotEmpty()) listOf((d["otherLabel"] as? String)?.takeIf { it.isNotEmpty() } ?: t("OTHER", emptyMap())) else emptyList())).joinToString(", "),
            "next", if (other.isNotEmpty() && chosen.isEmpty()) "other" else "choice", labels.joinToString(", "),
        )
    }

    fun submitOther() = act {
        val n = lastOfKind("choice")
        val text = jsTrim(otherValue)
        if (n == null || n.data["multiple"] == true || n.data["allowOther"] != true || text.isEmpty()) return@act
        maybeRotate()
        val otherLabel = render(n.data["otherLabel"] as? String).ifEmpty { t("OTHER", emptyMap()) }
        commitChoice(n, listOf("other"), listOf(text), text, otherLabel, "other", "other", otherLabel)
    }

    fun answerYesNo(branch: String) = act {
        val n = lastOfKind("yesno")
        if (n == null || branch !in listOf("yes", "no")) return@act
        maybeRotate()
        val label = render((if (branch == "yes") n.data["yesLabel"] else n.data["noLabel"]) as? String)
            .ifEmpty { if (branch == "yes") t("YES", emptyMap()) else t("NO", emptyMap()) }
        picks = picks + (n.id to label)
        emit("option_select", mapOf("option_kind" to "yesno", "option_id" to branch, "index" to if (branch == "yes") 1 else 2, "label" to label))
        follow(n, branch, false)
    }

    fun setInputValue(v: String) = act {
        inputDraft = v
        inputError = ""
        idleTouch()
    }

    private fun commitInput(n: FlowNode, value: String, confirmed: Boolean) {
        val d = n.data
        val saveTo = (d["saveTo"] as? String)?.takeIf { it.isNotEmpty() } ?: "conversation_attribute"
        setValue(
            n.id,
            linkedMapOf("variable" to d["variable"].takeIf { truthy(it) }, "label" to (d["label"] ?: Undefined), "value" to value, "display" to value, "saveTo" to saveTo)
                .filterValues { it !== Undefined },
        )
        emit(
            "input_submit",
            mapOf(
                "input_type" to ((d["inputType"] as? String)?.takeIf { it.isNotEmpty() } ?: "text"), "length" to value.codePointCount(0, value.length),
                "confirmed" to confirmed, "saved_to" to saveTo,
            ),
        )
        follow(n, null, false)
    }

    fun skipInput() = act {
        val n = lastOfKind("input")
        if (n == null || n.data["required"] != false) return@act
        maybeRotate()
        emit("input_skip")
        follow(n, null, false)
    }

    fun submitInput() = act {
        val n = lastOfKind("input")
        if (n == null || inputStage != "typing") return@act
        maybeRotate()
        val type = (n.data["inputType"] as? String)?.takeIf { it.isNotEmpty() } ?: "text"
        val res = validateInput(n.data, inputDraft)
        if (!res.ok) {
            emit("input_invalid", mapOf("input_type" to type, "reason" to res.reason))
            inputError = inputErrorText(t, type, res)
            return@act
        }
        if (res.value == "") {
            emit("input_skip")
            follow(n, null, false)
            return@act
        }
        inputError = ""
        if (n.data["confirm"] == true) {
            pendingInput = res.value
            inputStage = "confirm"
            focusSerial += 1
            return@act
        }
        commitInput(n, res.value, false)
    }

    fun editInput() = act {
        if (lastOfKind("input") == null) return@act
        pendingInput = null
        inputStage = "typing"
        inputError = ""
        focusSerial += 1
    }

    fun confirmInput() = act {
        val n = lastOfKind("input")
        if (n == null || inputStage != "confirm" || pendingInput == null) return@act
        maybeRotate()
        commitInput(n, pendingInput!!, true)
    }

    fun submitRating(raw: Long) = act {
        val n = lastOfKind("rating")
        if (n == null || ratingValue != null) return@act
        val d = n.data
        val scale = (d["scale"] as? String)?.takeIf { it.isNotEmpty() } ?: "stars5"
        val range = RATING_RANGES[scale] ?: RATING_RANGES.getValue("stars5")
        if (raw < range.min || raw > range.max) return@act
        maybeRotate()
        ratingValue = raw
        setValue(
            n.id,
            linkedMapOf(
                "variable" to d["variable"].takeIf { truthy(it) }, "label" to (d["label"] ?: Undefined), "value" to raw, "display" to raw.toString(),
                "saveTo" to ((d["saveTo"] as? String)?.takeIf { it.isNotEmpty() } ?: "conversation_attribute"),
            ).filterValues { it !== Undefined },
        )
        emit("rating_submit", mapOf("scale" to scale, "value" to raw))
        val entry = lastEntry()!!
        if (!hasNext(n, null)) {
            if (truthy(d["thanks"])) post = post + (entry.key to listOf(d["thanks"] as String))
            terminal("info")
            markResolved("implicit_end")
            return@act
        }
        later(if (noMotion()) 0 else RATING_NEXT_MS) {
            if (truthy(d["thanks"])) pendingPrelude = listOf(Triple("prelude:${entry.key}", n.id, d["thanks"] as String))
            follow(n, null, false)
        }
    }

    fun continueNext() = act {
        val n = lastOfKind("message") ?: return@act
        maybeRotate()
        follow(n, null, false)
    }

    private fun afterNo(n: FlowNode) {
        if (hasNext(n, "no")) follow(n, "no", false) else requestAgent("not_helpful")
    }

    fun answerFeedback(helpful: Boolean) = act {
        val n = lastOfKind("answer")
        if (n == null || feedbackStage != "asking" || !feedbackOn(n)) return@act
        maybeRotate()
        val f = cfg.feedback
        feedbackHelpful = helpful
        feedbackReason = ""
        emit("feedback", mapOf("helpful" to helpful, "effective" to true))
        emit(
            "option_select",
            mapOf(
                "option_kind" to "answer_feedback", "option_id" to if (helpful) "yes" else "no", "index" to if (helpful) 1 else 2,
                "label" to render(if (helpful) f.yesLabel else f.noLabel).ifEmpty { if (helpful) t("YES_RESOLVED", emptyMap()) else t("NO_NEED_AGENT", emptyMap()) },
            ),
        )
        if (helpful) {
            if (hasNext(n, "yes")) {
                follow(n, "yes", false)
            } else {
                terminal("resolved", glad = render(f.gladMessage).ifEmpty { t("GLAD_IT_HELPED", emptyMap()) })
                markResolved("answer_yes")
            }
            return@act
        }
        if (f.askReason && f.reasons.isNotEmpty()) {
            feedbackStage = "reason"
            focusSerial += 1
            return@act
        }
        afterNo(n)
    }

    fun chooseReason(reasonId: String?) = act {
        val n = lastOfKind("answer")
        if (n == null || feedbackStage != "reason") return@act
        val reason = cfg.feedback.reasons.firstOrNull { it.first == reasonId }
        emit("feedback_reason", mapOf("reason_id" to reason?.first))
        feedbackHelpful = false
        feedbackReason = if (reason != null) render(reason.second) else ""
        afterNo(n)
    }

    fun submitOffline(fields: Map<String, String>) = act {
        val o = overlay
        if (o?.type != "offline" || cfg.offline.mode != "form") return@act
        val res = validateOffline(cfg, ctx(), fields)
        if (!res.ok) {
            val types = mapOf("name" to "text", "email" to "email", "phone" to "phone", "message" to "longtext")
            overlay = o.copy(
                values = LinkedHashMap(fields),
                errors = res.errors.entries.associate { (name, r) -> name to inputErrorText(t, types[name], r) },
                error = if (res.channelMissing) t("OFFLINE.ERROR_CHANNEL", emptyMap()) else "",
            )
            return@act
        }
        emit("offline_submit", mapOf("fields" to res.values.keys.toList()), NO_NODE)
        proceedHandoff("offline_form", o.teamId, res.values)
    }

    fun leaveMessage() = act {
        val o = overlay
        if (o?.type != "offline" || cfg.offline.mode != "message") return@act
        proceedHandoff(o.reason ?: "manual", o.teamId)
    }

    fun retryHandoff() = act {
        val h = lastHandoff
        if (overlay?.type != "handoff-error" || h == null) return@act
        proceedHandoff(h.first, h.second, h.third.second, h.third.first)
    }

    /** Direct "start chat" card tapped: returns the `flow` body for the first message (web `markDirectStart`). */
    fun markDirectStart(): Map<String, Any?>? {
        if (destroyed || handoffState != null || manualMarked) return null
        manualMarked = true
        emit("handoff_request", mapOf("reason" to "manual"))
        settle()
        drain()
        return linkedMapOf(
            "session_id" to sessionId, "schema" to SCHEMA_VERSION, "revision" to revision, "reason" to "manual", "trace" to buildTrace(),
            "vars" to handoffVars(),
        )
    }

    fun markLinkClick(kind: String = "inline", buttonId: String? = null, url: String? = null) = act {
        val props = linkedMapOf<String, Any?>("kind" to kind)
        if (!buttonId.isNullOrEmpty()) props["button_id"] = buttonId
        props["host"] = urlHost(url)
        emit("link_click", props)
    }

    fun markActivity() = idleTouch()

    fun goBack() = act {
        if (handoffState != null) return@act
        maybeRotate()
        val from = lastEntry()?.id ?: MENU_ID
        val o = overlay
        val node = currentNode()
        if (o?.type == "offline") {
            if (kindOf(node) != "agent") {
                overlay = o.prev
                feedbackStage = "asking"
                emit("back", mapOf("from_node_id" to from, "to_node_id" to from))
                return@act
            }
        } else if (o?.type == "handoff-error" && kindOf(node) != "agent") {
            overlay = null
            emit("back", mapOf("from_node_id" to from, "to_node_id" to from))
            return@act
        } else if (feedbackStage == "reason" && o == null) {
            feedbackStage = "asking"
            emit("back", mapOf("from_node_id" to from, "to_node_id" to from))
            return@act
        } else if (inputStage == "confirm" && o == null) {
            inputStage = "typing"
            pendingInput = null
            focusSerial += 1
            emit("back", mapOf("from_node_id" to from, "to_node_id" to from))
            return@act
        }
        if (trail.isEmpty()) return@act
        var list = trail
        var removed: TrailEntry
        do {
            removed = list.last()
            list = list.dropLast(1)
            dropNodeState(removed.id)
        } while (removed.auto && list.isNotEmpty())
        trail = list
        val last = list.lastOrNull()
        emit("back", mapOf("from_node_id" to from, "to_node_id" to last?.id), from)
        clearStopState()
        startStep()
        if (last == null) {
            showMenu(from, "back")
            return@act
        }
        updateLast(null)
        dropNodeState(last.id)
        val prev = getNode(last.id)!!
        emit(
            "node_view",
            mapOf("from_node_id" to from, "handle" to "next", "via" to "back", "depth" to list.size, "passthrough" to false, "revisit" to true),
            last.id,
        )
        activate(prev, resume = true)
    }

    private fun resetAll() {
        trail = emptyList()
        values = emptyMap()
        picks = emptyMap()
        varsSet = emptyMap()
        varOrder = emptyList()
        webhookStatus = emptyMap()
        post = emptyMap()
        pendingPrelude = emptyList()
        feedbackHelpful = null
        feedbackReason = ""
        handoffState = null
        lastHandoff = null
    }

    fun restart(via: String = "nav") = act {
        if (handoffState != null) return@act
        val from = lastEntry()?.id ?: MENU_ID
        emit("restart", mapOf("from_node_id" to from, "via" to via))
        if (terminalReached) rotate()
        resetAll()
        clearStopState()
        startStep()
        showMenu(from)
    }

    // ================= resume =================
    private fun sameId(a: Any?, b: Any?): Boolean {
        if (a == null || b == null) return true
        if (a is Number && b is Number) return a.toDouble() == b.toDouble()
        return a == b
    }

    @Suppress("UNCHECKED_CAST")
    private fun isValidSaved(saved: JMap): Boolean {
        if (!(saved["v"] is Number && (saved["v"] as Number).toDouble() == SAVE_VERSION.toDouble()) || saved["trail"] !is List<*>) return false
        if (!truthy(saved["sessionId"]) || now() - jsToNumber(saved["savedAt"]) > RESUME_TTL_MS) return false
        if (!sameId(saved["flowId"], flowId) || !sameId(saved["revision"], revision)) return false
        val list = (saved["trail"] as List<Any?>).map { it as? JMap ?: emptyMap() }
        if (list.any { !graph.byId.containsKey(it["id"]) }) return false
        return list.withIndex().all { (i, e) ->
            if (i == list.size - 1) true
            else {
                val node = graph.byId[e["id"]]
                val nextId = list[i + 1]["id"]
                if (node?.kind == "goto") node.data["targetId"] == nextId
                else graph.out(e["id"] as String, e["handle"] as? String).any { it.target == nextId }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun hydrate(): Boolean {
        if (destroyed || persistence == null || started) return false
        val saved = persistence.load() ?: return false
        if (!isValidSaved(saved)) {
            persistence.clear()
            return false
        }
        sessionId = saved["sessionId"] as String
        seq = (saved["seq"] as? Number)?.toInt() ?: 0
        started = true
        startedAt = now()
        terminalReached = saved["terminal"] == true
        values = (saved["values"] as? Map<String, Any?>).orEmpty().mapValues { it.value as? JMap ?: emptyMap() }
        picks = (saved["picks"] as? Map<String, Any?>).orEmpty().mapValues { jsToString(it.value) }
        varsSet = (saved["varsSet"] as? Map<String, Any?>).orEmpty().mapValues { it.value as? JMap ?: emptyMap() }
        splits = (saved["splits"] as? Map<String, Any?>).orEmpty().mapValues { jsToString(it.value) }
        webhookStatus = (saved["webhook"] as? Map<String, Any?>).orEmpty().mapValues { jsToString(it.value) }
        var list = (saved["trail"] as List<Any?>).map { it as JMap }.map { e ->
            TrailEntry(e["id"] as String, e["auto"] == true, (e["via"] as? String)?.takeIf { it.isNotEmpty() } ?: "next", e["handle"] as? String, nextKey(), true)
        }
        while (list.isNotEmpty() && kindOf(getNode(list.last().id)) == "agent") list = list.dropLast(1)
        if (list.isNotEmpty()) list = list.dropLast(1) + list.last().copy(null)
        trail = list
        visited = list.map { it.id }.toSet()
        list.forEach { visitCounts[it.id] = (visitCounts[it.id] ?: 0) + 1 }
        varOrder = (values.keys + varsSet.keys).distinct().filter { id -> list.any { it.id == id } }
        clearStopState()
        startStep()
        revealed.clear()
        stepBlocksFull().forEach { revealed += it.key }
        val last = list.lastOrNull()
        if (last == null) {
            showMenu(MENU_ID, "resume")
            revealed.clear()
            stepBlocksFull().forEach { revealed += it.key }
        } else {
            emit(
                "node_view",
                mapOf(
                    "from_node_id" to (if (list.size > 1) list[list.size - 2].id else MENU_ID), "handle" to "next", "via" to "resume",
                    "depth" to list.size, "passthrough" to false, "revisit" to true,
                ),
                last.id,
            )
            activate(getNode(last.id)!!, resume = true)
        }
        settle()
        drain()
        return true
    }

    fun destroy() {
        destroyed = true
        stepToken += 1
        webhookToken += 1
        cancelReveal()
        autoTimer?.let { scheduler.clearTimeout(it) }
        autoTimer = null
        idleStop()
        cancelSave()
    }

    // ---- debug accessors (parity tests / diagnostics) ----
    fun debugValues(): Map<String, JMap> = values
    fun debugPicks(): Map<String, String> = picks
    fun debugVarsSet(): Map<String, JMap> = varsSet
    fun trailIds(): List<String> = trail.map { it.id }
}
