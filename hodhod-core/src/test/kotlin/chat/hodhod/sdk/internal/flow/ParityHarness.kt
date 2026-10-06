package chat.hodhod.sdk.internal.flow

import chat.hodhod.sdk.FlowView
import chat.hodhod.sdk.internal.arr
import chat.hodhod.sdk.internal.obj
import chat.hodhod.sdk.internal.parseJson
import chat.hodhod.sdk.internal.toJson
import chat.hodhod.sdk.internal.toPlain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.time.Instant

/** Shared helpers for the parity tests (fixtures are produced by `tools/gen_flow_parity.mjs` from the real web engine). */
internal object ParityHarness {
    const val BASE_NOW = 1_790_000_000_000L

    fun fixtureDir(): File {
        val url = ParityHarness::class.java.classLoader!!.getResource("flow-parity") ?: error("flow-parity fixtures missing; run tools/gen_flow_parity.mjs")
        return File(url.toURI())
    }

    fun load(file: File): JsonObject = parseJson(file.readText()).obj()!!

    /** Deep equality; numbers compare by value (JSON 3 == 3.0), objects ignore key order. Returns the first difference or null. */
    fun diff(expected: JsonElement?, actual: JsonElement?, path: String = "$"): String? {
        val e = expected ?: JsonNull
        val a = actual ?: JsonNull
        return when {
            e is JsonObject && a is JsonObject -> {
                (e.keys + a.keys).sorted().firstNotNullOfOrNull { k ->
                    if (k !in e) "$path.$k: unexpected key in actual (${a[k].toString().take(200)})"
                    else if (k !in a) "$path.$k: missing in actual (expected ${e[k].toString().take(200)})"
                    else diff(e[k], a[k], "$path.$k")
                }
            }
            e is JsonArray && a is JsonArray -> {
                if (e.size != a.size) "$path: array size expected ${e.size} actual ${a.size}\n  expected=${e.toString().take(400)}\n  actual=${a.toString().take(400)}"
                else e.indices.firstNotNullOfOrNull { diff(e[it], a[it], "$path[$it]") }
            }
            e is JsonPrimitive && a is JsonPrimitive && e !is JsonNull && a !is JsonNull -> {
                val en = if (e.isString) null else e.doubleOrNull
                val an = if (a.isString) null else a.doubleOrNull
                when {
                    en != null && an != null -> if (en == an) null else "$path: expected $e actual $a"
                    e.isString != a.isString -> "$path: expected $e actual $a"
                    e.content == a.content -> null
                    else -> "$path: expected $e actual $a"
                }
            }
            e is JsonNull && a is JsonNull -> null
            else -> "$path: expected ${e.toString().take(200)} actual ${a.toString().take(200)}"
        }
    }

    fun ctxFrom(o: JsonObject?, flowId: Any?): FlowContext {
        val c = o ?: JsonObject(emptyMap())
        val contact = c["contact"].obj()
        fun s(k: String) = (c[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        fun b(k: String) = (c[k] as? JsonPrimitive)?.content == "true"
        val now = Instant.parse(s("now")!!).toEpochMilli()
        return FlowContext(
            nowMillis = { now }, utcOffset = s("utcOffset"), businessOpen = b("businessOpen"), agentsOnline = b("agentsOnline"), locale = s("locale").orEmpty(),
            pageUrl = s("pageUrl").orEmpty(), device = s("device").orEmpty(), returning = b("returning"),
            contactName = (contact?.get("name") as? JsonPrimitive)?.content.orEmpty(), contactEmail = (contact?.get("email") as? JsonPrimitive)?.content.orEmpty(),
            contactPhone = (contact?.get("phone") as? JsonPrimitive)?.content.orEmpty(), inboxId = (c["inboxId"] as? JsonPrimitive)?.content?.toLongOrNull(),
            botId = flowId, referrerHost = s("referrerHost").orEmpty(),
        )
    }

    fun jsonStringify(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            v.forEach { c ->
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c == '\b' -> append("\\b")
                    c == '\u000C' -> append("\\f")
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
        is Map<*, *> -> v.entries.filter { it.value != null }.joinToString(",", "{", "}") { jsonStringify(it.key.toString()) + ":" + jsonStringify(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { jsonStringify(it) }
        is Double -> jsToString(v)
        else -> v.toString()
    }

    val tFn: Translate = { key, params -> if (params.isEmpty()) key else "$key:${jsonStringify(params)}" }

    fun viewJson(v: FlowView): JsonElement {
        val m = linkedMapOf<String, Any?>(
            "identity" to v.identity?.let { mapOf("name" to it.name, "avatarUrl" to it.avatarUrl) },
            "breadcrumb" to v.breadcrumb,
            "blocks" to v.blocks.map { b ->
                linkedMapOf<String, Any?>("key" to b.key, "nodeId" to b.nodeId, "rich" to b.rich, "kind" to b.kind).also {
                    if (b.image != null) it["image"] = b.image
                    if (b.imageAlt != null) it["imageAlt"] = b.imageAlt
                    if (b.buttons.isNotEmpty()) it["buttons"] = b.buttons.map { x -> mapOf("id" to x.id, "label" to x.label, "url" to x.url).filterValues { y -> y != null } }
                }
            },
            "typing" to v.typing,
            "control" to v.control?.let { mapOf("type" to it.type) + it.fields },
            "nav" to mapOf("canBack" to v.nav.canBack, "canRestart" to v.nav.canRestart, "agentLink" to v.nav.agentLink),
            "announce" to v.announce, "focusKey" to v.focusKey,
        )
        return m.toJson()
    }
}

/** Deterministic timers identical to `runtime.mjs`. */
internal class FakeClockScheduler(var now: Long = ParityHarness.BASE_NOW) : FlowScheduler {
    private class T(val id: Int, val at: Long, val fn: () -> Unit)

    private var seq = 0
    private val timers = LinkedHashMap<Int, T>()
    override fun setTimeout(ms: Long, fn: () -> Unit): Any {
        seq += 1
        timers[seq] = T(seq, now + ms, fn)
        return seq
    }

    override fun clearTimeout(id: Any) {
        timers.remove(id as Int)
    }

    private fun next(limit: Long): T? = timers.values.filter { it.at <= limit }.minWithOrNull(compareBy<T>({ it.at }, { it.id }))

    fun runUntil(limit: Long) {
        var guard = 0
        while (guard++ < 10000) {
            val t = next(limit) ?: break
            timers.remove(t.id)
            now = maxOf(now, t.at)
            t.fn()
        }
        now = maxOf(now, limit)
    }

    fun settle() {
        val start = now
        var guard = 0
        while (guard++ < 10000) {
            val t = next(start + 9000) ?: break
            timers.remove(t.id)
            now = maxOf(now, t.at)
            t.fn()
        }
    }
}
