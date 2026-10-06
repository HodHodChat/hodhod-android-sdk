package chat.hodhod.sdk.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.hodhod.sdk.ui.theme.HodhodRadius
import chat.hodhod.sdk.ui.theme.HodhodTheme

/** Rounded surface card (rounded-3xl, solid-1 with a hairline border). */
@Composable
internal fun HodhodCard(modifier: Modifier = Modifier, padding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val c = HodhodTheme.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(HodhodRadius.xxxl)).background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(HodhodRadius.xxxl)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
    )
}

/** Big 56dp primary action: label at the start, arrow at the end (mirrors in RTL). */
@Composable
internal fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
    trailing: ImageVector? = Icons.AutoMirrored.Rounded.ArrowForward, leading: ImageVector? = null, badge: Int = 0,
) {
    val c = HodhodTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(HodhodRadius.xxl))
            .background(c.accent)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
            if (leading != null) Icon(leading, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
            Text(text, color = c.onAccent, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (badge > 0) Box(Modifier.defaultMinSize(20.dp, 20.dp).clip(CircleShape).background(c.onAccent.copy(alpha = 0.2f)).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text(badge.toString(), color = c.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = c.onAccent, strokeWidth = 2.dp)
        else if (trailing != null) Icon(trailing, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
    }
}

/** Outlined surface button (e.g. «Submit a ticket»). */
@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: ImageVector? = null,
                             trailing: ImageVector? = Icons.AutoMirrored.Rounded.ArrowForward, enabled: Boolean = true) {
    val c = HodhodTheme.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled) 1f else 0.45f).clip(RoundedCornerShape(HodhodRadius.xxl)).background(c.surfaceField)
            .border(1.dp, c.borderStrong, RoundedCornerShape(HodhodRadius.xxl))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (leading != null) Icon(leading, null, tint = c.text, modifier = Modifier.size(20.dp))
            Text(text, color = c.text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
        if (trailing != null) Icon(trailing, null, tint = c.text, modifier = Modifier.size(20.dp))
    }
}

/** Low-emphasis pill/text button, min touch target 48dp. */
@Composable
internal fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = HodhodTheme.colors.textSecondary,
                         leading: ImageVector? = null) {
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (leading != null) Icon(leading, null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
internal fun SquareIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, tint: Color = HodhodTheme.colors.textSecondary, bordered: Boolean = true) {
    val c = HodhodTheme.colors
    Box(
        modifier.size(size).clip(RoundedCornerShape(HodhodRadius.lg)).then(if (bordered) Modifier.background(c.surfaceField).border(1.dp, c.border, RoundedCornerShape(HodhodRadius.lg)) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)) }
}

/**
 * Hodhod brand mark inside a clean neutral circle (surface + hairline border), used wherever a real avatar is absent
 * (header, end screen, error/empty states). No gradient: the mark is the brand purple, its details are cut-outs.
 */
@Composable
internal fun BrandTile(size: Dp, modifier: Modifier = Modifier, square: Boolean = false) {
    val c = HodhodTheme.colors
    val shape = if (square) RoundedCornerShape(size * 0.34f) else CircleShape
    val brand = if (c.isDark) Color(0xFFA977E6) else Color(0xFF7C2BCA)
    Box(modifier.size(size).clip(shape).background(if (c.isDark) Color(0xFF1F1D26) else Color.White).border(1.dp, c.borderStrong, shape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.8f), contentAlignment = Alignment.Center) {
            Icon(androidx.compose.ui.res.painterResource(chat.hodhod.sdk.ui.R.drawable.ic_hodhod_mark), null, tint = brand, modifier = Modifier.fillMaxSize())
            Icon(androidx.compose.ui.res.painterResource(chat.hodhod.sdk.ui.R.drawable.ic_hodhod_mark_details), null, tint = if (c.isDark) Color(0xFF1F1D26) else Color.White, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Glowing status dot (online teal / away amber). */
@Composable
internal fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp, glow: Boolean = true) {
    Box(modifier.size(size).drawBehind { if (glow) drawCircle(color.copy(alpha = 0.35f), radius = this.size.minDimension) }.clip(CircleShape).background(color))
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = HodhodTheme.colors.text, fontWeight = FontWeight.Black, fontSize = 18.sp, lineHeight = 26.sp)
}

@Composable
internal fun BodyHint(text: String, modifier: Modifier = Modifier, color: Color = HodhodTheme.colors.textSecondary, align: TextAlign? = null) {
    Text(text, modifier, color = color, fontSize = 13.sp, lineHeight = 24.sp, textAlign = align)
}

internal fun Modifier.noRipple(onClick: () -> Unit): Modifier =
    clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = onClick)

