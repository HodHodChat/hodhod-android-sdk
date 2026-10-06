package chat.hodhod.sdk.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.Agent
import chat.hodhod.sdk.Message
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.isSafeUrl
import chat.hodhod.sdk.ui.util.richText
import coil.compose.AsyncImage

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> = (this[key] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
internal fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

private fun openUrl(ctx: android.content.Context, url: String) {
    if (!isSafeUrl(url)) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** `input_select`: title + numbered choices; once chosen the submitted value is shown by the caller instead. */
@Composable
internal fun OptionsMessage(title: String?, items: List<Map<String, Any?>>, submitted: Boolean, onPick: (String) -> Unit) {
    val c = HodhodTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!title.isNullOrBlank()) BubbleText(title, Bubble.AGENT, true)
        if (!submitted) items.forEachIndexed { i, it ->
            val label = it.str("title") ?: it.str("value") ?: return@forEachIndexed
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.surfaceField).border(1.dp, c.borderStrong, RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { onPick(label) }.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("${i + 1}", color = c.accentText, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp)
                Text(label, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** `cards`: media + title + description + action buttons (link opens, postback replies with its text). */
@Composable
internal fun CardsMessage(items: List<Map<String, Any?>>, onPostback: (String) -> Unit) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(18.dp))) {
                item.str("media_url")?.let { AsyncImage(it, stringResource(R.string.hodhod_ux_widget2_card_image), Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = ContentScale.Crop) }
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.str("title")?.let { Text(it, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
                    item.str("description")?.let { Text(it, color = c.textSecondary, fontSize = 13.sp, lineHeight = 21.sp) }
                    item.list("actions").forEach { act ->
                        val text = act.str("text") ?: return@forEach
                        Box(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).background(c.violetSoft).clickable(role = Role.Button) {
                                if (act.str("type") == "link") act.str("uri")?.let { openUrl(ctx, it) } else onPostback(act.str("payload") ?: text)
                            }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center,
                        ) { Text(text, color = c.violetText, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
                    }
                }
            }
        }
    }
}

/** `article`: list of link cards. */
@Composable
internal fun ArticleMessage(items: List<Map<String, Any?>>) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    Column(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(c.agentBubble).border(1.dp, c.agentBubbleBorder, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp)) {
        items.forEach { item ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { item.str("link")?.let { openUrl(ctx, it) } }.padding(vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Link, null, tint = c.text, modifier = Modifier.size(16.dp))
                    Text(item.str("title") ?: "", color = c.text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
                item.str("description")?.let { Text(it, color = c.textSecondary, fontSize = 13.sp, lineHeight = 20.sp, maxLines = 2) }
            }
        }
    }
}

/** CSAT survey card (emoji/star/number scale + optional comment) -- shared by in-thread csat messages and the end screen. */
@Composable
internal fun CsatCard(
    title: String?, type: CsatType, submittedRating: Int?, submittedFeedback: String?, agent: Agent?,
    onRate: (Int, String?) -> Unit, modifier: Modifier = Modifier,
) {
    val c = HodhodTheme.colors
    var rating by remember(submittedRating) { mutableStateOf(submittedRating) }
    var showFeedback by remember { mutableStateOf(false) }
    var feedback by remember(submittedFeedback) { mutableStateOf(submittedFeedback ?: "") }
    var feedbackSent by remember(submittedFeedback) { mutableStateOf(!submittedFeedback.isNullOrBlank()) }
    val ctx = LocalContext.current
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(c.surface).border(1.dp, c.border, RoundedCornerShape(26.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val ratedTitle = stringResource(R.string.hodhod_csat_submitted_title)
        if (agent != null && rating == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HodhodAvatar(agent.name, agent.avatarUrl, 30.dp)
                Text(stringResource(R.string.hodhod_csat_rate_agent, "\u2068${agent.name}\u2069"), color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp, modifier = Modifier.semantics { heading() })
            }
        } else {
            Text(if (rating != null) ratedTitle else title ?: stringResource(R.string.hodhod_csat_title), color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 13.5.sp,
                modifier = Modifier.semantics { heading() })
        }
        CsatScale(type, rating, locked = feedbackSent, onSelect = { if (!feedbackSent) { rating = it; onRate(it, null) } })
        val sel = rating
        if (sel != null) {
            val label = when (sel) { 1 -> R.string.hodhod_csat_ratings_poor; 2 -> R.string.hodhod_csat_ratings_fair; 3 -> R.string.hodhod_csat_ratings_average; 4 -> R.string.hodhod_csat_ratings_good; else -> R.string.hodhod_csat_ratings_excellent }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), color = c.accentText, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (!feedbackSent && !showFeedback) GhostButton(stringResource(R.string.hodhod_csat_add_comment), { showFeedback = true }, color = c.accentText)
            }
            if (showFeedback && !feedbackSent) {
                val fr = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { fr.requestFocus() }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HodhodField(feedback, { feedback = it }, stringResource(R.string.hodhod_csat_add_comment), Modifier.weight(1f), placeholder = stringResource(R.string.hodhod_csat_placeholder), singleLine = false, maxLines = 4, maxLength = 1000, focusRequester = fr)
                    Box(
                        Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(c.accent).clickable(enabled = feedback.isNotBlank(), role = Role.Button) {
                            feedbackSent = true; onRate(sel, feedback.trim())
                        }.semantics { contentDescription = ctx.getString(R.string.hodhod_csat_submit) }, contentAlignment = Alignment.Center,
                    ) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.onAccent) }
                }
            }
        }
    }
}
