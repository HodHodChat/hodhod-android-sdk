package chat.hodhod.sdk.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.Announcement
import chat.hodhod.sdk.AnnouncementBlock
import chat.hodhod.sdk.AnnouncementKind
import chat.hodhod.sdk.TextSegment
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.theme.HodhodColors
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.theme.VazirmatnFamily
import chat.hodhod.sdk.ui.util.fixUrl
import chat.hodhod.sdk.ui.util.isSafeUrl
import coil.compose.SubcomposeAsyncImage

private val ImageMaxHeight = 160.dp

/** Test seam: replaces the real link opener (default = `ACTION_VIEW` through [openAnnouncementLink]). */
internal val LocalAnnouncementLinkOpener = compositionLocalOf<((String) -> Unit)?> { null }

/** Banner colours: soft background + border, the strong colour of the icon / links. Body text always uses the regular text colour. */
internal class AnnouncementPalette(val background: Color, val border: Color, val strong: Color)

internal fun announcementPalette(c: HodhodColors, kind: AnnouncementKind): AnnouncementPalette = when (kind) {
    AnnouncementKind.NOTICE -> AnnouncementPalette(c.amberSoft, c.amber.copy(alpha = 0.5f), c.amberText)
    AnnouncementKind.ALERT -> AnnouncementPalette(c.rubySoft, c.rubyBorder, c.rubyText)
}

/**
 * Opens [href] with `ACTION_VIEW` (system browser / dialer / mail app), never in a WebView. Only http, https, mailto and tel
 * links are handled; anything else (or no app able to handle it) is ignored. Returns whether an activity was started.
 */
internal fun openAnnouncementLink(context: Context, href: String): Boolean {
    if (!isSafeUrl(href) || href.any { it.code <= 0x20 }) return false
    val uri = Uri.parse(href)
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) intent.addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

/** Builds the text of one paragraph: bold spans and clickable link spans (allow-listed schemes only, no autolinking of plain text). */
internal fun announcementText(segments: List<TextSegment>, linkColor: Color, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    segments.forEach { s ->
        val href = s.href?.takeIf { isSafeUrl(it) }
        val body: AnnotatedString.Builder.() -> Unit = {
            if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) } else append(s.text)
        }
        if (href != null) {
            // Phone numbers keep their left-to-right order inside RTL text (the bidi algorithm would otherwise reverse «021-123»): isolate them.
            val isolate = href.startsWith("tel:", ignoreCase = true)
            if (isolate) append('\u2066')
            withLink(LinkAnnotation.Url(href, linkStyle, LinkInteractionListener { onLink(href) })) { body() }
            if (isolate) append('\u2069')
        } else body()
    }
}

/** Announcements at the start of the widget, in server order. Emits nothing for an empty list. */
@Composable
internal fun AnnouncementBanners(items: List<Announcement>, onDismiss: (String) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // the version is part of the key: an edited announcement is a fresh banner (image failures reset)
        items.forEach { a -> key(a.id, a.updatedAt) { AnnouncementBanner(a, onDismiss = { onDismiss(a.id) }) } }
    }
}

@Composable
internal fun AnnouncementBanner(a: Announcement, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val opener = LocalAnnouncementLinkOpener.current ?: remember(ctx) { { href: String -> openAnnouncementLink(ctx, href); Unit } }
    val p = announcementPalette(c, a.kind)
    val shape = RoundedCornerShape(18.dp)
    var failedImages by remember { mutableStateOf(setOf<Int>()) }
    val visible = a.blocks.withIndex().filter { it.index !in failedImages }
    if (visible.isEmpty()) return // image-only announcement whose images could not be loaded
    val kindLabel = stringResource(if (a.kind == AnnouncementKind.ALERT) R.string.hodhod_ui_announcement_alert else R.string.hodhod_ui_announcement_notice)
    Row(
        modifier.fillMaxWidth().testTag("hodhod-announcement-${a.id}").clip(shape).background(p.background).border(1.dp, p.border, shape)
            .padding(start = 14.dp, end = if (a.dismissible) 4.dp else 14.dp)
            .semantics { isTraversalGroup = true },
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top,
    ) {
        // The icon is the group's heading for TalkBack («هشدار» / «اطلاعیه»), then the blocks are read in order. No live region: it is static content.
        Icon(
            if (a.kind == AnnouncementKind.ALERT) Icons.Rounded.WarningAmber else Icons.Rounded.Info, kindLabel,
            Modifier.padding(top = 12.dp).size(22.dp).semantics { heading() }, tint = p.strong,
        )
        Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            visible.forEach { (index, block) ->
                when (block) {
                    is AnnouncementBlock.Text -> AnnouncementParagraph(block, p.strong, opener)
                    is AnnouncementBlock.Image -> AnnouncementImage(block, opener, onError = { failedImages = failedImages + index })
                }
            }
        }
        if (a.dismissible) {
            SquareIconButton(Icons.Rounded.Close, stringResource(R.string.hodhod_ui_announcement_dismiss), onDismiss, Modifier.testTag("hodhod-announcement-dismiss-${a.id}"), size = 48.dp, tint = p.strong, bordered = false)
        }
    }
}

@Composable
private fun AnnouncementParagraph(block: AnnouncementBlock.Text, linkColor: Color, onLink: (String) -> Unit) {
    val text = remember(block, linkColor, onLink) { announcementText(block.segments, linkColor, onLink) }
    Text(
        text, color = HodhodTheme.colors.text, fontSize = 14.sp, lineHeight = 22.sp,
        // Vazirmatn explicitly (a custom `style` replaces the theme's LocalTextStyle): regular, and the real Bold file for bold spans, for every script.
        fontFamily = VazirmatnFamily, style = TextStyle(textDirection = TextDirection.Content),
    )
}

@Composable
private fun AnnouncementImage(block: AnnouncementBlock.Image, onLink: (String) -> Unit, onError: () -> Unit) {
    val c = HodhodTheme.colors
    val href = block.href?.takeIf { isSafeUrl(it) }
    val description = block.alt ?: href?.let { Uri.parse(it).host ?: it }
    val shape = RoundedCornerShape(12.dp)
    // Fixed max height + Fit: the whole picture is visible, never cropped, never taller than the cap (also at 1.3x font scale).
    var modifier = Modifier.fillMaxWidth().heightIn(max = ImageMaxHeight).clip(shape)
    if (href != null) modifier = modifier.defaultMinSize(minHeight = 48.dp).clickable(role = Role.Button) { onLink(href) }
    SubcomposeAsyncImage(
        model = fixUrl(block.url), contentDescription = description, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
        modifier = modifier, onError = { onError() },
        loading = { Box(Modifier.fillMaxWidth().height(64.dp).background(c.surfaceMuted)) },
    )
}
