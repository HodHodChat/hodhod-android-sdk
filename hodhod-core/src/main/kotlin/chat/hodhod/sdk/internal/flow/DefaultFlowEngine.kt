package chat.hodhod.sdk.internal.flow

import android.os.Handler
import android.os.Looper
import chat.hodhod.sdk.ContactInfo
import chat.hodhod.sdk.FlowStatus
import chat.hodhod.sdk.FlowView
import chat.hodhod.sdk.HodhodFlowEngine
import chat.hodhod.sdk.WidgetConfig
import chat.hodhod.sdk.internal.SessionStore
import chat.hodhod.sdk.internal.arr
import chat.hodhod.sdk.internal.obj
import chat.hodhod.sdk.internal.parseJson
import chat.hodhod.sdk.internal.str
import chat.hodhod.sdk.internal.sub
import chat.hodhod.sdk.internal.toJson
import chat.hodhod.sdk.internal.toPlain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the engine needs from the host repository (kept as an interface so the engine is JVM-testable). */
internal interface FlowHost {
    val config: WidgetConfig?
    val agentsOnline: Boolean
    val contact: ContactInfo
    val locale: String
    val store: SessionStore

    /** `GET /widget/chatbot_flow` -> the `payload` object (null when the inbox has none / the call failed). */
    suspend fun fetchFlow(): JsonObject?

    /** `POST /widget/chatbot_flow/webhook` -> the `payload` object (SSRF-hardened server proxy). */
    suspend fun runWebhook(nodeId: String, sessionId: String, variables: Map<String, Any?>): JsonObject?

    /** `POST /widget/chatbot_flow_events` -> (HTTP status or null on network error, parsed body). */
    suspend fun postEvents(body: JsonObject): Pair<Int?, JsonElement?>

    /** Post the first visitor message with the `flow` handoff body (creates the conversation; the server applies team/labels/ticket). */
    suspend fun sendHandoff(content: String, flow: Map<String, Any?>): Result<Any?>

    /** Remember the direct-start flow body for the next first message. */
    fun setPendingFlow(flow: Map<String, Any?>?)
}

/** Main-thread timer backed by a Looper [Handler]. */
internal class HandlerFlowScheduler : FlowScheduler {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    override fun setTimeout(ms: Long, fn: () -> Unit): Any {
        val r = Runnable { fn() }
        handler.postDelayed(r, ms)
        return r
    }

    override fun clearTimeout(id: Any) {
        handler.removeCallbacks(id as Runnable)
    }
}

/** [SessionStore]-backed flow session persistence (web: sessionStorage `hodhod:flow:v2:<token>`). */
internal class StoreFlowPersistence(private val store: SessionStore) : FlowPersistence {
    override fun load(): JMap? = store.get(KEY)?.let { (parseJson(it).obj()?.toPlain() as? JMap) }
    override fun save(state: JMap) = store.put(KEY, state.toJson().toString())
    override fun clear() = store.put(KEY, null)

    companion object {
        const val KEY = "flow_state_v2"
        const val SEEN_KEY = "flow_seen"
        const val VID_KEY = "flow_vid"
    }
}

internal class DefaultFlowEngine(
    private val host: FlowHost,
    private val scope: CoroutineScope,
    private val scheduler: FlowScheduler = HandlerFlowScheduler(),
    private val log: (String) -> Unit = {},
) : HodhodFlowEngine {
    private val _isActive = MutableStateFlow(false)
    private val _status = MutableStateFlow(FlowStatus.IDLE)
    private val _view = MutableStateFlow<FlowView?>(null)
    private val _require = MutableStateFlow(false)
    private val _handedOff = MutableStateFlow(false)
    override val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    override val status: StateFlow<FlowStatus> = _status.asStateFlow()
    override val view: StateFlow<FlowView?> = _view.asStateFlow()
    override val requireFlow: StateFlow<Boolean> = _require.asStateFlow()
    override val handedOff: StateFlow<Boolean> = _handedOff.asStateFlow()

    private var machine: FlowMachine? = null
    private var translate: Translate = { key, params ->
        params.entries.fold(DefaultFlowStrings.EN[key] ?: key) { acc, (k, v) -> acc.replace("{$k}", jsToString(v)) }
    }
    private var loading = false
    private var flowId: Any? = null
    private var persistence = StoreFlowPersistence(host.store)
    private val analytics = FlowAnalytics(
        scope = scope,
        post = { host.postEvents(it) },
        locale = { host.locale },
        visitorId = {
            host.store.get(StoreFlowPersistence.VID_KEY) ?: java.util.UUID.randomUUID().toString().also { host.store.put(StoreFlowPersistence.VID_KEY, it) }
        },
    )

    override fun setTranslator(translate: (key: String, params: Map<String, Any?>) -> String) {
        this.translate = translate
        machine?.drain()
    }

    /** Flush pending analytics (app going to the background). */
    fun flushAnalytics() = analytics.flush()

    private fun contextNow(): FlowContext {
        val cfg = host.config
        val c = host.contact
        return FlowContext(
            utcOffset = cfg?.utcOffset, businessOpen = cfg?.let { !(it.workingHoursEnabled && !it.isInWorkingHours()) } ?: true,
            agentsOnline = host.agentsOnline, locale = host.locale, pageUrl = "", device = "mobile", returning = returning,
            contactName = c.name.orEmpty(), contactEmail = c.email.orEmpty(), contactPhone = c.phone.orEmpty(), inboxId = null, botId = flowId, referrerHost = "",
        )
    }

    private var returning = false

    override suspend fun load() {
        if (loading || machine != null) return
        loading = true
        _status.value = FlowStatus.LOADING
        try {
            val payload = host.fetchFlow()
            val doc = payload?.takeIf { it.sub("settings")?.get("enabled") != JsonPrimitive(false) }?.let { migrateAndNormalize(it.toPlain()) }
            if (payload == null || doc == null || doc.nodes.isEmpty()) return finish(FlowStatus.NONE)
            flowId = payload["flow_id"]?.toPlain()
            returning = host.store.get(StoreFlowPersistence.SEEN_KEY) == "1"
            val trig = evaluateTriggers(doc.settings.triggers, contextNow())
            if (!trig.show) return finish(FlowStatus.NONE)
            val bot = payload.sub("bot")?.let { BotInfo(it.str("name"), it.str("avatar_url")) }
            val rev = (payload["revision"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()
            lateinit var m: FlowMachine
            m = FlowMachine(
                doc = doc, context = { contextNow() }, t = { k, p -> translate(k, p) }, initialSessionId = null, flowId = flowId, revision = rev,
                onRequestAgent = { p, cb -> handoff(p, cb) },
                onEvent = { analytics.onEvent(it) },
                runWebhook = { node, vars, cb -> webhook(node, vars, m, cb) },
                persistence = persistence, scheduler = scheduler, bot = bot, reducedMotion = reducedMotion,
                onChange = { if (machine === m) _view.value = m.view() },
            )
            machine = m
            _require.value = doc.requireFlow
            _isActive.value = true
            if (!m.hydrate()) m.start(trig.startTopicId)
            host.store.put(StoreFlowPersistence.SEEN_KEY, "1")
            _status.value = FlowStatus.READY
            _view.value = m.view()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log("flow load failed: ${e.javaClass.simpleName}")
            finish(FlowStatus.NONE)
        } finally {
            loading = false
        }
    }

    private var reducedMotion: Boolean = false

    override fun setReducedMotion(reduced: Boolean) {
        reducedMotion = reduced
    }

    private fun finish(s: FlowStatus) {
        _status.value = s
        _isActive.value = false
        _view.value = null
        _require.value = false
    }

    private fun webhook(node: FlowNode, vars: Map<String, Any?>, m: FlowMachine, cb: (WebhookResult?) -> Unit) {
        scope.launch {
            val p = try {
                host.runWebhook(node.id, m.sessionId, vars)
            } catch (e: Exception) {
                null
            }
            val res = if (p == null) WebhookResult(false, reason = "http") else WebhookResult(
                ok = (p["ok"] as? JsonPrimitive)?.content == "true",
                vars = (p["vars"].obj()?.toPlain() as? JMap).orEmpty(),
                reason = p.str("reason"), cached = (p["cached"] as? JsonPrimitive)?.content == "true",
            )
            cb(res)
        }
    }

    private fun ticketRequestContent(p: HandoffPayload): String {
        val lines = p.summarySteps.filter { truthy(it["l"]) && it["pick"] != null && it["pick"] != "" }.map {
            val pick = it["pick"]
            "${it["l"]}: ${if (pick is List<*>) pick.joinToString(", ") { x -> jsToString(x) } else jsToString(pick)}"
        }
        return (listOf(translate("TICKET_REQUEST_MESSAGE", emptyMap())) + lines).joinToString("\n").take(5000)
    }

    private fun handoff(p: HandoffPayload, cb: (Result<AgentResult?>) -> Unit) {
        scope.launch {
            val content = if (p.ticket) ticketRequestContent(p) else translate("AGENT_REQUEST_MESSAGE", emptyMap())
            val r = host.sendHandoff(content, p.toFlowParam())
            if (r.isSuccess) {
                _handedOff.value = true
                cb(Result.success(AgentResult(true, r.getOrNull())))
            } else {
                cb(Result.failure(r.exceptionOrNull() ?: IllegalStateException("handoff failed")))
            }
        }
    }

    override fun selectOption(nodeId: String) { machine?.selectOption(nodeId) }
    override fun chooseOption(optionId: String) { machine?.chooseOption(optionId) }
    override fun toggleOption(optionId: String) { machine?.toggleOption(optionId) }
    override fun setOtherText(text: String) { machine?.setOtherText(text) }
    override fun submitOther() { machine?.submitOther() }
    override fun submitMulti() { machine?.submitMulti() }
    override fun answerYesNo(yes: Boolean) { machine?.answerYesNo(if (yes) "yes" else "no") }
    override fun setInputValue(text: String) { machine?.setInputValue(text) }
    override fun submitInput() { machine?.submitInput() }
    override fun editInput() { machine?.editInput() }
    override fun confirmInput() { machine?.confirmInput() }
    override fun skipInput() { machine?.skipInput() }
    override fun submitRating(value: Int) { machine?.submitRating(value.toLong()) }
    override fun continueNext() { machine?.continueNext() }
    override fun answerFeedback(helpful: Boolean) { machine?.answerFeedback(helpful) }
    override fun chooseReason(reasonId: String?) { machine?.chooseReason(reasonId) }
    override fun submitOffline(fields: Map<String, String>) { machine?.submitOffline(fields) }
    override fun leaveMessage() { machine?.leaveMessage() }
    override fun requestAgent(reason: String) { machine?.requestAgentAction(reason) }
    override fun retryHandoff() { machine?.retryHandoff() }
    override fun goBack() { machine?.goBack() }
    override fun restart(via: String) { machine?.restart(via) }
    override fun markLinkClick(buttonId: String?, url: String?) { machine?.markLinkClick(if (buttonId != null) "button" else "inline", buttonId, url) }
    override fun markActivity() { machine?.markActivity() }

    override fun prepareDirectStart() {
        val payload = machine?.markDirectStart() ?: return
        host.setPendingFlow(payload)
    }

    override fun reset() {
        machine?.destroy()
        machine = null
        persistence.clear()
        _handedOff.value = false
        _view.value = null
        _status.value = FlowStatus.IDLE
        _isActive.value = false
        _require.value = false
        analytics.flush()
    }
}

/** Engine used when the repository has no flow support (fake repository, preview): never active. */
internal object NoopFlowEngine : HodhodFlowEngine {
    override val isActive: StateFlow<Boolean> = MutableStateFlow(false)
    override val status: StateFlow<FlowStatus> = MutableStateFlow(FlowStatus.NONE)
    override val view: StateFlow<FlowView?> = MutableStateFlow(null)
    override val requireFlow: StateFlow<Boolean> = MutableStateFlow(false)
    override val handedOff: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun load() = Unit
    override fun setTranslator(translate: (key: String, params: Map<String, Any?>) -> String) = Unit
    override fun setReducedMotion(reduced: Boolean) = Unit
    override fun selectOption(nodeId: String) = Unit
    override fun chooseOption(optionId: String) = Unit
    override fun toggleOption(optionId: String) = Unit
    override fun setOtherText(text: String) = Unit
    override fun submitOther() = Unit
    override fun submitMulti() = Unit
    override fun answerYesNo(yes: Boolean) = Unit
    override fun setInputValue(text: String) = Unit
    override fun submitInput() = Unit
    override fun editInput() = Unit
    override fun confirmInput() = Unit
    override fun skipInput() = Unit
    override fun submitRating(value: Int) = Unit
    override fun continueNext() = Unit
    override fun answerFeedback(helpful: Boolean) = Unit
    override fun chooseReason(reasonId: String?) = Unit
    override fun submitOffline(fields: Map<String, String>) = Unit
    override fun leaveMessage() = Unit
    override fun requestAgent(reason: String) = Unit
    override fun retryHandoff() = Unit
    override fun goBack() = Unit
    override fun restart(via: String) = Unit
    override fun markLinkClick(buttonId: String?, url: String?) = Unit
    override fun markActivity() = Unit
    override fun prepareDirectStart() = Unit
    override fun reset() = Unit
}
