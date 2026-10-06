package chat.hodhod.sdk.ui.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel")

internal fun isSafeUrl(url: String): Boolean {
    val scheme = url.substringBefore(':', "").lowercase()
    return scheme in SAFE_SCHEMES
}

private val TOKEN = Regex("""\[([^\]]+)]\(([^)\s]+)\)|\*\*(.+?)\*\*|(?<![\w*])\*([^*\n]+)\*(?![\w*])|`([^`\n]+)`|(https?://[^\s<>()]+[^\s<>().,;:!?'"])""")

/** Minimal markdown subset used by the widget for message bodies: bold, italic, code, links, bare URLs. */
internal fun richText(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    var last = 0
    for (m in TOKEN.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[2].isNotEmpty() -> if (isSafeUrl(g[2])) withLink(LinkAnnotation.Url(g[2], link)) { append(g[1]) } else append(g[1])
            g[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[3]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(g[5]) }
            g[6].isNotEmpty() -> withLink(LinkAnnotation.Url(g[6], link)) { append(g[6]) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

/**
 * Servers behind a dev proxy hand out absolute URLs with their own `localhost` origin; map those onto the configured base URL
 * (e.g. `http://10.0.2.2:3000` from the emulator). Has no effect for real hosts.
 */
internal fun fixUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    val base = chat.hodhod.sdk.Hodhod.config?.baseUrl ?: return url
    return runCatching {
        val u = android.net.Uri.parse(url); val b = android.net.Uri.parse(base)
        val local = setOf("localhost", "127.0.0.1", "0.0.0.0")
        if (u.host in local && b.host != u.host && b.host != null) u.buildUpon().scheme(b.scheme).encodedAuthority(b.encodedAuthority).build().toString() else url
    }.getOrDefault(url)
}
