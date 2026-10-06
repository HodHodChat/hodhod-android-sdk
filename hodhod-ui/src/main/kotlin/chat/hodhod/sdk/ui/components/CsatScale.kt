package chat.hodhod.sdk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.ui.R
import chat.hodhod.sdk.ui.theme.HodhodTheme
import chat.hodhod.sdk.ui.util.widgetString
import androidx.compose.ui.res.stringResource

internal enum class CsatType { EMOJI, STAR, NUMBER;
    companion object { fun from(v: String?) = when (v) { "star" -> STAR; "number" -> NUMBER; else -> EMOJI } }
}

private val EMOJIS = listOf("😞", "😑", "😐", "😀", "😍")
private val LABEL_KEYS = listOf("CSAT.RATINGS.POOR", "CSAT.RATINGS.FAIR", "CSAT.RATINGS.AVERAGE", "CSAT.RATINGS.GOOD", "CSAT.RATINGS.EXCELLENT")

/**
 * 1..5 scale in three flavours (emoji / star / number) with radiogroup semantics, anchor captions
 * (low/high) or the selected caption, per widget CustomerSatisfaction.vue. Order follows the layout
 * direction (1 at the start edge).
 */
@Composable
internal fun CsatScale(type: CsatType, selected: Int?, locked: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = HodhodTheme.colors
    val ctx = LocalContext.current
    val labels = LABEL_KEYS.map { ctx.widgetString(it) ?: "" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in 1..5) {
                val filled = if (selected == null) false else if (type == CsatType.STAR) v <= selected else v == selected
                val desc = ctx.widgetString("UX_WIDGET2.CSAT_NUMBERED", labels[v - 1], v) ?: "$v ${labels[v - 1]}"
                val shape = RoundedCornerShape(14.dp)
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                        .then(if (type == CsatType.STAR) Modifier else Modifier
                            .background(if (filled) c.accent else c.surfaceField)
                            .border(1.dp, if (filled) c.accent else c.border, shape))
                        .alpha(if (type == CsatType.EMOJI && selected != null && !filled) 0.55f else 1f)
                        .selectable(selected = v == selected, enabled = !locked, role = Role.RadioButton, onClick = { onSelect(v) })
                        .semantics { contentDescription = desc },
                    contentAlignment = Alignment.Center,
                ) {
                    when (type) {
                        CsatType.STAR -> Icon(if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
                            tint = if (filled) c.amber else c.textTertiary, modifier = Modifier.size(30.dp))
                        CsatType.EMOJI -> Text(EMOJIS[v - 1], fontSize = 24.sp)
                        CsatType.NUMBER -> Text(v.toString(), color = if (filled) c.onAccent else c.textSecondary, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    }
                }
            }
        }
        if (selected == null) {
            Row(Modifier.fillMaxWidth().clearAndSetSemantics(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels[0], color = c.textSecondary, fontSize = 12.sp)
                Text(labels[4], color = c.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

private fun Modifier.clearAndSetSemantics(): Modifier = this.semantics(mergeDescendants = true) { }
