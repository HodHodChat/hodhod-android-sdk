package chat.hodhod.sdk.internal.flow

import java.util.regex.Pattern

// Port of shared/helpers/chatbotFlow/regexGuard.js + inputValidation.js.

private const val REGEX_MAX = 200

private val DIGITS = Regex("[۰-۹٠-٩]")

/** Persian/Arabic-Indic digits to ASCII. */
internal fun toAscii(s: String): String = DIGITS.replace(s) { (it.value[0].code % 16).toString() }

private class Stored(val src: String, val flags: String)

private fun splitStored(text: String): Stored {
    val m = Regex("^/(.*)/([a-z]*)\\z", RegexOption.DOT_MATCHES_ALL).find(text)
    return if (m != null) Stored(m.groupValues[1], m.groupValues[2]) else Stored(text, "")
}

private class Quant(val max: Double, val len: Int)

private fun quantAt(src: String, i: Int): Quant? {
    val c = src[i]
    if (c == '*' || c == '+') return Quant(Double.POSITIVE_INFINITY, 1)
    if (c == '?') return Quant(1.0, 1)
    if (c == '{') {
        val m = Regex("^\\{(\\d+)(,(\\d*))?\\}").find(src.substring(i)) ?: return null
        val min = m.groupValues[1].toDouble()
        var max = min
        if (m.groups[2] != null) max = if (m.groupValues[3] == "") Double.POSITIVE_INFINITY else m.groupValues[3].toDouble()
        return Quant(max, m.value.length)
    }
    return null
}

private fun scanForbidden(src: String): String? {
    var inClass = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '\\') {
            val n = src.getOrNull(i + 1)
            if (n != null && n in '1'..'9' || (n == 'k' && src.getOrNull(i + 2) == '<')) return "backreference"
            i += 1
        } else if (inClass) {
            if (c == ']') inClass = false
        } else if (c == '[') {
            inClass = true
        } else if (c == '(' && Regex("^\\(\\?<?[=!]").containsMatchIn(src.substring(i, minOf(src.length, i + 4)))) {
            return "lookaround"
        }
        i += 1
    }
    return null
}

private class Group(var hasQuant: Boolean = false, var hasAlt: Boolean = false)
private class Last(val type: String, val hasQuant: Boolean = false, val hasAlt: Boolean = false)

private val JS_ESCAPES = "dDwWsSbBnrtfv0cxuk"

/** True when the pattern is valid JS syntax in the parts Java is more lenient about (stacked quantifiers, inline flag groups, ...). */
private fun jsSyntaxOk(src: String): Boolean {
    var inClass = false
    var prevQuant = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '\\') {
            prevQuant = false
            i += 2
            continue
        }
        if (inClass) {
            if (c == ']') inClass = false
            i += 1
            continue
        }
        when {
            c == '[' -> {
                inClass = true
                prevQuant = false
            }
            c == '(' -> {
                prevQuant = false
                if (src.startsWith("?", i + 1) && !src.startsWith("?:", i + 1) && !Regex("^\\?<[A-Za-z_$]").containsMatchIn(src.substring(i + 1, minOf(src.length, i + 4)))) return false
            }
            c == '*' || c == '+' || c == '?' || c == '{' -> {
                val q = if (c == '{') quantAt(src, i) else Quant(1.0, 1)
                if (q == null) {
                    prevQuant = false
                } else {
                    if (prevQuant) return false
                    i += q.len - 1
                    if (src.getOrNull(i + 1) == '?') i += 1
                    prevQuant = true
                }
            }
            else -> prevQuant = false
        }
        i += 1
    }
    return true
}

/** JS RegExp source to an equivalent java.util.regex pattern (the safe subset only: no backrefs/lookaround). */
internal fun translateJsRegex(src: String, unicode: Boolean = false): String {
    val sb = StringBuilder()
    var inClass = false
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '\\' && i + 1 < src.length) {
            val n = src[i + 1]
            when {
                n == 's' -> sb.append(if (inClass) JS_WS_CLASS else "[$JS_WS_CLASS]")
                n == 'S' && !inClass -> sb.append("[^$JS_WS_CLASS]")
                n == 'v' -> sb.append("\\x0B")
                n == '0' && !(src.getOrNull(i + 2)?.isDigit() ?: false) -> sb.append("\\x00")
                (n == 'p' || n == 'P') && unicode -> sb.append(c).append(n)
                n.isLetter() && n !in JS_ESCAPES -> sb.append(n) // JS identity escape (\A is just "A")
                n == '/' -> sb.append('/')
                else -> sb.append(c).append(n)
            }
            i += 2
            continue
        }
        if (inClass) {
            if (c == ']') inClass = false
            if (c == '[') sb.append("\\[") else sb.append(c)
        } else when (c) {
            '[' -> {
                inClass = true
                sb.append(c)
                if (src.startsWith("^", i + 1)) {
                    sb.append('^')
                    i += 1
                }
                if (src.startsWith("]", i + 1)) {
                    sb.append("\\]")
                    i += 1
                }
            }
            '.' -> sb.append("[^\\n\\r\\u2028\\u2029]")
            '$' -> sb.append("\\z")
            else -> sb.append(c)
        }
        i += 1
    }
    return sb.toString()
}

private fun compileJava(src: String, flags: String): Pattern? = try {
    var f = 0
    if ('i' in flags) f = f or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    if (!jsSyntaxOk(src)) null else Pattern.compile(translateJsRegex(src, 'u' in flags), f)
} catch (_: Exception) {
    null
}

/** `{ok:true}` or `{ok:false, reason}` (too_long | flags | backreference | lookaround | syntax | nested_quantifier). */
internal fun analyzeRegex(stored: String): String? {
    val (src, flags) = splitStored(stored).let { it.src to it.flags }
    if (src.length > REGEX_MAX) return "too_long"
    if (!Regex("^[iu]*\\z").matches(flags) || flags.toSet().size != flags.length) return "flags"
    scanForbidden(src)?.let { return it }
    if (compileJava(src, flags) == null) return "syntax"
    val stack = ArrayList<Group>()
    var inClass = false
    var last: Last? = null
    var i = 0
    while (i < src.length) {
        val c = src[i]
        if (c == '\\') {
            last = Last("atom")
            i += 1
        } else if (inClass) {
            if (c == ']') {
                inClass = false
                last = Last("atom")
            }
        } else if (c == '[') {
            inClass = true
        } else if (c == '(') {
            stack += Group()
            if (src.startsWith("?:", i + 1)) i += 2
            else if (Regex("^\\?<[A-Za-z_$]").containsMatchIn(src.substring(minOf(src.length, i + 1), minOf(src.length, i + 4)))) i = src.indexOf('>', i)
            last = null
        } else if (c == ')') {
            val g = if (stack.isEmpty()) return "syntax" else stack.removeAt(stack.size - 1)
            last = Last("group", g.hasQuant, g.hasAlt)
            stack.lastOrNull()?.let {
                if (g.hasQuant) it.hasQuant = true
                if (g.hasAlt) it.hasAlt = true
            }
        } else if (c == '|') {
            stack.lastOrNull()?.hasAlt = true
            last = null
        } else {
            val q = quantAt(src, i)
            if (q != null) {
                if (last?.type == "group" && q.max > 1 && (last.hasQuant || last.hasAlt)) return "nested_quantifier"
                stack.lastOrNull()?.hasQuant = true
                i += q.len - 1
                if (src.getOrNull(i + 1) == '?') i += 1
            } else {
                last = Last("atom")
            }
        }
        i += 1
    }
    if (stack.isNotEmpty() || inClass) return "syntax"
    return null
}

/** Safe, compiled pattern or null (unsafe / invalid). */
internal fun compileSafe(stored: String?): Pattern? {
    val s = splitStored(stored ?: "")
    if (s.src.isEmpty() || analyzeRegex(stored ?: "") != null) return null
    return compileJava(s.src, s.flags)
}

internal class InputResult(val ok: Boolean, val value: String = "", val reason: String? = null, val params: Map<String, Any?> = emptyMap())

private fun fail(reason: String, params: Map<String, Any?> = emptyMap()) = InputResult(false, reason = reason, params = params)

private val EMAIL = Regex("^[^$JS_WS_CLASS@]+@[^$JS_WS_CLASS@]+\\.[^$JS_WS_CLASS@]{2,}\\z")
private val PHONE = Regex("^\\+?[0-9]{7,15}\\z")
private val NUMBER = Regex("^-?\\d+(\\.\\d+)?\\z")
private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}\\z")
private val URL_RE = Regex("^https?://[^$JS_WS_CLASS/$.?#][^$JS_WS_CLASS]*\\z", RegexOption.IGNORE_CASE)
private val SINGLE_LINE = setOf("text", "email", "phone", "number", "date", "url")
private val REGEX_TYPES = setOf("text", "phone", "number", "url")

private fun cpLen(s: String) = s.codePointCount(0, s.length)

private fun isRealDate(s: String): Boolean {
    if (!DATE.matches(s)) return false
    val y = s.substring(0, 4).toInt()
    val m = s.substring(5, 7).toInt()
    val d = s.substring(8, 10).toInt()
    if (m !in 1..12 || d < 1) return false
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[m - 1]
    return d <= days
}

/** Port of validateInput(node, raw). [d] is the node data. */
internal fun validateInput(d: JMap, raw: String?): InputResult {
    val type = (d["inputType"] as? String)?.takeIf { it.isNotEmpty() } ?: "text"
    val required = d["required"] != false
    var value = jsTrim(raw.orEmpty())
    if (value == "") return if (required) fail("required") else InputResult(true, "")

    val pattern = d["regexPattern"] as? String
    val regex = if (type in REGEX_TYPES && !pattern.isNullOrEmpty()) compileSafe(pattern) else null
    if (regex != null) value = toAscii(value)

    when (type) {
        "text", "longtext" -> {
            val defaultMin = if (required && type == "text") 1 else 0
            val defaultMax = if (type == "text") 500 else 2000
            val min = if (isFiniteNumber(d["minLength"])) (d["minLength"] as Number).toDouble() else defaultMin.toDouble()
            val max = if (isFiniteNumber(d["maxLength"])) (d["maxLength"] as Number).toDouble() else defaultMax.toDouble()
            if (cpLen(value) < min) return fail("minlength", mapOf<String, Any?>("min" to (d["minLength"].takeIf { isFiniteNumber(it) } ?: defaultMin)))
            if (cpLen(value) > max) return fail("maxlength", mapOf<String, Any?>("max" to (d["maxLength"].takeIf { isFiniteNumber(it) } ?: defaultMax)))
        }
        "email" -> {
            value = value.lowercase()
            if (!EMAIL.matches(value) || value.length > 254) return fail("format")
        }
        "phone" -> {
            value = Regex("[$JS_WS_CLASS\\-()]").replace(toAscii(value), "")
            if (!PHONE.matches(value)) return fail("format")
        }
        "number" -> {
            value = toAscii(value)
            if (!NUMBER.matches(value)) return fail("format")
            val n = value.toDouble()
            if (isFiniteNumber(d["min"]) && n < (d["min"] as Number).toDouble()) return fail("min", mapOf("min" to d["min"]))
            if (isFiniteNumber(d["max"]) && n > (d["max"] as Number).toDouble()) return fail("max", mapOf("max" to d["max"]))
        }
        "date" -> {
            if (!isRealDate(value)) return fail("format")
            val mn = d["min"]
            val mx = d["max"]
            if (mn is String && mn.isNotEmpty() && value < mn) return fail("min", mapOf("min" to mn))
            if (mx is String && mx.isNotEmpty() && value > mx) return fail("max", mapOf("max" to mx))
        }
        "url" -> if (!URL_RE.matches(value) || cpLen(value) > 500) return fail("format")
    }

    if (regex != null) {
        val cue = (d["regexCue"] as? String) ?: ""
        if (type in SINGLE_LINE && Regex("[\\r\\n]").containsMatchIn(value)) return fail("regex", mapOf("cue" to cue))
        if (!regex.matcher(value).find()) return fail("regex", mapOf("cue" to cue))
    }
    return InputResult(true, value)
}
