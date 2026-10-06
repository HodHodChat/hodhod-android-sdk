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
import org.junit.Test
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Pure-function parity: the Kotlin helpers against vectors produced by the real web helpers (template, markdown, validation, regex guard, ...). */
internal class FlowVectorsTest {
    private fun vectors(name: String): JsonObject = ParityHarness.load(File(ParityHarness.fixtureDir(), "vectors-$name.json"))
    private fun cases(name: String): List<JsonObject> = vectors(name)["cases"]!!.arr()!!.map { it.obj()!! }
    private fun prim(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun check(label: String, expected: JsonElement?, actual: JsonElement?) {
        ParityHarness.diff(expected, actual)?.let { fail("$label: $it") }
    }

    @Test
    fun template() {
        val v = vectors("template")
        @Suppress("UNCHECKED_CAST")
        val vars = v["vars"]!!.toPlain() as Map<String, Any?>
        cases("template").forEach { c ->
            assertEquals(prim(c, "output"), renderTemplate(prim(c, "input")!!, vars, prim(c, "ctx")!!), "template ${c["input"]} (${c["ctx"]})")
        }
    }

    @Test
    fun markdown() {
        @Suppress("UNCHECKED_CAST")
        val vars = vectors("markdown")["vars"]!!.toPlain() as Map<String, Any?>
        cases("markdown").forEach { c -> check("markdown ${c["input"].toString().take(60)}", c["output"], renderRich(prim(c, "input")!!, vars).toJson()) }
    }

    @Test
    fun inputValidation() {
        cases("input").forEach { c ->
            @Suppress("UNCHECKED_CAST")
            val data = (c["data"]!!.toPlain() as Map<String, Any?>) + ("kind" to "input")
            val r = validateInput(data, prim(c, "raw"))
            val actual = if (r.ok) mapOf("ok" to true, "value" to r.value) else mapOf("ok" to false, "reason" to r.reason, "params" to r.params)
            check("input ${c["data"]} / ${c["raw"].toString().take(40)}", c["result"], actual.toJson())
        }
    }

    @Test
    fun regexGuard() {
        cases("regex").forEach { c ->
            val input = prim(c, "input")!!
            val reason = analyzeRegex(input)
            val exp = c["analysis"]!!.obj()!!
            assertEquals((exp["ok"] as JsonPrimitive).content == "true", reason == null, "regex ok for '$input' (expected $exp, got $reason)")
            if (reason != null) assertEquals(prim(exp, "reason"), reason, "regex reason for '$input'")
            assertEquals((c["compiles"] as JsonPrimitive).content == "true", compileSafe(input) != null, "regex compiles '$input'")
        }
    }

    @Test
    fun rules() {
        cases("rules").forEach { c ->
            @Suppress("UNCHECKED_CAST")
            val vars = c["vars"]!!.toPlain() as Map<String, Any?>
            val ctx = ParityHarness.ctxFrom(c["ctx"]!!.obj(), null)
            @Suppress("UNCHECKED_CAST")
            val rule = c["rule"]!!.toPlain() as Map<String, Any?>
            assertEquals((c["result"] as JsonPrimitive).content == "true", evaluateRule(rule, ctx, vars), "rule ${c["rule"]} ctx ${c["ctx"]}")
        }
    }

    @Test
    fun triggers() {
        cases("triggers").forEach { c ->
            val settings = normalizeSettings(mapOf("triggers" to c["triggers"]!!.toPlain()))
            val r = evaluateTriggers(settings.triggers, ParityHarness.ctxFrom(c["ctx"]!!.obj(), null))
            val label = "triggers ${c["triggers"]} ctx ${c["ctx"]}"
            assertEquals((c["show"] as JsonPrimitive).content == "true", r.show, label)
            assertEquals(prim(c, "startTopicId"), r.startTopicId, label)
            assertEquals(prim(c, "via"), r.via, label)
            assertEquals(prim(c, "ruleId"), r.ruleId, label)
        }
    }

    @Test
    fun split() {
        cases("split").forEach { c ->
            val vs = c["variants"]
            if (vs == null || vs is JsonNull || vs is JsonArray) {
                if (c.containsKey("variants")) {
                    @Suppress("UNCHECKED_CAST")
                    val list = (vs as? JsonArray)?.let { it.toPlain() as List<Any?> }
                    assertEquals(prim(c, "variant"), pickVariant(list, prim(c, "bucket")!!.toInt()), "split fallback $c")
                    return@forEach
                }
            }
            val sid = prim(c, "session")!!
            val nid = prim(c, "node")!!
            assertEquals(prim(c, "fnv")!!.toLong(), fnv1a32("$sid:$nid"), "fnv $sid:$nid")
            val bucket = bucketOf(sid, nid)
            assertEquals(prim(c, "bucket")!!.toInt(), bucket, "bucket $sid:$nid")
            assertEquals(prim(c, "variant"), pickVariant(listOf(mapOf("id" to "a", "weight" to 30L), mapOf("id" to "b", "weight" to 70L)), bucket), "variant $sid:$nid")
            assertEquals(prim(c, "variant3"), pickVariant(listOf(mapOf("id" to "a", "weight" to 0L), mapOf("id" to "b", "weight" to 0L), mapOf("id" to "c")), bucket), "variant3 $sid:$nid")
        }
    }

    @Test
    fun urls() {
        cases("url").forEach { c ->
            val url = prim(c, "url")!!
            val parsed = parseUrl(url)
            val parts = c["parts"]
            if (parts == null || parts is JsonNull) assertEquals(null, parsed, "url $url should be rejected")
            else {
                val p = parts.obj()!!
                assertTrue(parsed != null, "url $url should parse")
                assertEquals(prim(p, "protocol"), parsed!!.protocol, "protocol $url")
                assertEquals(prim(p, "hostname"), parsed.hostname, "hostname $url")
                assertEquals(prim(p, "pathname"), parsed.pathname, "pathname $url")
            }
            assertEquals((c["allowedHttps"] as JsonPrimitive).content == "true", isAllowedUrl(url, listOf("https:")), "allowed https $url")
            assertEquals((c["allowedAll"] as JsonPrimitive).content == "true", isAllowedUrl(url, DEFAULT_LINK_PROTOCOLS), "allowed all $url")
        }
    }

    @Test
    fun misc() {
        val misc = cases("misc").first()
        misc["numbers"]!!.arr()!!.map { it.obj()!! }.forEach { c ->
            val exp = prim(c, "number")!!
            val got = jsNumber(prim(c, "input")!!)
            if (exp == "NaN") assertTrue(got.isNaN(), "Number('${c["input"]}') should be NaN, got $got")
            else assertEquals(if (exp == "Infinity") Double.POSITIVE_INFINITY else if (exp == "-Infinity") Double.NEGATIVE_INFINITY else exp.toDouble(), got, "Number('${c["input"]}')")
        }
        misc["normalize"]!!.arr()!!.map { it.obj()!! }.forEach { c ->
            assertEquals(prim(c, "normalize"), normalizeText(prim(c, "input")), "normalizeText ${c["input"]}")
            assertEquals(prim(c, "ascii"), toAscii(prim(c, "input")!!), "toAscii ${c["input"]}")
        }
    }

    @Test
    fun nowIsParsed() {
        assertEquals(Instant.parse("2026-10-03T10:00:00Z").toEpochMilli(), ParityHarness.ctxFrom(JsonObject(mapOf("now" to JsonPrimitive("2026-10-03T10:00:00Z"))), null).nowMillis())
    }
}
