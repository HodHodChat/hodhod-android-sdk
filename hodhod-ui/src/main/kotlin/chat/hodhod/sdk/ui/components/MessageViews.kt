package chat.hodhod.sdk.ui.components

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import chat.hodhod.sdk.Message
import chat.hodhod.sdk.MessageAttachment
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.theme.HodhodRadius
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.fixUrl
import chat.hodhod.sdk.ui.util.isSafeUrl
import chat.hodhod.sdk.ui.util.richText
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage

internal enum class Bubble { AGENT, USER, BOT, FAILED }

/** Corner radii: 20dp bubble, 6dp "tail" corner at the bottom-start/bottom-end... follows widget `.chat-bubble` + consecutive-message rule. */
internal fun bubbleShape(isUser: Boolean, groupStart: Boolean): RoundedCornerShape {
    val big = HodhodRadius.bubble
    val small = HodhodRadius.bubbleTail
    // Agent bubbles sit at the start edge, visitor ones at the end edge. Consecutive bubbles square the corner touching the previous one.
    return if (isUser) RoundedCornerShape(topStart = big, topEnd = if (groupStart) big else small, bottomEnd = big, bottomStart = big)
    else RoundedCornerShape(topStart = if (groupStart) big else small, topEnd = big, bottomEnd = big, bottomStart = big)
}

@Composable
internal fun BubbleText(text: String, kind: Bubble, groupStart: Boolean, modifier: Modifier = Modifier) {
    val c = HodhodTheme.colors
    val isUser = kind == Bubble.USER || kind == Bubble.FAILED
    val textColor = when (kind) { Bubble.USER -> c.onAccent; Bubble.FAILED -> c.rubyText; else -> c.text }
    val linkColor = if (kind == Bubble.USER) c.onAccent else c.accentText
    val styled = androidx.compose.runtime.remember(text, linkColor) { richText(text, linkColor) }
    val body: @Composable () -> Unit = {
        Text(styled, color = textColor, fontSize = 15.sp, lineHeight = 24.sp, style = TextStyle(textDirection = TextDirection.Content))
    }
    when (kind) {
        Bubble.BOT -> Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 9.dp).size(7.dp).drawBehind { drawCircle(c.accent.copy(alpha = 0.35f), radius = size.minDimension) }.clip(CircleShape).background(c.accent.copy(alpha = 0.85f)))
            body()
        }
        else -> Box(
            modifier.clip(bubbleShape(isUser, groupStart)).then(
                when (kind) {
                    Bubble.USER -> Modifier.background(c.accent)
                    Bubble.FAILED -> Modifier.background(c.rubySoft).border(1.dp, c.rubyBorder, bubbleShape(true, groupStart))
                    else -> Modifier.background(c.agentBubble).border(1.dp, c.agentBubbleBorder, bubbleShape(false, groupStart))
                },
            ).padding(horizontal = 16.dp, vertical = 10.dp),
        ) { body() }
    }
}

private fun open(ctx: android.content.Context, url: String) {
    if (!isSafeUrl(url)) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

internal fun fileNameOf(a: MessageAttachment): String =
    a.localFile?.name?.substringAfter('_', a.localFile?.name ?: "") ?: a.dataUrl?.substringBefore('?')?.substringAfterLast('/')?.let { runCatching { Uri.decode(it) }.getOrDefault(it) } ?: "file"

internal fun humanSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val kb = bytes / 1024.0
    return if (kb < 1024) "%.0f KB".format(kb) else "%.1f MB".format(kb / 1024)
}

/** All attachment kinds of one message in a single rounded container (images preview, files/audio/video chips). */
@Composable
internal fun Attachments(m: Message, isUser: Boolean, groupStart: Boolean) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    var preview by remember { mutableStateOf<Any?>(null) }
    Column(Modifier.widthIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        m.attachments.forEach { a ->
            val model: Any? = a.localFile ?: fixUrl(a.thumbUrl) ?: fixUrl(a.dataUrl)
            when (a.fileType) {
                "image" -> SubcomposeAsyncImage(
                    model = model, contentDescription = stringResource(R.string.hodhod_ux_widget_image_sent), contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().then(if ((a.width ?: 0) > 0 && (a.height ?: 0) > 0) Modifier.aspectRatio((a.width!!.toFloat() / a.height!!).coerceIn(0.6f, 2f)) else Modifier.heightIn(min = 90.dp, max = 260.dp)).clip(RoundedCornerShape(16.dp)).background(c.surfaceMuted)
                        .clickable(role = Role.Button) { preview = a.localFile ?: fixUrl(a.dataUrl) },
                    error = { Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.BrokenImage, null, tint = c.textTertiary) } },
                    loading = { Box(Modifier.fillMaxWidth().height(120.dp)) },
                )
                "video" -> Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(c.surfaceMuted).clickable(role = Role.Button) { fixUrl(a.dataUrl)?.let { open(ctx, it) } },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PlayCircle, stringResource(R.string.hodhod_attachments_video_content), tint = c.accentText, modifier = Modifier.size(48.dp))
                }
                "audio" -> AudioChip(a, isUser)
                else -> FileChip(a, isUser) { fixUrl(a.dataUrl)?.let { open(ctx, it) } }
            }
        }
    }
    preview?.let { p ->
        Dialog({ preview = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)).clickable { preview = null }, contentAlignment = Alignment.Center) {
                AsyncImage(p, stringResource(R.string.hodhod_ux_widget_image_sent), Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                Icon(Icons.Rounded.Close, stringResource(R.string.hodhod_ux_widget_close_window), tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).size(32.dp))
            }
        }
    }
}

@Composable
private fun FileChip(a: MessageAttachment, isUser: Boolean, onClick: () -> Unit) {
    val c = HodhodTheme.colors
    val fg = if (isUser) c.onAccent else c.text
    val bg = if (isUser) c.accent else c.agentBubble
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(bg).then(if (isUser) Modifier else Modifier.border(1.dp, c.agentBubbleBorder, RoundedCornerShape(16.dp)))
            .clickable(role = Role.Button, onClick = onClick).heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = fg, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(fileNameOf(a), color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = TextStyle(textDirection = TextDirection.Content))
            humanSize(a.fileSize)?.let { Text(it, color = fg.copy(alpha = 0.75f), fontSize = 11.sp) }
        }
        Icon(Icons.Rounded.Download, null, tint = fg, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun AudioChip(a: MessageAttachment, isUser: Boolean) {
    val c = HodhodTheme.colors
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player?.release() } }
    val fg = if (isUser) c.onAccent else c.text
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (isUser) c.accent else c.agentBubble).heightIn(min = 52.dp)
            .clickable(role = Role.Button) {
                val url = a.localFile?.absolutePath ?: fixUrl(a.dataUrl) ?: return@clickable
                if (player == null) {
                    player = MediaPlayer().apply {
                        setDataSource(url); setOnCompletionListener { playing = false }
                        setOnPreparedListener { it.start(); playing = true }; prepareAsync()
                    }
                } else if (playing) { player?.pause(); playing = false } else { player?.start(); playing = true }
            }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(if (playing) Icons.Rounded.PauseCircle else Icons.Rounded.PlayCircle, stringResource(R.string.hodhod_attachments_audio_content), tint = fg, modifier = Modifier.size(32.dp))
        Text(stringResource(R.string.hodhod_attachments_audio_content), color = fg, fontSize = 14.sp)
    }
}
