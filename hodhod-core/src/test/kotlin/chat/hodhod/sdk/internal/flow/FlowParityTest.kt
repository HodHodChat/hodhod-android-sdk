package chat.hodhod.sdk.internal.flow

import chat.hodhod.sdk.internal.arr
import chat.hodhod.sdk.internal.obj
import chat.hodhod.sdk.internal.toJson
import chat.hodhod.sdk.internal.toPlain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import kotlin.test.fail

/** Replays every scenario recorded from the real web engine (tools/gen_flow_parity.mjs) against the Kotlin port and compares each step exactly. */
@RunWith(Parameterized::class)
internal class FlowParityTest(private val name: String, private val file: File) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun scenarios(): List<Array<Any>> =
            ParityHarness.fixtureDir().listFiles { f -> f.name.startsWith("scenario-") }!!.sortedBy { it.name }.map { arrayOf(it.name.removePrefix("scenario-").removeSuffix(".json"), it) }
    }

    @Test
    fun replay() {
        val fx = ParityHarness.load(file)
        if (fx["tooNew"] != null) {
            assertNull("too-new schema must be refused", migrateAndNormalize(fx["flow"]!!.toPlain()))
            return
        }
        val doc = migrateAndNormalize(fx["flow"]!!.toPlain())!!
        // normalised definition
        val docJson = mapOf(
            "nodes" to doc.nodes.map { mapOf("id" to it.id, "data" to it.data) },
            "edges" to doc.edges.map { mapOf("id" to it.id, "source" to it.source, "target" to it.target, "sourceHandle" to it.sourceHandle) },
            "menu_order" to doc.menuOrder, "require_flow" to doc.requireFlow, "revision" to doc.revision,
        ).toJson()
        val expDoc = fx["doc"]!!.obj()!!
        val expNodes = JsonArray(expDoc["nodes"]!!.arr()!!.map { n -> JsonObject(mapOf("id" to n.obj()!!["id"]!!, "data" to n.obj()!!["data"]!!)) })
        ParityHarness.diff(JsonObject(expDoc + ("nodes" to expNodes)), docJson, "doc")?.let { fail("$name: normalised definition differs: $it") }

        val opts = fx["options"]!!.obj()!!
        val flowId = (opts["flowId"] as? JsonPrimitive)?.content?.toLongOrNull()
        val revision = (opts["revision"] as? JsonPrimitive)?.content?.toIntOrNull()
        val instant = (opts["instant"] as? JsonPrimitive)?.content == "true"
        val persist = (opts["persist"] as? JsonPrimitive)?.content == "true"
        val bot = opts["bot"].obj()?.let { b -> BotInfo((b["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content, (b["avatar_url"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content) }
        val agentScript = opts["agent"]!!.arr()!!.map { (it as JsonPrimitive).content }
        val webhooks = opts["webhooks"]!!.obj()!!
        val ctxJson = opts["context"].obj()
        var ctx = ctxJson
        val clock = FakeClockScheduler()
        var uuid = 0
        val events = ArrayList<JsonElement>()
        val wire = ArrayList<JsonElement>()
        var mapper = MapperState()
        val agentCalls = ArrayList<JsonElement>()
        val webhookCalls = ArrayList<JsonElement>()
        var agentIdx = 0
        val hookIdx = HashMap<String, Int>()
        var saved: JMap? = null
        val persistence = if (persist) object : FlowPersistence {
            override fun load(): JMap? = saved
            override fun save(state: JMap) {
                saved = state.toJson().toPlain() as JMap
            }
            override fun clear() {
                saved = null
            }
        } else null

        fun makeMachine(initialSession: String?) = FlowMachine(
            doc = doc, context = { ParityHarness.ctxFrom(ctx, flowId) }, t = ParityHarness.tFn, initialSessionId = initialSession, flowId = flowId, revision = revision,
            onRequestAgent = { p, cb ->
                agentCalls += p.toMap().toJson()
                val mode = agentScript[minOf(agentIdx, agentScript.size - 1)]
                agentIdx += 1
                when (mode) {
                    "none" -> Unit
                    "reject" -> cb(Result.failure(IllegalStateException("x")))
                    else -> cb(Result.success(AgentResult(true, 42L)))
                }
            },
            onEvent = { e ->
                events += e.toMap().toJson()
                wire += mapFlowEvent(mapper, e).events.map { it.toJson() }
            },
            runWebhook = { node, vars, cb ->
                webhookCalls += mapOf("nodeId" to node.id, "vars" to vars).toJson()
                val list = webhooks[node.id]?.arr() ?: JsonArray(listOf(JsonObject(mapOf("ok" to JsonPrimitive(false), "reason" to JsonPrimitive("config")))))
                val i = hookIdx[node.id] ?: 0
                hookIdx[node.id] = i + 1
                val r = list[minOf(i, list.size - 1)]
                if (r is JsonPrimitive && r.content == "reject") throw IllegalStateException("net")
                val o = r.obj()!!
                cb(
                    WebhookResult(
                        ok = (o["ok"] as? JsonPrimitive)?.content == "true", vars = (o["vars"].obj()?.toPlain() as? JMap).orEmpty(),
                        reason = (o["reason"] as? JsonPrimitive)?.content, cached = (o["cached"] as? JsonPrimitive)?.content == "true",
                    ),
                )
            },
            persistence = persistence, scheduler = clock, instant = instant, bot = bot, now = { clock.now },
            newUuid = { "00000000-0000-4000-8000-${(++uuid).toString().padStart(12, '0')}" },
        )

        var m = makeMachine(fx["options"]!!.obj()!!["sessionId"].let { (it as JsonPrimitive).content })
        val actions = fx["actions"]!!.arr()!!
        actions.forEachIndexed { idx, step ->
            val a = step.obj()!!["action"]!!.obj()!!
            val op = (a["op"] as JsonPrimitive).content
            fun str(k: String) = (a[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            var returned: Any? = null
            when (op) {
                "start" -> m.start(str("nodeId"))
                "selectOption" -> m.selectOption(str("id")!!)
                "chooseOption" -> m.chooseOption(str("id")!!)
                "toggleOption" -> m.toggleOption(str("id")!!)
                "setOtherText" -> m.setOtherText(str("text")!!)
                "submitOther" -> m.submitOther()
                "submitMulti" -> m.submitMulti()
                "answerYesNo" -> m.answerYesNo(str("branch")!!)
                "setInputValue" -> m.setInputValue(str("text")!!)
                "submitInput" -> m.submitInput()
                "editInput" -> m.editInput()
                "confirmInput" -> m.confirmInput()
                "skipInput" -> m.skipInput()
                "submitRating" -> m.submitRating(str("value")!!.toLong())
                "continueNext" -> m.continueNext()
                "answerFeedback" -> m.answerFeedback(str("helpful") == "true")
                "chooseReason" -> m.chooseReason(str("id"))
                "submitOffline" -> m.submitOffline(a["fields"]!!.obj()!!.entries.associate { it.key to (it.value as JsonPrimitive).content })
                "leaveMessage" -> m.leaveMessage()
                "requestAgent" -> m.requestAgentAction(str("reason") ?: "manual")
                "retryHandoff" -> m.retryHandoff()
                "goBack" -> m.goBack()
                "restart" -> m.restart(str("via") ?: "nav")
                "markLinkClick" -> m.markLinkClick(str("kind") ?: "inline", str("buttonId"), str("url"))
                "markActivity" -> m.markActivity()
                "markDirectStart" -> returned = m.markDirectStart()
                "setContext" -> ctx = JsonObject((ctx ?: JsonObject(emptyMap())) + a["patch"]!!.obj()!!)
                "advance" -> clock.runUntil(clock.now + str("ms")!!.toLong())
                "reload" -> {
                    m.destroy()
                    mapper = MapperState()
                    m = makeMachine(null)
                    if (!m.hydrate()) m.start()
                }
                "destroy" -> m.destroy()
                else -> fail("unknown op $op")
            }
            if (a["noSettle"] == null && op !in listOf("advance", "setContext", "markActivity")) clock.settle()

            val snap = linkedMapOf<String, Any?>(
                "view" to ParityHarness.viewJson(m.view()),
                "variables" to m.variables(),
                "trail" to m.trailIds(),
                "handoffState" to m.handoffState,
                "flowPath" to m.flowPath(),
                "session" to mapOf("id" to m.sessionId, "restarts" to m.restarts.toLong()),
                "debug" to mapOf("values" to m.debugValues(), "picks" to m.debugPicks(), "varsSet" to m.debugVarsSet()),
                "events" to JsonArray(events.toList()), "wire" to JsonArray(wire.toList()),
                "agentCalls" to JsonArray(agentCalls.toList()), "webhookCalls" to JsonArray(webhookCalls.toList()),
                "persisted" to saved, "returned" to returned, "now" to clock.now,
            )
            events.clear()
            wire.clear()
            agentCalls.clear()
            webhookCalls.clear()
            val expected = step.obj()!!["expect"]!!
            ParityHarness.diff(expected, snap.toJson(), "step#$idx($op)")?.let { fail("$name: $it") }
        }
    }
}
