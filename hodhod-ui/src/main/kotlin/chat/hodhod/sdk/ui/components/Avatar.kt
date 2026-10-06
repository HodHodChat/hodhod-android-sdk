package chat.hodhod.sdk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import chat.hodhod.sdk.ui.theme.HodhodTheme

private val PALETTE = listOf(0xFF7A4FD1, 0xFF3B82F6, 0xFF0EA5A4, 0xFFE0A458, 0xFFE5484D, 0xFF4FC38A, 0xFFB45FD1).map { Color(it) }

/** Squircle avatar: image if [url] loads, initials on a stable colour otherwise. */
@Composable
internal fun HodhodAvatar(name: String, url: String?, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.34f)
    val initials = name.trim().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    val bg = PALETTE[(name.hashCode() and 0x7fffffff) % PALETTE.size]
    val fallback: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
            Text(initials, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.38f).sp)
        }
    }
    val fixed = chat.hodhod.sdk.ui.util.fixUrl(url)
    if (fixed.isNullOrBlank()) Box(modifier) { fallback() } else
        SubcomposeAsyncImage(
            model = fixed, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape).background(HodhodTheme.colors.surfaceMuted),
            error = { fallback() }, loading = { fallback() },
        )
}
