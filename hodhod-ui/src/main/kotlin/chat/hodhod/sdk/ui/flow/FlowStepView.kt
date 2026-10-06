package chat.hodhod.sdk.ui.flow

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.FlowBlock
import chat.hodhod.sdk.FlowControl
import chat.hodhod.sdk.FlowView
import chat.hodhod.sdk.HodhodFlowEngine
import chat.hodhod.sdk.ui.components.BodyHint
import chat.hodhod.sdk.ui.components.GhostButton
import chat.hodhod.sdk.ui.components.HodhodField
import chat.hodhod.sdk.ui.components.PrimaryButton
import chat.hodhod.sdk.ui.theme.HodhodRadius
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.fixUrl
import chat.hodhod.sdk.ui.util.widgetString
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private fun fs(key: String) = "CHATBOT_FLOW.$key"

@Composable
private fun flowText(key: String, vararg args: Any): String = widgetString(fs(key), fs(key), *args)

/** One flow screen: bot identity, breadcrumb, bot bubbles, the current control and back / start-over (web `FlowStepView.vue`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlowStepView(view: FlowView, engine: HodhodFlowEngine, reducedMotion: Boolean, modifier: Modifier = Modifier) {
    val c = HodhodTheme.colors
    val bring = remember { BringIntoViewRequester() }
    // Keep the control (or the newest bubble) in view when the step changes.
    LaunchedEffect(view.focusKey, view.control?.type, view.blocks.size) {
        delay(60)
        runCatching { bring.bringIntoView() }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        view.identity?.let { id ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (id.avatarUrl != null) AsyncImage(fixUrl(id.avatarUrl), null, Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                else Box(Modifier.size(24.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.8f)))
                Text(id.name, color = c.text, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                Box(Modifier.clip(CircleShape).background(c.surfaceMuted).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(flowText("BOT_BADGE"), color = c.textSecondary, fontSize = 10.5.sp)
                }
            }
        }
        if (view.breadcrumb.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(16.dp).size(width = 16.dp, height = 1.dp).background(c.borderStrong))
                Text(view.breadcrumb, color = c.textTertiary, fontSize = 11.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            view.blocks.forEach { key(it.key) { BotBlock(it, engine) } }
            if (view.typing) TypingDots(reducedMotion)
        }
        // Screen-reader announcement of the newest bot message (the visible text is read by focus traversal).
        if (view.announce.isNotEmpty()) Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = view.announce })
        val control = view.control
        if (control != null) {
            Column(Modifier.fillMaxWidth().bringIntoViewRequester(bring), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlView(control, engine, view)
                if ((control.fields["extra"] as? Map<*, *>)?.get("agentButton") == true) {
                    GhostButton(flowText("TALK_TO_AGENT"), { engine.requestAgent("idle") }, leading = Icons.Rounded.SupportAgent)
                }
            }
        }
        if (view.nav.canBack || view.nav.canRestart) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (view.nav.canBack) GhostButton(flowText("BACK"), { engine.goBack() }, leading = Icons.AutoMirrored.Rounded.ArrowBack)
                if (view.nav.canRestart) GhostButton(flowText("START_OVER"), { engine.restart("nav") })
            }
        }
    }
}

@Composable
private fun BotBlock(block: FlowBlock, engine: HodhodFlowEngine) {
    val c = HodhodTheme.colors
    val uri = LocalUriHandler.current
    val text = remember(block.rich, c.accentText) {
        flowRichText(block.rich, c.accentText) { href ->
            engine.markLinkClick(null, href)
            runCatching { uri.openUri(href) }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 11.dp).size(7.dp).drawBehind { drawCircle(c.accent.copy(alpha = 0.3f), radius = size.minDimension) }.clip(CircleShape).background(c.accent.copy(alpha = 0.85f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text, color = c.text, fontSize = 15.sp, lineHeight = 27.sp, modifier = Modifier.alpha(if (block.kind == "idle") 0.8f else 1f),
                style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content),
            )
            block.image?.let { url ->
                AsyncImage(fixUrl(url), block.imageAlt?.takeIf { it.isNotEmpty() }, Modifier.fillMaxWidth().heightIn(max = 192.dp).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
            }
            if (block.buttons.isNotEmpty()) @OptIn(ExperimentalLayoutApi::class) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                block.buttons.forEach { b ->
                    Row(
                        Modifier.heightIn(min = 48.dp).clip(CircleShape).border(1.dp, c.borderStrong, CircleShape)
                            .clickable(role = Role.Button) { engine.markLinkClick(b.id, b.url); runCatching { uri.openUri(b.url) } }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(b.label, color = c.text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Icon(Icons.Rounded.OpenInNew, null, tint = c.textSecondary, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingDots(reduced: Boolean) {
    val c = HodhodTheme.colors
    val desc = flowText("TYPING")
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(start = 17.dp).semantics { contentDescription = desc; liveRegion = LiveRegionMode.Polite }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val a = if (reduced) 0.6f else t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 160), RepeatMode.Reverse), label = "dot$i").value
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(c.textTertiary))
        }
    }
}

// ───────────────────────────── controls ─────────────────────────────

@Composable
private fun ControlView(control: FlowControl, engine: HodhodFlowEngine, view: FlowView) {
    when (control.type) {
        "menu" -> OptionRows(control.list("rows").map { Row3(it["id"] as String, it["label"] as? String ?: "", it["emoji"] as? String ?: "", false) }, multi = false) { engine.selectOption(it) }
        "choice", "multichoice" -> ChoiceControl(control, engine)
        "yesno" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigChoice(control.str("yesLabel"), true, Modifier.weight(1f)) { engine.answerYesNo(true) }
            BigChoice(control.str("noLabel"), false, Modifier.weight(1f)) { engine.answerYesNo(false) }
        }
        "continue" -> PrimaryButton(control.str("label"), { engine.continueNext() })
        "input" -> InputControl(control, engine)
        "rating" -> RatingControl(control, engine)
        "feedback" -> FeedbackControl(control, engine)
        "terminal" -> TerminalControl(control, engine, view)
        "deadend" -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(control.str("text"), color = HodhodTheme.colors.text, fontSize = 15.sp, lineHeight = 28.sp, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content))
            PrimaryButton(flowText("TALK_TO_AGENT"), { engine.requestAgent("dead_end") }, trailing = Icons.AutoMirrored.Rounded.ArrowForward, leading = Icons.Rounded.SupportAgent)
        }
        "offline" -> OfflineControl(control, engine)
        "pending" -> StatusCard(control.str("label").ifEmpty { flowText("WEBHOOK.LOADING") })
        "agent" -> StatusCard(control.str("label").ifEmpty { if (control.bool("ticket")) flowText("CREATING_TICKET") else flowText("CONNECTING_AGENT") }, big = true)
        "handoff-error" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(flowText("HANDOFF_ERROR"), color = HodhodTheme.colors.rubyText, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            PrimaryButton(flowText("RETRY"), { engine.retryHandoff() }, trailing = null)
        }
    }
}

private data class Row3(val id: String, val label: String, val emoji: String, val selected: Boolean)

@Composable
private fun OptionRows(rows: List<Row3>, multi: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.forEachIndexed { i, r -> OptionRow(i + 1, r, multi) { onSelect(r.id) } } }
}

@Composable
private fun OptionRow(index: Int, row: Row3, multi: Boolean, onClick: () -> Unit) {
    val c = HodhodTheme.colors
    val shape = RoundedCornerShape(HodhodRadius.xl)
    val label = row.label.ifEmpty { "—" }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).background(c.surfaceField).border(if (row.selected) 2.dp else 1.dp, if (row.selected) c.accent else c.border, shape)
            .clickable(role = if (multi) Role.Checkbox else Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { if (multi) selected = row.selected }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(c.surfaceMuted), contentAlignment = Alignment.Center) {
            if (multi && row.selected) Icon(Icons.Rounded.Check, null, tint = c.accentText, modifier = Modifier.size(15.dp))
            else Text(index.toString(), color = c.accentText, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
        }
        if (row.emoji.isNotEmpty()) Text(row.emoji, fontSize = 16.sp)
        Text(label, Modifier.weight(1f), color = c.text, fontSize = 14.sp, lineHeight = 24.sp, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content))
        if (!multi) Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.placeholder, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun BigChoice(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = HodhodTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.heightIn(min = 72.dp).clip(shape).background(if (primary) c.accent else c.surfaceField).then(if (primary) Modifier else Modifier.border(1.dp, c.border, shape))
            .clickable(role = Role.Button, onClick = onClick).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (primary) c.onAccent else c.text, fontWeight = FontWeight.Black, fontSize = 17.sp) }
}

@Composable
private fun StatusCard(label: String, big: Boolean = false) {
    val c = HodhodTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceField).border(1.dp, c.border, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = if (big) 14.dp else 12.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(Modifier.size(if (big) 28.dp else 18.dp), color = c.accent, strokeWidth = 2.5.dp)
        Text(label, color = c.textSecondary, fontSize = 14.sp, lineHeight = 24.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceControl(control: FlowControl, engine: HodhodFlowEngine) {
    val c = HodhodTheme.colors
    val multi = control.type == "multichoice"
    val chips = control.str("style") == "chips"
    val rows = control.list("options").map { Row3(it["id"] as String, it["label"] as? String ?: "", "", it["selected"] == true) }.toMutableList()
    val allowOther = control.bool("allowOther")
    val otherOpen = control.bool("otherOpen")
    if (allowOther) rows += Row3("other", control.str("otherLabel"), "", otherOpen)
    val onChoose: (String) -> Unit = { id -> if (multi) { if (id == "other") engine.toggleOption("other") else engine.toggleOption(id) } else engine.chooseOption(id) }
    if (chips) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { r ->
                val shape = CircleShape
                Box(
                    Modifier.heightIn(min = 48.dp).clip(shape).background(if (r.selected) c.accent.copy(alpha = 0.12f) else c.surfaceField).border(if (r.selected) 2.dp else 1.dp, if (r.selected) c.accent else c.border, shape)
                        .clickable(role = if (multi) Role.Checkbox else Role.Button) { onChoose(r.id) }.semantics { if (multi) selected = r.selected }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(r.label.ifEmpty { "—" }, color = c.text, fontSize = 13.5.sp) }
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.forEachIndexed { i, r -> OptionRow(i + 1, r, multi) { onChoose(r.id) } } }
    }
    if (allowOther && otherOpen) {
        HodhodField(
            value = control.str("otherValue"), onValueChange = { engine.setOtherText(it) }, label = control.str("otherLabel"),
            placeholder = flowText("OTHER_PLACEHOLDER"), maxLength = 120,
            keyboardOptions = KeyboardOptions(imeAction = if (multi) ImeAction.Default else ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!multi) engine.submitOther() }),
            trailing = if (!multi) ({ SendButton { engine.submitOther() } }) else null,
        )
    }
    if (multi) {
        val can = control.bool("canSubmit")
        PrimaryButton(control.str("doneLabel"), { if (can) engine.submitMulti() }, enabled = can, trailing = null)
    }
}

@Composable
private fun SendButton(onClick: () -> Unit) {
    val c = HodhodTheme.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(c.accent).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = "send" },
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Rounded.Send, flowText("SEND"), tint = c.onAccent, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun InputControl(control: FlowControl, engine: HodhodFlowEngine) {
    val c = HodhodTheme.colors
    if (control.str("stage") == "confirm") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.align(Alignment.End).clip(RoundedCornerShape(22.dp)).background(c.accent).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(control.str("pendingValue"), color = c.onAccent, fontSize = 14.sp, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content))
            }
            BodyHint(flowText("CONFIRM_INPUT"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.heightIn(min = 52.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, c.borderStrong, RoundedCornerShape(18.dp)).clickable(role = Role.Button) { engine.editInput() }.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    Text(flowText("EDIT_INPUT"), color = c.textSecondary, fontSize = 14.sp)
                }
                PrimaryButton(flowText("CONFIRM_YES"), { engine.confirmInput() }, Modifier.weight(1f), trailing = null)
            }
        }
        return
    }
    val type = control.str("inputType")
    val long = type == "longtext"
    val keyboard = when (type) {
        "email" -> KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send)
        "phone" -> KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send)
        "number" -> KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Send)
        "url" -> KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Send)
        "date" -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Send)
        "longtext" -> KeyboardOptions(imeAction = ImeAction.Default)
        else -> KeyboardOptions(imeAction = ImeAction.Send)
    }
    val maxLength = control.int("maxLength") ?: Int.MAX_VALUE
    val placeholder = control.str("placeholder").ifEmpty { if (type == "date") "YYYY-MM-DD" else "" }.ifEmpty { null }
    var showPicker by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HodhodField(
            value = control.str("value"), onValueChange = { engine.setInputValue(it); engine.markActivity() }, label = control.str("label").ifEmpty { null },
            placeholder = placeholder, error = control.str("error").ifEmpty { null }, singleLine = !long, minLines = if (long) 3 else 1, maxLength = maxLength,
            keyboardOptions = keyboard, keyboardActions = KeyboardActions(onSend = { engine.submitInput() }),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (type == "date") IconTap(Icons.Rounded.CalendarMonth) { showPicker = true }
                    SendButton { engine.submitInput() }
                }
            },
        )
        if (control.bool("skippable")) GhostButton(control.str("skipLabel"), { engine.skipInput() })
    }
    if (showPicker) DatePickerPopup(control.str("value"), control.fields["min"] as? String, control.fields["max"] as? String, onDismiss = { showPicker = false }) { iso ->
        showPicker = false
        engine.setInputValue(iso)
    }
}

@Composable
private fun IconTap(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = HodhodTheme.colors.textSecondary, modifier = Modifier.size(22.dp))
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerPopup(current: String, min: String?, max: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = HodhodTheme.colors
    val iso = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false } }
    val initial = runCatching { iso.parse(current)?.time }.getOrNull()
    val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = initial)
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss, colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = c.surface),
        confirmButton = {
            androidx.compose.material3.TextButton({
                state.selectedDateMillis?.let { onPick(iso.format(java.util.Date(it))) } ?: onDismiss()
            }) { Text("OK", color = c.accentText, fontWeight = FontWeight.Bold) }
        },
    ) { androidx.compose.material3.DatePicker(state) }
}

@Composable
private fun RatingControl(control: FlowControl, engine: HodhodFlowEngine) {
    val c = HodhodTheme.colors
    val scale = control.str("scale")
    val min = control.int("min") ?: 1
    val max = control.int("max") ?: 5
    val value = control.int("value")
    val done = value != null
    val emojis = listOf("😞", "🙁", "😐", "🙂", "😍")
    val labels = (min..max).associateWith { v ->
        when (scale) {
            "thumbs" -> flowText(if (v == 1) "RATING.THUMBS_UP" else "RATING.THUMBS_DOWN")
            "emoji5" -> flowText("RATING.EMOJI_$v")
            else -> flowText("RATING.ARIA", max, v)
        }
    }
    fun label(v: Int): String = labels[v].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.semantics { role = Role.RadioButton }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (min..max).forEach { v ->
                val on = if (value == null) false else if (scale == "stars5") v <= value else v == value
                val shape = RoundedCornerShape(16.dp)
                Box(
                    Modifier.size(width = if (scale == "nps10") 44.dp else 52.dp, height = 52.dp).clip(shape)
                        .background(if (value == v && scale == "nps10") c.accent else c.surfaceField)
                        .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.border, shape)
                        .clickable(enabled = !done, role = Role.RadioButton) { engine.submitRating(v) }
                        .semantics { contentDescription = label(v); selected = value == v; stateDescription = if (value == v) "✓" else "" },
                    contentAlignment = Alignment.Center,
                ) {
                    when (scale) {
                        "stars5" -> Icon(if (on) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (on) c.amber else c.placeholder, modifier = Modifier.size(26.dp))
                        "emoji5" -> Text(emojis[(v - 1).coerceIn(0, 4)], fontSize = 24.sp)
                        "thumbs" -> Icon(if (v == 1) Icons.Rounded.ThumbUp else Icons.Rounded.ThumbDown, null, tint = if (on) c.accent else c.textSecondary, modifier = Modifier.size(24.dp))
                        else -> Text(v.toString(), color = if (value == v) c.onAccent else c.text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        }
        val low = control.str("lowLabel")
        val high = control.str("highLabel")
        if (scale == "nps10" || low.isNotEmpty() || high.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(low.ifEmpty { if (scale == "nps10") flowText("RATING.NPS_LOW") else "" }, color = c.textTertiary, fontSize = 11.5.sp)
                Text(high.ifEmpty { if (scale == "nps10") flowText("RATING.NPS_HIGH") else "" }, color = c.textTertiary, fontSize = 11.5.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FeedbackControl(control: FlowControl, engine: HodhodFlowEngine) {
    val c = HodhodTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surfaceField).border(1.dp, c.border, RoundedCornerShape(20.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (control.str("stage") == "reason") {
            Text(control.str("reasonPrompt"), color = c.textSecondary, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                control.list("reasons").forEach { r -> Chip(r["label"] as? String ?: "", tint = false) { engine.chooseReason(r["id"] as? String) } }
                Chip(flowText("SKIP"), tint = false) { engine.chooseReason(null) }
            }
        } else {
            Text(control.str("question"), color = c.textSecondary, fontSize = 13.sp, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(control.str("yesLabel"), tint = true) { engine.answerFeedback(true) }
                Chip(control.str("noLabel"), tint = false) { engine.answerFeedback(false) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, tint: Boolean, onClick: () -> Unit) {
    val c = HodhodTheme.colors
    Box(
        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(if (tint) c.violetSoft else Color.Transparent).border(1.dp, if (tint) c.accent.copy(alpha = 0.5f) else c.borderStrong, CircleShape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (tint) c.violetText else c.textSecondary, fontSize = 13.sp, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content)) }
}

@Composable
private fun TerminalControl(control: FlowControl, engine: HodhodFlowEngine, view: FlowView) {
    val c = HodhodTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val glad = control.str("glad")
        if (glad.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.tealSoft).border(1.dp, c.teal.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(c.teal), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
                Text(glad, Modifier.weight(1f), color = c.tealText, fontSize = 13.sp, style = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content))
                GhostButton(flowText("START_OVER"), { engine.restart("resolved") }, color = c.tealText)
            }
        }
        if (control.bool("primaryRestart")) PrimaryButton(flowText("BACK_TO_MENU"), { engine.restart("end_button") }, trailing = null)
        if (view.nav.agentLink) GhostButton(flowText("TALK_TO_AGENT"), { engine.requestAgent("manual") }, leading = Icons.Rounded.SupportAgent)
    }
}

@Composable
private fun OfflineControl(control: FlowControl, engine: HodhodFlowEngine) {
    val c = HodhodTheme.colors
    val values = remember { mutableStateMapOf<String, String>() }
    val fields = control.list("fields")
    LaunchedEffect(fields.map { it["name"] to it["value"] }) { fields.forEach { f -> val v = f["value"] as? String ?: ""; if (v.isNotEmpty() || values[f["name"] as String] == null) values[f["name"] as String] = v } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(flowText(if (control.str("trigger") == "no_agents") "OFFLINE.TITLE_NO_AGENTS" else "OFFLINE.TITLE_CLOSED"), color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.semantics { heading() })
            if (control.str("message").isNotEmpty()) BodyHint(control.str("message"))
        }
        if (control.str("mode") == "form") {
            fields.forEach { f ->
                val name = f["name"] as String
                HodhodField(
                    value = values[name].orEmpty(), onValueChange = { values[name] = it; engine.markActivity() }, label = flowText("OFFLINE.FIELD_${name.uppercase()}"), required = f["required"] == true,
                    error = (f["error"] as? String)?.takeIf { it.isNotEmpty() }, singleLine = name != "message", minLines = if (name == "message") 3 else 1, maxLength = if (name == "message") 2000 else 100,
                    keyboardOptions = KeyboardOptions(keyboardType = when (name) { "email" -> KeyboardType.Email; "phone" -> KeyboardType.Phone; else -> KeyboardType.Text }),
                )
            }
            control.str("error").takeIf { it.isNotEmpty() }?.let { Text(it, color = c.rubyText, fontSize = 12.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
            PrimaryButton(flowText("OFFLINE.SUBMIT"), { engine.submitOffline(values.toMap()) }, enabled = !control.bool("submitting"), trailing = null)
        } else {
            PrimaryButton(flowText("OFFLINE.LEAVE_MESSAGE"), { engine.leaveMessage() }, trailing = null)
        }
    }
}
