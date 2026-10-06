package chat.hodhod.sdk.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.*
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.HodhodChatViewModel
import chat.hodhod.sdk.ui.components.*
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.widgetString
import kotlinx.coroutines.launch

/** Convert a Chatwoot `/^...$/flags` pattern into a Kotlin [Regex] (null when unusable). */
internal fun parseJsRegex(raw: String?): Regex? {
    if (raw.isNullOrBlank()) return null
    val m = Regex("^/(.*)/([gimsuy]*)$", RegexOption.DOT_MATCHES_ALL).find(raw)
    val body = m?.groupValues?.get(1) ?: raw
    val opts = buildSet { if (m?.groupValues?.get(2)?.contains('i') == true) add(RegexOption.IGNORE_CASE) }
    return runCatching { Regex(body, opts) }.getOrNull()
}

private val AR_DIGITS = mapOf('۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4', '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
    '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4', '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9')
internal fun toAsciiDigits(s: String) = s.map { AR_DIGITS[it] ?: it }.joinToString("")

/** Pre-chat form (widget PreChat/Form.vue): only fields the server does not already know, native keyboards, inline validation. */
@Composable
internal fun PreChatBody(vm: HodhodChatViewModel, config: WidgetConfig, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val contact by vm.repo.contact.collectAsState()
    val scope = rememberCoroutineScope()
    val form = config.preChatForm
    val fields = remember(form, contact) {
        form.fields.filter { f ->
            f.enabled && !(contact.hasEmail && f.name == "emailAddress") && !(contact.hasPhone && f.name == "phoneNumber") &&
                !((contact.identifier != null || contact.hasEmail || contact.hasPhone) && f.name == "fullName")
        }
    }
    val values = remember { mutableStateMapOf<String, String>() }
    var errors by remember { mutableStateOf(mapOf<String, String>()) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun err(f: PreChatField, kind: String): String {
        val key = when (f.name) { "emailAddress" -> "EMAIL_ADDRESS"; "fullName" -> "FULL_NAME"; "phoneNumber" -> "PHONE_NUMBER"; "message" -> "MESSAGE"; else -> null }
        if (key != null) ctx.widgetString("PRE_CHAT_FORM.FIELDS.$key.${if (kind == "required") "REQUIRED_ERROR" else "VALID_ERROR"}")?.let { return it }
        return if (kind == "required") f.label + " " + ctx.getString(R.string.hodhod_pre_chat_form_is_required) else f.regexCue ?: ctx.getString(R.string.hodhod_pre_chat_form_regex_error)
    }
    fun validate(): Boolean {
        val e = mutableMapOf<String, String>()
        fields.forEach { f ->
            val v = (values[f.name] ?: "").trim()
            when {
                v.isEmpty() || v == "false" -> if (f.required) e[f.name] = err(f, "required")
                f.name == "emailAddress" || f.type == "email" -> if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(v)) e[f.name] = err(f, "valid")
                f.name == "phoneNumber" -> if (!Regex("^\\+?[0-9 ()-]{6,20}$").matches(toAsciiDigits(v))) e[f.name] = err(f, "valid")
                else -> parseJsRegex(f.regexPattern)?.let { r -> if (!r.matches(toAsciiDigits(v)) && !r.matches(v)) e[f.name] = err(f, "valid") }
            }
        }
        errors = e; return e.isEmpty()
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!form.message.isNullOrBlank()) Text(form.message!!, color = c.textSecondary, fontSize = 14.sp, lineHeight = 24.sp)
        fields.forEach { f ->
            val v = values[f.name] ?: ""
            val label = f.label.ifBlank { f.name }
            when {
                f.type == "list" || f.type == "select" -> {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surfaceField).border(1.dp, if (errors[f.name] != null) c.rubyText else c.border, RoundedCornerShape(18.dp))
                            .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(label + if (f.required) " *" else "", color = c.textSecondary, fontSize = 11.5.sp)
                            Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(v.ifEmpty { f.placeholder ?: stringResource(R.string.hodhod_ux_widget2_select_placeholder) }, color = if (v.isEmpty()) c.placeholder else c.text, fontSize = 15.sp)
                                Icon(Icons.Rounded.ExpandMore, null, tint = c.textSecondary)
                            }
                        }
                        DropdownMenu(open, { open = false }, containerColor = c.surface) { f.values.forEach { o -> DropdownMenuItem({ Text(o, color = c.text) }, onClick = { values[f.name] = o; open = false }) } }
                    }
                    errors[f.name]?.let { Text(it, Modifier.padding(horizontal = 6.dp), color = c.rubyText, fontSize = 12.sp) }
                }
                f.type == "checkbox" -> {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { values[f.name] = if (v == "true") "false" else "true" }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(v == "true", null, colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent))
                        Text(label + if (f.required) " *" else "", Modifier.padding(start = 8.dp), color = c.text, fontSize = 14.sp)
                    }
                    errors[f.name]?.let { Text(it, Modifier.padding(horizontal = 6.dp), color = c.rubyText, fontSize = 12.sp) }
                }
                else -> HodhodField(v, { values[f.name] = it }, label, placeholder = f.placeholder, required = f.required, error = errors[f.name],
                    singleLine = f.name != "message", minLines = if (f.name == "message") 3 else 1,
                    keyboardOptions = KeyboardOptions(keyboardType = when {
                        f.name == "emailAddress" || f.type == "email" -> KeyboardType.Email
                        f.name == "phoneNumber" -> KeyboardType.Phone
                        f.type == "number" -> KeyboardType.Number
                        f.type == "link" -> KeyboardType.Uri
                        else -> KeyboardType.Text
                    }))
            }
        }
        if (failed) Text(stringResource(R.string.hodhod_ui_generic_error), color = c.rubyText, fontSize = 13.sp)
        PrimaryButton(stringResource(R.string.hodhod_start_conversation), {
            if (busy || !validate()) return@PrimaryButton
            busy = true; failed = false
            scope.launch {
                val payload = fields.filter { it.name != "message" && !values[it.name].isNullOrBlank() }.associate {
                    val raw = values[it.name]!!.trim()
                    it.name to if (it.name == "phoneNumber" || it.type == "number") toAsciiDigits(raw) else raw
                }
                val r = vm.repo.submitPreChat(payload)
                busy = false
                if (r.isSuccess) {
                    values["message"]?.takeIf { it.isNotBlank() }?.let { vm.repo.sendMessage(it.trim()) }
                    onDone()
                } else failed = true
            }
        }, loading = busy, enabled = !busy)
    }
}
