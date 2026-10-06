package chat.hodhod.sdk.internal.flow

import java.util.regex.Pattern

// Port of shared/helpers/chatbotFlow/template.js ({{variable}} templating, single pass, never re-expands values).

internal const val TEMPLATE_VALUE_LIMIT = 500
internal const val URL_FINAL_LIMIT = 2000

private val PLACEHOLDER: Pattern = Pattern.compile(
    "\\{\\{[$JS_WS_CLASS]*([a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*)[$JS_WS_CLASS]*" +
        "(?:\\|[$JS_WS_CLASS]*default[$JS_WS_CLASS]*:[$JS_WS_CLASS]*\"([^\"\\n]{0,100})\"[$JS_WS_CLASS]*)?\\}\\}",
)

internal sealed interface TplToken {
    data class Text(val value: String) : TplToken
    data class Var(val name: String, val fallback: String?, val raw: String) : TplToken
}

internal class ParsedTemplate(val tokens: List<TplToken>, val errors: Int)

internal fun parseTemplate(src: String): ParsedTemplate {
    val tokens = ArrayList<TplToken>()
    val text = StringBuilder()
    var errors = 0
    fun flush() {
        if (text.isNotEmpty()) tokens += TplToken.Text(text.toString())
        text.setLength(0)
    }
    var i = 0
    while (i < src.length) {
        if (src[i] == '\\' && src.startsWith("{{", i + 1)) {
            text.append("{{")
            i += 3
        } else if (src.startsWith("{{", i)) {
            val m = PLACEHOLDER.matcher(src).useAnchoringBounds(false).useTransparentBounds(true).region(i, src.length)
            if (m.lookingAt()) {
                flush()
                tokens += TplToken.Var(m.group(1)!!, m.group(2), m.group(0)!!)
                i += m.group(0)!!.length
            } else {
                errors += 1
                text.append("{{")
                i += 2
            }
        } else {
            text.append(src[i])
            i += 1
        }
    }
    flush()
    return ParsedTemplate(tokens, errors)
}

private fun lookup(vars: Map<String, Any?>, name: String): Any? {
    if (vars.containsKey(name)) return vars[name]
    var acc: Any? = vars
    for (k in name.split('.')) {
        acc = if (acc is Map<*, *> && acc.containsKey(k)) acc[k] else return null
    }
    return acc
}

private fun stringify(v: Any?): String = when (v) {
    null -> ""
    is List<*> -> v.joinToString(", ") { stringify(it) }
    is Map<*, *> -> ""
    else -> jsToString(v)
}

internal fun resolveValue(token: TplToken.Var, vars: Map<String, Any?>): String {
    val raw = stringify(lookup(vars, token.name))
    val value = if (raw == "") token.fallback ?: "" else raw
    return if (value.length > TEMPLATE_VALUE_LIMIT) value.substring(0, TEMPLATE_VALUE_LIMIT) else value
}

/** [ctx] is `text` or `url` (the only contexts the visitor-side engine uses). */
internal fun renderTemplate(input: String, vars: Map<String, Any?>, ctx: String = "text"): String {
    val sb = StringBuilder()
    for (t in parseTemplate(input).tokens) {
        when (t) {
            is TplToken.Text -> sb.append(t.value)
            is TplToken.Var -> sb.append(if (ctx == "url") encodeURIComponent(resolveValue(t, vars)) else resolveValue(t, vars))
        }
    }
    return sb.toString()
}

internal fun isAllowedUrl(url: String?, protocols: List<String> = listOf("https:")): Boolean {
    if (url.isNullOrEmpty() || url.length > URL_FINAL_LIMIT) return false
    return parseUrl(url)?.protocol in protocols
}
