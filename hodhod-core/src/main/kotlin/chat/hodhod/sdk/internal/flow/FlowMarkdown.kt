package chat.hodhod.sdk.internal.flow

import java.util.regex.Pattern

// Port of shared/helpers/chatbotFlow/markdownLite.js + rich.js. Output is a token tree (never HTML):
//   block  = {type:"p", children:[token]} | {type:"ul"|"ol", items:[[token]]}
//   token  = {type:"text",value} | {type:"br"} | {type:"code",value} | {type:"strong"|"em",children} | {type:"link",href,children,newTab}

private const val OPEN = '\uE000'
private const val CLOSE = '\uE001'
private const val MARKDOWN_CHARS = 4000
private const val MARKDOWN_DEPTH = 3

private val MARK = Pattern.compile("\uE000(\\d+)\uE001")
private val WORD = Pattern.compile("[\\p{L}\\p{N}]")
private val HREF_OK = Pattern.compile("^(https?:|mailto:|tel:|\uE000)", Pattern.CASE_INSENSITIVE)
private val TRAILING_PUNCT = Pattern.compile("[.,;:!?)،؛]+\\z")
private val LINK = Pattern.compile("^\\[([^\\]\\n]*)\\]\\(([^)$JS_WS_CLASS]+)\\)")
private val BARE_PROTO = Pattern.compile("^https?://", Pattern.CASE_INSENSITIVE)
private val BARE_URL = Pattern.compile("^[^$JS_WS_CLASS<>]+")
private val UL = Pattern.compile("^[$JS_WS_CLASS]*-[$JS_WS_CLASS]+([^\\n\\r\\u2028\\u2029]*)\\z")
private val OL = Pattern.compile("^[$JS_WS_CLASS]*\\d+\\.[$JS_WS_CLASS]+([^\\n\\r\\u2028\\u2029]*)\\z")

private fun at(s: String, i: Int): Char? = if (i in s.indices) s[i] else null
private fun isWord(c: Char?): Boolean = c != null && WORD.matcher(c.toString()).find()

private fun tok(vararg p: Pair<String, Any?>): MutableMap<String, Any?> = linkedMapOf(*p)

private fun parseInline(s: String, depth: Int): List<MutableMap<String, Any?>> {
    val out = ArrayList<MutableMap<String, Any?>>()
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) out += tok("type" to "text", "value" to buf.toString())
        buf.setLength(0)
    }
    val nestable = depth < MARKDOWN_DEPTH
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\n') {
            flush()
            out += tok("type" to "br")
            i += 1
        } else if (c == '`' && s.indexOf('`', i + 1) > i + 1) {
            val end = s.indexOf('`', i + 1)
            flush()
            out += tok("type" to "code", "value" to s.substring(i + 1, end))
            i = end + 1
        } else if (c == '*' && at(s, i + 1) == '*' && nestable && s.indexOf("**", i + 2) > i + 2) {
            val end = s.indexOf("**", i + 2)
            flush()
            out += tok("type" to "strong", "children" to parseInline(s.substring(i + 2, end), depth + 1))
            i = end + 2
        } else if ((c == '*' || c == '_') && nestable && at(s, i + 1) != c && !jsIsSpace(at(s, i + 1)) && !(c == '_' && isWord(at(s, i - 1)))) {
            var end = -1
            var j = i + 2
            while (j < s.length) {
                if (s[j] == c && !jsIsSpace(at(s, j - 1)) && at(s, j + 1) != c && at(s, j - 1) != c && !(c == '_' && isWord(at(s, j + 1)))) {
                    end = j
                    break
                }
                j += 1
            }
            if (end > 0) {
                flush()
                out += tok("type" to "em", "children" to parseInline(s.substring(i + 1, end), depth + 1))
                i = end + 1
            } else {
                buf.append(c)
                i += 1
            }
        } else if (c == '[') {
            val m = LINK.matcher(s.substring(i))
            if (m.find() && HREF_OK.matcher(m.group(2)!!).find()) {
                flush()
                out += tok(
                    "type" to "link", "href" to m.group(2),
                    "children" to if (nestable) parseInline(m.group(1)!!, depth + 1) else listOf(tok("type" to "text", "value" to m.group(1))),
                )
                i += m.group(0)!!.length
            } else {
                buf.append(c)
                i += 1
            }
        } else if ((c == 'h' || c == 'H') && !isWord(at(s, i - 1)) && BARE_PROTO.matcher(s.substring(i, minOf(s.length, i + 8))).find()) {
            val m = BARE_URL.matcher(s.substring(i))
            m.find()
            val url = TRAILING_PUNCT.matcher(m.group(0)!!).replaceFirst("")
            flush()
            out += tok("type" to "link", "href" to url, "children" to listOf(tok("type" to "text", "value" to url)))
            i += url.length
        } else {
            buf.append(c)
            i += 1
        }
    }
    flush()
    return out
}

private fun parseBlocks(text: String): List<MutableMap<String, Any?>> {
    val blocks = ArrayList<MutableMap<String, Any?>>()
    val para = ArrayList<String>()
    var list: MutableMap<String, Any?>? = null
    fun flushPara() {
        if (para.isNotEmpty()) blocks += tok("type" to "p", "children" to parseInline(para.joinToString("\n"), 0))
        para.clear()
    }
    fun flushList() {
        list?.let { blocks += it }
        list = null
    }
    for (line in text.split('\n')) {
        val ul = UL.matcher(line).also { it.find() }
        val ol = OL.matcher(line)
        val isUl = UL.matcher(line).find()
        val isOl = !isUl && ol.find()
        if (isUl || isOl) {
            flushPara()
            val type = if (isUl) "ul" else "ol"
            if (list != null && list!!["type"] != type) flushList()
            if (list == null) list = tok("type" to type, "items" to ArrayList<List<MutableMap<String, Any?>>>())
            val body = (if (isUl) UL.matcher(line).also { it.find() } else ol).group(1)!!
            @Suppress("UNCHECKED_CAST")
            (list!!["items"] as ArrayList<List<MutableMap<String, Any?>>>).add(parseInline(body, 0))
        } else if (jsTrim(line).isEmpty()) {
            flushPara()
            flushList()
        } else {
            flushList()
            para += line
        }
    }
    flushPara()
    flushList()
    return blocks
}

@Suppress("UNCHECKED_CAST")
private fun unmaskTokens(tokens: List<Map<String, Any?>>, unmask: (String, String) -> String): List<Map<String, Any?>> =
    tokens.flatMap { t ->
        when (t["type"]) {
            "text" -> unmask(t["value"] as String, "text").let { v -> if (v.isNotEmpty()) listOf(t + ("value" to v)) else emptyList() }
            "code" -> listOf(t + ("value" to unmask(t["value"] as String, "text")))
            "link" -> listOf(t + mapOf("href" to unmask(t["href"] as String, "url"), "children" to unmaskTokens(t["children"] as List<Map<String, Any?>>, unmask)))
            else -> if (t["children"] != null) listOf(t + ("children" to unmaskTokens(t["children"] as List<Map<String, Any?>>, unmask))) else listOf(t)
        }
    }

@Suppress("UNCHECKED_CAST")
private fun mapBlocks(blocks: List<Map<String, Any?>>, unmask: (String, String) -> String): List<Map<String, Any?>> = blocks.map { b ->
    if (b["type"] == "p") b + ("children" to unmaskTokens(b["children"] as List<Map<String, Any?>>, unmask))
    else b + ("items" to (b["items"] as List<List<Map<String, Any?>>>).map { unmaskTokens(it, unmask) })
}

internal fun parseMarkdownLite(input: String, resolve: (TplToken.Var, String) -> String): List<Map<String, Any?>> {
    val full = input.replace(OPEN.toString(), "").replace(CLOSE.toString(), "")
    val head = full.take(MARKDOWN_CHARS)
    val rest = full.drop(MARKDOWN_CHARS)
    val vars = ArrayList<TplToken.Var>()
    val masked = parseTemplate(head).tokens.joinToString("") { t ->
        when (t) {
            is TplToken.Text -> t.value
            is TplToken.Var -> {
                vars += t
                "$OPEN${vars.size - 1}$CLOSE"
            }
        }
    }
    val unmask = { s: String, where: String ->
        val m = MARK.matcher(s)
        val sb = StringBuffer()
        while (m.find()) {
            val token = vars.getOrNull(m.group(1)!!.toInt())
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(if (token == null) "" else resolve(token, where)))
        }
        m.appendTail(sb)
        sb.toString()
    }
    val blocks = ArrayList(mapBlocks(parseBlocks(masked), unmask))
    if (rest.isNotEmpty()) blocks += tok("type" to "p", "children" to listOf(tok("type" to "text", "value" to rest)))
    return blocks
}

internal val DEFAULT_LINK_PROTOCOLS = listOf("https:", "http:", "mailto:", "tel:")

@Suppress("UNCHECKED_CAST")
private fun sanitizeLinks(tokens: List<Map<String, Any?>>, protocols: List<String>): List<Map<String, Any?>> = tokens.flatMap { t ->
    if (t["type"] == "link") {
        val children = sanitizeLinks(t["children"] as List<Map<String, Any?>>, protocols)
        if (!isAllowedUrl(t["href"] as String, protocols)) children
        else listOf(t + mapOf("children" to children, "newTab" to Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(t["href"] as String)))
    } else if (t["children"] != null) {
        listOf(t + ("children" to sanitizeLinks(t["children"] as List<Map<String, Any?>>, protocols)))
    } else listOf(t)
}

@Suppress("UNCHECKED_CAST")
internal fun renderRich(template: String, vars: Map<String, Any?>, protocols: List<String> = DEFAULT_LINK_PROTOCOLS): List<Map<String, Any?>> {
    val resolve = { token: TplToken.Var, where: String ->
        val value = resolveValue(token, vars)
        if (where == "url") encodeURIComponent(value) else value
    }
    return parseMarkdownLite(template, resolve).map { b ->
        if (b["type"] == "p") b + ("children" to sanitizeLinks(b["children"] as List<Map<String, Any?>>, protocols))
        else b + ("items" to (b["items"] as List<List<Map<String, Any?>>>).map { sanitizeLinks(it, protocols) })
    }
}

/** Plain text of a rich tree (for the TalkBack announcement). */
@Suppress("UNCHECKED_CAST")
internal fun richPlainText(blocks: List<Map<String, Any?>>): String {
    fun inline(list: List<Map<String, Any?>>?): String = (list ?: emptyList()).joinToString("") { t ->
        when {
            t["type"] == "br" -> " "
            t["children"] != null -> inline(t["children"] as List<Map<String, Any?>>)
            else -> (t["value"] as? String).orEmpty()
        }
    }
    val joined = blocks.joinToString(" ") { b ->
        if (b["type"] == "p") inline(b["children"] as List<Map<String, Any?>>)
        else ((b["items"] as? List<List<Map<String, Any?>>>) ?: emptyList()).joinToString(" ") { inline(it) }
    }
    return jsTrim(Regex("[$JS_WS_CLASS]+").replace(joined, " "))
}
