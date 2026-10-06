package chat.hodhod.sdk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.theme.HodhodTheme
import androidx.compose.ui.res.stringResource

/**
 * Widget "field with label inside the box" (WidgetField.vue): rounded 18dp box, small label, focus ring in the accent,
 * error state in ruby, hint/error line below (announced as an error to TalkBack).
 */
@Composable
internal fun HodhodField(
    value: String, onValueChange: (String) -> Unit, label: String?, modifier: Modifier = Modifier,
    placeholder: String? = null, error: String? = null, hint: String? = null, required: Boolean = false,
    singleLine: Boolean = true, minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 8,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, enabled: Boolean = true,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None, maxLength: Int = Int.MAX_VALUE,
    trailing: (@Composable () -> Unit)? = null, focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    val c = HodhodTheme.colors
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)
    val borderColor = when { error != null -> c.rubyText; focused -> c.accentText; else -> c.border }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(if (error != null) c.rubySoft else c.surfaceField)
                .border(if (focused) 2.dp else 1.dp, borderColor, shape)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .semantics { if (error != null) error(error) },
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (!label.isNullOrEmpty()) {
                    Text(
                        if (required) "$label *" else label, color = if (error != null) c.rubyText else c.textSecondary,
                        fontSize = 11.5.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp,
                    )
                }
                BasicTextField(
                    value = value, onValueChange = { if (it.length <= maxLength) onValueChange(it) }, enabled = enabled,
                    singleLine = singleLine, minLines = minLines, maxLines = maxLines, interactionSource = src,
                    textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 26.sp, textDirection = TextDirection.Content, fontFamily = chat.hodhod.sdk.ui.theme.VazirmatnFamily),
                    cursorBrush = SolidColor(c.accentText), keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, visualTransformation = visualTransformation,
                    modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty() && placeholder != null) Text(placeholder, color = c.placeholder, fontSize = 15.sp, lineHeight = 26.sp)
                            inner()
                        }
                    },
                )
            }
            if (trailing != null) trailing()
        }
        val line = error ?: hint
        if (line != null) Text(line, Modifier.padding(horizontal = 6.dp), color = if (error != null) c.rubyText else c.textSecondary, fontSize = 12.sp, lineHeight = 20.sp)
    }
}

