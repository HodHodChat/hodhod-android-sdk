package chat.hodhod.sdk.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import chat.hodhod.sdk.*
import chat.hodhod.sdk.ui.*
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.components.*
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.theme.VazirmatnFamily
import chat.hodhod.sdk.ui.util.Dates
import chat.hodhod.sdk.ui.util.widgetString
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

private sealed interface Item {
    data class Day(val label: String, val epoch: Long) : Item
    data class Msg(val m: Message, val groupStart: Boolean, val groupEnd: Boolean) : Item
    data object Csat : Item
}

private fun senderKey(m: Message): String = when {
    m.isFromContact -> "me"
    m.messageType == MessageType.ACTIVITY -> "activity-${m.id}"
    m.sender != null -> "s${m.sender?.id ?: m.sender?.name}"
    else -> "bot"
}

private fun isCsat(m: Message) = m.contentType == "input_csat" || m.contentType == "csat"

@Suppress("UNCHECKED_CAST")
private fun csatResponse(m: Message): Map<String, Any?>? = (m.contentAttributes["submitted_values"] as? Map<String, Any?>)?.get("csat_survey_response") as? Map<String, Any?>

@Composable
private fun buildItems(messages: List<Message>, showSyntheticCsat: Boolean, locale: String?): List<Item> {
    val today = stringResource(R.string.hodhod_today)
    val yesterday = stringResource(R.string.hodhod_yesterday)
    return remember(messages, showSyntheticCsat, locale, today, yesterday) {
        val visible = messages.filter { it.contentAttributes["deleted"] != true && it.contentType != "input_email" }
        val out = ArrayList<Item>()
        visible.forEachIndexed { i, m ->
            val prev = visible.getOrNull(i - 1); val next = visible.getOrNull(i + 1)
            if (prev == null || !Dates.sameDay(prev.createdAt, m.createdAt)) {
                val rel = Dates.relativeDay(m.createdAt)
                out += Item.Day(when (rel) { 0 -> today; -1 -> yesterday; else -> Dates.fullDay(m.createdAt, locale) }, m.createdAt)
            }
            val start = prev == null || senderKey(prev) != senderKey(m) || !Dates.sameDay(prev.createdAt, m.createdAt) || isCsat(prev)
            val end = next == null || senderKey(next) != senderKey(m) || !Dates.sameDay(next.createdAt, m.createdAt) || isCsat(next)
            out += Item.Msg(m, start, end)
        }
        if (showSyntheticCsat) out += Item.Csat
        out
    }
}

@Composable
internal fun ChatBody(
    vm: HodhodChatViewModel, config: WidgetConfig, convo: ConversationState, messages: List<Message>, locale: String?,
    modifier: Modifier = Modifier, onStartNew: () -> Unit,
) {
    val repo = vm.repo
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val active = convo as? ConversationState.Active
    val ended = convo as? ConversationState.Ended
    val agentsTyping = active?.agentTyping == true
    val hasCsatMsg = messages.any(::isCsat)
    val showSyntheticCsat = ended != null && ended.csatEnabled && !hasCsatMsg
    val items = buildItems(messages, showSyntheticCsat, locale)
    val lastAgent = remember(messages) { messages.lastOrNull { !it.isFromContact && it.sender?.type == "user" }?.sender?.let { Agent(it.id ?: 0, it.name ?: "", it.avatarUrl, null) } }
    val listState = rememberLazyListState()
    val reversed = remember(items, agentsTyping) { (if (agentsTyping) listOf<Item?>(null) else emptyList()) + items.reversed() }
    val unread = active?.unreadCount ?: 0

    // auto-scroll to newest when a new message arrives while the user is near the bottom (or sent it)
    val lastKey = messages.lastOrNull()?.key
    LaunchedEffect(lastKey, agentsTyping, showSyntheticCsat) {
        if (messages.isNotEmpty() && (listState.firstVisibleItemIndex <= 2 || messages.last().isFromContact)) listState.animateScrollToItem(0)
    }
    // mark read while visible
    LaunchedEffect(unread, messages.size) { if (unread > 0) repo.markRead() }
    // load older when the oldest loaded item is near
    LaunchedEffect(listState, active?.hasMore) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged().collect { (last, total) ->
                if (active?.hasMore == true && active.isLoadingOlder.not() && total > 0 && last >= total - 3) repo.loadOlder()
            }
    }

    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize().semantics { contentDescription = ctx.getString(R.string.hodhod_ux_widget_transcript) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(reversed.size, key = { i -> when (val it = reversed[i]) { null -> "typing"; is Item.Day -> "day-${it.epoch}"; is Item.Msg -> it.m.key; Item.Csat -> "csat" } }) { i ->
                    when (val it = reversed[i]) {
                        null -> TypingBubble(lastAgent?.name)
                        is Item.Day -> DayChip(it.label)
                        is Item.Csat -> CsatCard(null, CsatType.EMOJI, null, null, lastAgent, { r, f -> scope.launch { repo.submitCsat(r, f) } }, Modifier.padding(top = 8.dp))
                        is Item.Msg -> MessageRow(it, lastAgent, locale, vm, config, ended != null)
                    }
                }
                if (active?.isLoadingOlder == true) item(key = "loading-older") { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp) } }
            }
            if (messages.isEmpty() && ended == null) Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandTile(72.dp)
                Spacer(Modifier.height(8.dp))
                Text(config.welcomeTitle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.hodhod_lark_home_greeting), color = c.text, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
                Text(config.welcomeTagline?.takeIf { it.isNotBlank() } ?: stringResource(R.string.hodhod_lark_home_intro), color = c.textSecondary, fontSize = 14.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
            }
            val showFab by remember { derivedStateOf { listState.firstVisibleItemIndex > 3 } }
            if (showFab) SquareIconButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.hodhod_ui_scroll_bottom), { scope.launch { listState.animateScrollToItem(0) } },
                Modifier.align(Alignment.BottomEnd).padding(16.dp), tint = c.text)
        }
        NoticeBar(vm.notice.collectAsState().value)
        val hideComposer = ended != null || (config.allowMessagesAfterResolved.not() && active?.status == ConversationStatus.RESOLVED)
        if (!hideComposer) Composer(vm, config) else EndFooter(vm, config, hasCsat = hasCsatMsg || showSyntheticCsat, onStartNew)
    }
}

@Composable
private fun DayChip(label: String) {
    val c = HodhodTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(label, Modifier.clip(RoundedCornerShape(50)).background(c.surfaceMuted).padding(horizontal = 12.dp, vertical = 4.dp), color = c.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TypingBubble(name: String?) {
    val c = HodhodTheme.colors
    val label = if (name.isNullOrBlank()) stringResource(R.string.hodhod_ux_widget_typing_generic) else stringResource(R.string.hodhod_ux_widget_typing, "\u2068$name\u2069")
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) { contentDescription = label; liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(c.agentBubble).border(1.dp, c.agentBubbleBorder, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) { i ->
                val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
                Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(c.textSecondary))
            }
        }
        Text(label, color = c.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun MessageRow(it: Item.Msg, lastAgent: Agent?, locale: String?, vm: HodhodChatViewModel, config: WidgetConfig, isEnded: Boolean) {
    val m = it.m
    val c = HodhodTheme.colors
    val scope = rememberCoroutineScope()
    val repo = vm.repo
    val topPad = if (it.groupStart) 10.dp else 0.dp
    if (m.messageType == MessageType.ACTIVITY) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(c.border)); Text(m.content ?: "", color = c.textSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(3f, fill = false)); Box(Modifier.weight(1f).height(1.dp).background(c.border))
        }
        return
    }
    if (isCsat(m)) {
        val resp = csatResponse(m)
        val rating = (resp?.get("rating") as? Number)?.toInt()
        CsatCard(m.content, CsatType.from(m.contentAttributes["display_type"] as? String), rating, resp?.get("feedback_message") as? String, lastAgent,
            { r, f -> scope.launch { repo.submitCsat(r, f) } }, Modifier.padding(top = 8.dp))
        return
    }
    val isUser = m.isFromContact
    val failed = m.status == MessageStatus.FAILED
    val hasText = !m.content.isNullOrBlank()
    val isBot = !isUser && m.sender == null && m.attachments.isEmpty() && m.messageType != MessageType.ACTIVITY
    val name = m.sender?.name ?: m.contentAttributes["sender_name"] as? String
    val ctx = LocalContext.current
    val time = Dates.time(m.createdAt, locale)
    val ci = m.contentAttributes
    val submitted = ci.list("submitted_values")
    val selectKind = when (m.contentType) { "input_select", "cards", "article", "form", "input_email" -> m.contentType; else -> null }

    Column(Modifier.fillMaxWidth().padding(top = topPad), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
            if (!isUser && !isBot) {
                Box(Modifier.width(38.dp)) { if (it.groupEnd) HodhodAvatar(name ?: "", m.sender?.avatarUrl, 30.dp) }
            }
            Column(Modifier.weight(1f, fill = false).widthIn(max = 560.dp).then(if (isUser || isBot) Modifier else Modifier), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isUser) Text(((name ?: stringResource(R.string.hodhod_unread_view_bot)) + ": "), Modifier.size(0.dp).semantics { }, fontSize = 0.sp)
                when (selectKind) {
                    "input_select" -> {
                        OptionsMessage(m.content, ci.list("items"), submitted.isNotEmpty()) { label -> scope.launch { repo.sendMessage(label) } }
                        submitted.firstOrNull()?.let { s -> (s.str("title") ?: s.str("value"))?.let { BubbleText(it, Bubble.USER, true) } }
                    }
                    "cards" -> {
                        if (hasText) BubbleText(m.content!!, Bubble.AGENT, it.groupStart)
                        CardsMessage(ci.list("items")) { p -> scope.launch { repo.sendMessage(p) } }
                    }
                    "article" -> {
                        if (hasText) BubbleText(m.content!!, Bubble.AGENT, it.groupStart)
                        ArticleMessage(ci.list("items"))
                    }
                    else -> {
                        if (hasText) BubbleText(m.content!!, when { failed -> Bubble.FAILED; isUser -> Bubble.USER; isBot -> Bubble.BOT; else -> Bubble.AGENT }, it.groupStart,
                            Modifier.alpha(if (m.status == MessageStatus.SENDING) 0.6f else 1f))
                    }
                }
                if (m.attachments.isNotEmpty()) Box(Modifier.alpha(if (m.status == MessageStatus.SENDING) 0.6f else 1f)) { Attachments(m, isUser, it.groupStart) }
            }
        }
        if (failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                GhostButton(stringResource(R.string.hodhod_ux_widget_retry), { scope.launch { repo.retry(m.key) } }, color = c.rubyText)
                GhostButton(stringResource(R.string.hodhod_ui_discard), { repo.discardFailed(m.key) }, color = c.textSecondary)
            }
        } else if (it.groupEnd) {
            val meta = when {
                isUser -> if (m.status == MessageStatus.SENDING) "…" else time
                isBot -> time
                else -> "\u2068${name ?: ""}\u2069 · $time"
            }
            Text(meta, Modifier.padding(start = if (!isUser && !isBot) 38.dp else 0.dp, top = 2.dp, bottom = 2.dp), color = c.textSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Composer(vm: HodhodChatViewModel, config: WidgetConfig) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf(vm.draft) }
    var menu by remember { mutableStateOf(false) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    val canAttach = config.enabledFeatures.attachments
    fun accept(uri: Uri?) {
        if (uri == null) return
        val a = ctx.attachmentFromUri(uri) ?: return
        if (a.file.length() > MAX_UPLOAD_BYTES) { a.file.delete(); vm.showNotice(ctx.getString(R.string.hodhod_file_size_limit, "40 MB"), true); return }
        vm.sendAttachment(a)
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { accept(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { accept(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) accept(cameraUri?.let { Uri.parse(it) }); cameraUri = null }
    fun launchCamera() {
        val dir = File(ctx.cacheDir, "hodhod-camera").apply { mkdirs() }
        val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".hodhod.fileprovider", f)
        cameraUri = uri.toString(); camera.launch(uri)
    }
    val send = { val t = text; text = ""; vm.sendText(t); vm.repo.setTyping(false) }
    Row(Modifier.fillMaxWidth().background(c.background).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canAttach) Box {
            SquareIconButton(Icons.Rounded.AttachFile, stringResource(R.string.hodhod_ux_widget_attach), { menu = true }, tint = c.textSecondary)
            DropdownMenu(menu, { menu = false }, containerColor = c.surface) {
                DropdownMenuItem({ Text(stringResource(R.string.hodhod_ui_attach_photo), color = c.text) }, leadingIcon = { Icon(Icons.Rounded.Image, null, tint = c.textSecondary) }, onClick = { menu = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) })
                DropdownMenuItem({ Text(stringResource(R.string.hodhod_ui_attach_camera), color = c.text) }, leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, tint = c.textSecondary) }, onClick = { menu = false; launchCamera() })
                DropdownMenuItem({ Text(stringResource(R.string.hodhod_ui_attach_file), color = c.text) }, leadingIcon = { Icon(Icons.Rounded.Description, null, tint = c.textSecondary) }, onClick = { menu = false; pickFile.launch("*/*") })
            }
        }
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(c.surfaceField).border(1.dp, c.border, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text, onValueChange = { v: String -> if (v.length <= 10_000) { text = v; vm.draft = v; vm.repo.setTyping(v.isNotEmpty()) } }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 5,
                textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 24.sp, textDirection = TextDirection.Content, fontFamily = VazirmatnFamily),
                cursorBrush = SolidColor(c.accentText), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner -> Box { if (text.isEmpty()) Text(stringResource(R.string.hodhod_chat_placeholder), color = c.placeholder, fontSize = 15.sp, lineHeight = 24.sp); inner() } },
            )
        }
        val enabled = text.isNotBlank()
        Box(Modifier.size(48.dp).alpha(if (enabled) 1f else 0.4f).clip(CircleShape).background(c.accent).clickable(enabled = enabled, role = Role.Button, onClick = send)
            .semantics { contentDescription = ctx.getString(R.string.hodhod_ux_widget_send) }, contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Rounded.Send, null, tint = c.onAccent, modifier = Modifier.size(22.dp))
        }
    }
}

/** End-of-chat footer: orb, thanks, «new conversation» + email transcript row. */
@Composable
private fun EndFooter(vm: HodhodChatViewModel, config: WidgetConfig, hasCsat: Boolean, onStartNew: () -> Unit) {
    val c = HodhodTheme.colors
    val contact by vm.repo.contact.collectAsState()
    val busy by vm.transcriptBusy.collectAsState()
    val sent by vm.transcriptSent.collectAsState()
    val ctx = LocalContext.current
    val canMail = contact.hasEmail && config.enabledFeatures.emailTranscript
    val sendMail = {
        vm.sendTranscript { ok, code ->
            vm.showNotice(if (ok) ctx.getString(R.string.hodhod_email_transcript_send_email_success) else ctx.getString(R.string.hodhod_email_transcript_send_email_error), !ok)
        }
    }
    Column(Modifier.fillMaxWidth().background(c.background).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = if (hasCsat) Alignment.Start else Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = if (hasCsat) Modifier.fillMaxWidth() else Modifier) {
            BrandTile(if (hasCsat) 48.dp else 84.dp)
            Column(horizontalAlignment = if (hasCsat) Alignment.Start else Alignment.CenterHorizontally) {
                Text(stringResource(R.string.hodhod_end_screen_title), color = c.text, fontWeight = FontWeight.Black, fontSize = if (hasCsat) 18.sp else 26.sp, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.hodhod_end_screen_subtitle), color = c.textSecondary, fontSize = 13.sp)
            }
        }
        val newBtn: @Composable (Modifier) -> Unit = { mod ->
            Box(mod.heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).background(c.accent.copy(alpha = 0.12f)).border(1.dp, c.accent.copy(alpha = 0.55f), RoundedCornerShape(18.dp))
                .clickable(role = Role.Button, onClick = onStartNew).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.hodhod_start_new_conversation), color = c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
        if (hasCsat) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                newBtn(Modifier.weight(1f))
                if (canMail) SquareIconButton(if (sent) Icons.Rounded.Check else Icons.Rounded.MailOutline, stringResource(R.string.hodhod_ux_widget2_transcript_row), { if (!busy) sendMail() }, tint = if (sent) c.tealText else c.textSecondary)
            }
        } else {
            newBtn(Modifier)
            if (canMail) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(18.dp)).background(c.surfaceField).border(1.dp, c.border, RoundedCornerShape(18.dp))
                .clickable(enabled = !busy && !sent, role = Role.Button) { sendMail() }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.MailOutline, null, tint = c.textSecondary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.hodhod_ux_widget2_transcript_row), Modifier.weight(1f), color = c.text, fontSize = 14.sp)
                if (sent) Icon(Icons.Rounded.Check, null, tint = c.tealText, modifier = Modifier.size(18.dp))
            }
        }
    }
}
