package chat.hodhod.sdk.ui.flow

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

/**
 * Renders the engine's markdown-lite token tree (already template-rendered and link-sanitised by the core engine; never HTML) to an
 * [AnnotatedString] (link taps call [onLinkClick], which must open the URL): paragraphs, `- ` / `1.` lists, bold, italic, code, links (https/http/mailto/tel only) and line breaks.
 */
@Suppress("UNCHECKED_CAST")
internal fun flowRichText(blocks: List<Map<String, Any?>>, linkColor: Color, onLinkClick: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    fun inline(tokens: List<Map<String, Any?>>) {
        for (t in tokens) when (t["type"]) {
            "text" -> append(t["value"] as? String ?: "")
            "br" -> append('\n')
            "code" -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(t["value"] as? String ?: "") }
            "strong" -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { inline(t["children"] as? List<Map<String, Any?>> ?: emptyList()) }
            "em" -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inline(t["children"] as? List<Map<String, Any?>> ?: emptyList()) }
            "link" -> {
                val href = t["href"] as? String ?: ""
                withLink(LinkAnnotation.Url(href, linkStyle) { onLinkClick(href) }) {
                    inline(t["children"] as? List<Map<String, Any?>> ?: emptyList())
                }
            }
        }
    }
    blocks.forEachIndexed { i, b ->
        if (i > 0) append('\n')
        when (b["type"]) {
            "p" -> inline(b["children"] as? List<Map<String, Any?>> ?: emptyList())
            "ul", "ol" -> (b["items"] as? List<List<Map<String, Any?>>> ?: emptyList()).forEachIndexed { n, item ->
                if (n > 0) append('\n')
                append(if (b["type"] == "ul") "•  " else "${n + 1}. ")
                inline(item)
            }
        }
    }
}
