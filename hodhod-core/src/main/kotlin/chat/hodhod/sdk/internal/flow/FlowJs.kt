package chat.hodhod.sdk.internal.flow

import java.nio.charset.StandardCharsets

// Small helpers that reproduce the JavaScript semantics the web flow engine relies on (trim, Number(), encodeURIComponent, new URL()).
// The flow engine is a line-by-line port of shared/composables/useChatbotFlow.js; these keep the observable behaviour identical.

/** JSON-shaped object (parsed flow definitions, props of events, controls). */
internal typealias JMap = Map<String, Any?>

/** The set of characters `String.prototype.trim` and the regex class `\s` treat as whitespace. */
internal const val JS_WS_CLASS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

private fun isJsWs(c: Char): Boolean = c in "\t\n\u000B\u000C\r \u00A0\u1680\u2028\u2029\u202F\u205F\u3000\uFEFF" || c in '\u2000'..'\u200A'

internal fun jsTrim(s: String): String {
    var a = 0
    var b = s.length
    while (a < b && isJsWs(s[a])) a++
    while (b > a && isJsWs(s[b - 1])) b--
    return s.substring(a, b)
}

internal fun jsIsSpace(c: Char?): Boolean = c == null || isJsWs(c)

/** JS `Number(string)`; NaN when not a numeric literal. */
internal fun jsNumber(raw: String): Double {
    val s = jsTrim(raw)
    if (s.isEmpty()) return 0.0
    return when {
        s == "Infinity" || s == "+Infinity" -> Double.POSITIVE_INFINITY
        s == "-Infinity" -> Double.NEGATIVE_INFINITY
        Regex("^0[xX][0-9a-fA-F]+$").matches(s) -> s.substring(2).toBigInteger(16).toDouble()
        Regex("^0[oO][0-7]+$").matches(s) -> s.substring(2).toBigInteger(8).toDouble()
        Regex("^0[bB][01]+$").matches(s) -> s.substring(2).toBigInteger(2).toDouble()
        Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s) -> s.toDouble()
        else -> Double.NaN
    }
}

/** Value coerced like JS `Number(x)` for the primitive kinds JSON can hold. */
internal fun jsToNumber(v: Any?): Double = when (v) {
    null -> 0.0
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> jsNumber(v)
    else -> Double.NaN
}

internal fun isFiniteNumber(v: Any?): Boolean = v is Number && v.toDouble().isFinite()

/** `Number.isInteger`. */
internal fun isIntegerNumber(v: Any?): Boolean = v is Number && v.toDouble().let { it.isFinite() && it == Math.floor(it) }

/** JS `String(number)` for the numbers that end up in text (ratings, counts). */
internal fun jsNumToString(d: Double): String =
    if (d.isFinite() && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

internal fun jsToString(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Double -> jsNumToString(v)
    is Float -> jsNumToString(v.toDouble())
    else -> v.toString()
}

/** JS truthiness. */
internal fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is String -> v.isNotEmpty()
    is Number -> v.toDouble().let { it != 0.0 && !it.isNaN() }
    else -> true
}

private const val URI_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

internal fun encodeURIComponent(s: String): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(StandardCharsets.UTF_8)) {
        val c = (b.toInt() and 0xFF)
        if (c < 128 && URI_UNRESERVED.indexOf(c.toChar()) >= 0) sb.append(c.toChar()) else sb.append('%').append("%02X".format(c))
    }
    return sb.toString()
}

internal data class ParsedUrl(val protocol: String, val hostname: String, val pathname: String)

private val SPECIAL = setOf("http", "https", "ftp", "ws", "wss", "file")

/** Minimal `new URL(input)`: null when the WHATWG parser would throw. */
internal fun parseUrl(input: String): ParsedUrl? {
    val s = input.trim { it <= ' ' }.filter { it != '\t' && it != '\n' && it != '\r' }
    val m = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(s) ?: return null
    val scheme = m.groupValues[1].lowercase()
    var rest = s.substring(m.value.length)
    val protocol = "$scheme:"
    val cut = rest.indexOfFirst { it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
    rest = rest.substring(0, cut)
    if (scheme in SPECIAL) {
        rest = rest.trimStart('/', '\\')
        val end = rest.indexOfFirst { it == '/' || it == '\\' }.let { if (it < 0) rest.length else it }
        var authority = rest.substring(0, end)
        authority = authority.substringAfterLast('@')
        val ipv6 = authority.startsWith("[") && authority.contains("]")
        val host = if (ipv6) authority.substring(0, authority.indexOf(']') + 1).lowercase() else authority.replace(Regex(":\\d*$"), "").lowercase()
        if (scheme != "file" && host.isEmpty()) return null
        if (!ipv6 && Regex("[\\s#%/<>?@\\[\\\\\\]^|]").containsMatchIn(host)) return null
        val path = encodePath(rest.substring(end).replace('\\', '/').ifEmpty { "/" }, true)
        return ParsedUrl(protocol, host, path)
    }
    if (rest.startsWith("//")) {
        val body = rest.substring(2)
        val end = body.indexOf('/').let { if (it < 0) body.length else it }
        val host = body.substring(0, end).substringAfterLast('@').replace(Regex(":\\d*$"), "").lowercase()
        return ParsedUrl(protocol, host, encodePath(body.substring(end), true))
    }
    return ParsedUrl(protocol, "", encodePath(rest, false))
}

/** WHATWG path percent-encoding (space, quotes, brackets and non-ASCII are escaped; `%` is kept). */
private fun encodePath(path: String, special: Boolean): String {
    val sb = StringBuilder()
    var i = 0
    while (i < path.length) {
        val cp = path.codePointAt(i)
        val n = Character.charCount(cp)
        val ch = cp.toChar()
        val escape = cp < 0x20 || cp > 0x7E || (special && cp < 0x80 && ch in " \"#<>?`{}")
        if (escape) path.substring(i, i + n).toByteArray(StandardCharsets.UTF_8).forEach { sb.append('%').append("%02X".format(it.toInt() and 0xFF)) } else sb.append(path, i, i + n)
        i += n
    }
    return sb.toString()
}
