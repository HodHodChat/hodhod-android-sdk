package chat.hodhod.sdk.internal.flow

import java.nio.charset.StandardCharsets

// Port of shared/helpers/chatbotFlow/{evaluate,split}.js + the context the engine reads (FlowContext of useFlowContext.js).

/** Environment the flow reads (what the web widget derives in `useFlowContext`). */
internal data class FlowContext(
    val nowMillis: () -> Long = System::currentTimeMillis,
    val utcOffset: String? = null,
    val businessOpen: Boolean = true,
    val agentsOnline: Boolean = false,
    val locale: String = "",
    val pageUrl: String = "",
    val device: String = "mobile",
    val returning: Boolean = false,
    val contactName: String = "",
    val contactEmail: String = "",
    val contactPhone: String = "",
    val inboxId: Long? = null,
    val botId: Any? = null,
    val referrerHost: String = "",
)

internal fun normalizeText(value: Any?): String =
    jsTrim(toAscii(jsToString(value))).lowercase().replace('ي', 'ی').replace('ك', 'ک')

private val WEEKDAY_KEYS = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")

private fun zoneOf(utcOffset: String?): java.util.TimeZone {
    if (utcOffset.isNullOrEmpty()) return java.util.TimeZone.getDefault()
    if (Regex("^[+-]\\d{2}:?\\d{2}\\z").matches(utcOffset)) {
        val withColon = if (utcOffset.contains(':')) utcOffset else utcOffset.substring(0, 3) + ":" + utcOffset.substring(3)
        return java.util.TimeZone.getTimeZone("GMT$withColon")
    }
    return if (utcOffset in java.util.TimeZone.getAvailableIDs()) java.util.TimeZone.getTimeZone(utcOffset) else java.util.TimeZone.getDefault()
}

/** (minutes since midnight, weekday key) of [nowMillis] in the inbox timezone (offset like `+03:30` or an IANA id). */
internal fun zonedParts(nowMillis: Long, utcOffset: String?): Pair<Int, String> {
    val cal = java.util.Calendar.getInstance(zoneOf(utcOffset)).apply { timeInMillis = nowMillis }
    return (cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)) to WEEKDAY_KEYS[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
}

private fun toMinutes(hhmm: Any?): Int? {
    val m = Regex("^(\\d{1,2}):(\\d{2})\\z").find(jsToString(hhmm)) ?: return null
    return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
}

private fun asList(v: Any?): List<Any?> = (v as? List<*>) ?: emptyList()
private fun asBool(v: Any?) = v == true || v == "true"

private fun localeMatches(locale: String, wanted: Any?): Boolean {
    val l = locale.lowercase().replaceFirst("-", "_")
    val w = jsToString(wanted).lowercase().replaceFirst("-", "_")
    return w.isNotEmpty() && (l == w || l.startsWith("${w}_"))
}

private fun pathOf(url: String): String = parseUrl(url)?.pathname ?: url.split(Regex("[?#]"))[0]

private fun textOperator(op: Any?, left: String, right: String): Boolean = when (op) {
    "equals" -> left == right
    "not_equals" -> left != right
    "contains" -> left.contains(right)
    "not_contains" -> !left.contains(right)
    "starts_with" -> left.startsWith(right)
    "ends_with" -> left.endsWith(right)
    else -> false
}

private fun varToString(v: Any?): String = if (v is List<*>) v.joinToString(", ") { jsToString(it) } else jsToString(v)
private fun isSet(v: Any?): Boolean = v != null && v != "" && !(v is List<*> && v.isEmpty())

private fun evaluatePageUrl(rule: JMap, ctx: FlowContext): Boolean {
    val url = ctx.pageUrl
    val value = jsToString(rule["value"])
    if (rule["operator"] == "matches") {
        val re = compileSafe(value)
        return re != null && re.matcher(url).find()
    }
    val subject = if (value.startsWith("/")) pathOf(url) else url
    if (rule["operator"] == "not_contains") return !subject.lowercase().contains(value.lowercase())
    return textOperator(rule["operator"], subject.lowercase(), value.lowercase())
}

private fun evaluateVariable(rule: JMap, vars: Map<String, Any?>): Boolean {
    val raw = vars[rule["variable"]?.let { jsToString(it) }]
    val op = rule["operator"]
    if (op == "is_set") return isSet(raw)
    if (op == "is_not_set") return !isSet(raw)
    val leftRaw = varToString(raw)
    if (op == "matches") {
        val re = compileSafe(jsToString(rule["value"]))
        return re != null && re.matcher(toAscii(leftRaw)).find()
    }
    val rightRaw = renderTemplate(jsToString(rule["value"]), vars, "text")
    if (op in listOf("gt", "gte", "lt", "lte")) {
        val a = jsNumber(jsTrim(toAscii(leftRaw)))
        val b = jsNumber(jsTrim(toAscii(rightRaw)))
        if (jsTrim(leftRaw) == "" || jsTrim(rightRaw) == "") return false
        if (a.isNaN() || b.isNaN()) return false
        return when (op) {
            "gt" -> a > b
            "gte" -> a >= b
            "lt" -> a < b
            else -> a <= b
        }
    }
    return textOperator(op, normalizeText(leftRaw), normalizeText(rightRaw))
}

@Suppress("UNCHECKED_CAST")
internal fun evaluateRule(rule: JMap?, ctx: FlowContext, vars: Map<String, Any?>): Boolean {
    if (rule == null) return false
    return when (rule["type"]) {
        "business_hours" -> (if (ctx.businessOpen) "open" else "closed") == rule["value"]
        "agents_online" -> (if (ctx.agentsOnline) "yes" else "no") == rule["value"]
        "locale" -> {
            val hit = asList(rule["value"]).any { localeMatches(ctx.locale, it) }
            if (rule["operator"] == "is_not_one_of") !hit else rule["operator"] == "is_one_of" && hit
        }
        "page_url" -> evaluatePageUrl(rule, ctx)
        "returning_visitor" -> ctx.returning == asBool(rule["value"])
        "device" -> asList(rule["value"]).contains(ctx.device)
        "variable" -> evaluateVariable(rule, vars)
        "time_of_day" -> {
            val v = rule["value"] as? Map<String, Any?>
            val from = toMinutes(v?.get("from"))
            val to = toMinutes(v?.get("to"))
            if (from == null || to == null) false else {
                val (minutes, _) = zonedParts(ctx.nowMillis(), ctx.utcOffset)
                val inside = if (from <= to) minutes in from until to else minutes >= from || minutes < to
                if (rule["operator"] == "not_between") !inside else inside
            }
        }
        "weekday" -> asList(rule["value"]).contains(zonedParts(ctx.nowMillis(), ctx.utcOffset).second)
        else -> false
    }
}

internal class Branch(val handle: String, val rule: JMap?)

@Suppress("UNCHECKED_CAST")
internal fun pickBranch(node: FlowNode, ctx: FlowContext, vars: Map<String, Any?>): Branch {
    val rules = (node.data["rules"] as? List<Any?>).orEmpty().map { it as? JMap }
    val rule = rules.firstOrNull { evaluateRule(it, ctx, vars) }
    return if (rule != null) Branch(jsToString(rule["id"]), rule) else Branch("else", null)
}

private fun urlRuleMatches(rule: UrlRule, url: String): Boolean {
    if (rule.match == "regex") {
        val re = compileSafe(rule.value)
        return re != null && re.matcher(url).find()
    }
    val u = url.lowercase()
    val v = rule.value.lowercase()
    if (v.isEmpty()) return false
    return when (rule.match) {
        "equals" -> u == v
        "starts_with" -> u.startsWith(v)
        "ends_with" -> u.endsWith(v)
        else -> u.contains(v)
    }
}

internal class TriggerResult(val show: Boolean, val startTopicId: String?, val via: String = "default", val ruleId: String? = null)

internal fun evaluateTriggers(t: TriggerSettings, ctx: FlowContext): TriggerResult {
    val hidden = TriggerResult(false, null)
    if (t.visitor == "new" && ctx.returning) return hidden
    if (t.visitor == "returning" && !ctx.returning) return hidden
    if (t.hours == "open" && !ctx.businessOpen) return hidden
    if (t.hours == "closed" && ctx.businessOpen) return hidden
    if (t.locales.isNotEmpty() && t.locales.none { localeMatches(ctx.locale, it) }) return hidden
    val matched = t.urlRules.filter { urlRuleMatches(it, ctx.pageUrl) }
    if (matched.any { it.mode == "hide" }) return hidden
    val shows = t.urlRules.filter { it.mode == "show" }
    if (shows.isNotEmpty() && matched.none { it.mode == "show" }) return hidden
    val first = matched.firstOrNull { it.mode == "show" && it.startTopicId.isNotEmpty() }
    return TriggerResult(true, first?.startTopicId, if (first != null) "url_rule" else "default", first?.id)
}

// ---- A/B split (FNV-1a over UTF-8, identical to Lark::ChatbotFlow::Split) ----

internal fun fnv1a32(input: String): Long {
    var h = 0x811c9dc5L
    for (b in input.toByteArray(StandardCharsets.UTF_8)) {
        h = h xor (b.toLong() and 0xFF)
        h = (h * 0x01000193L) and 0xFFFFFFFFL
    }
    return h
}

internal fun bucketOf(sessionId: String, nodeId: String): Int = (fnv1a32("$sessionId:$nodeId") % 10000).toInt()

internal fun pickVariant(variants: List<Any?>?, bucket: Int): String? {
    val list = (variants ?: emptyList()).map { it as? JMap ?: emptyMap() }
    fun weight(v: JMap): Double = jsToNumber(v["weight"]).let { if (it.isNaN()) 0.0 else it }.coerceAtLeast(0.0)
    val total = list.sumOf { weight(it) }
    val t = bucket / 10000.0 * total
    var acc = 0.0
    for (v in list) {
        acc += weight(v)
        if (t < acc) return v["id"]?.let { jsToString(it) }
    }
    return list.lastOrNull()?.get("id")?.let { jsToString(it) }
}
