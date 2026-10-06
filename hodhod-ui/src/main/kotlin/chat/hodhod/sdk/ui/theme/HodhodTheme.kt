package chat.hodhod.sdk.ui.theme

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import chat.hodhod.sdk.ui.R
import java.util.Locale

internal val LocalHodhodColors = compositionLocalOf { hodhodColors(false, Color(DEFAULT_ACCENT)) }

/** Radii of the Masir design: lg 14, xl 18, 2xl 22, 3xl 26. */
internal object HodhodRadius {
    val lg = 14.dp
    val xl = 18.dp
    val xxl = 22.dp
    val xxxl = 26.dp
    val bubble = 20.dp
    val bubbleTail = 6.dp
}

internal object HodhodTheme {
    val colors: HodhodColors
        @Composable @ReadOnlyComposable get() = LocalHodhodColors.current
}

/** Vazirmatn (SIL OFL) bundled as res/font; used for every script so Persian joins correctly. */
internal val VazirmatnFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_bold, FontWeight.SemiBold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
    Font(R.font.vazirmatn_extrabold, FontWeight.Black),
)

private val RTL_LANGS = setOf("fa", "ar", "he", "ur", "ps", "ku", "sd", "yi", "dv")

internal fun isRtlLanguage(tag: String?): Boolean = tag != null && Locale.forLanguageTag(tag.replace('_', '-')).language in RTL_LANGS

/**
 * Applies the SDK locale override (HodhodConfig.locale) to string resources and layout direction.
 * With a null/blank [localeTag] the system locale is kept.
 */
@Composable
internal fun HodhodLocalized(localeTag: String?, content: @Composable () -> Unit) {
    val base = LocalContext.current
    if (localeTag.isNullOrBlank()) {
        content(); return
    }
    val tag = localeTag.replace('_', '-')
    val ctx = remember(base, tag) { localizedContext(base, tag) }
    CompositionLocalProvider(
        LocalContext provides ctx,
        LocalConfiguration provides ctx.resources.configuration,
        LocalLayoutDirection provides if (isRtlLanguage(tag)) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}

internal fun localizedContext(base: Context, tag: String): Context {
    val locale = Locale.forLanguageTag(tag)
    val cfg = Configuration(base.resources.configuration)
    cfg.setLocale(locale)
    cfg.setLayoutDirection(locale)
    val localized = base.createConfigurationContext(cfg)
    // Keep the original context in the wrapper chain so Activity-based lookups (ActivityResult registry, lifecycle owners) still work.
    return LocalizedContext(base, localized.resources)
}

private class LocalizedContext(base: Context, private val res: android.content.res.Resources) : android.content.ContextWrapper(base) {
    override fun getResources(): android.content.res.Resources = res
}

@Composable
internal fun HodhodTheme(
    accent: Color = Color(DEFAULT_ACCENT),
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = remember(accent, darkTheme) { hodhodColors(darkTheme, accent) }
    val scheme: ColorScheme = if (darkTheme) darkColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.background, onBackground = colors.text,
        surface = colors.background, onSurface = colors.text, surfaceVariant = colors.surface, onSurfaceVariant = colors.textSecondary,
        outline = colors.borderStrong, outlineVariant = colors.border, error = colors.rubyText,
    ) else lightColorScheme(
        primary = colors.accent, onPrimary = colors.onAccent, background = colors.background, onBackground = colors.text,
        surface = colors.background, onSurface = colors.text, surfaceVariant = colors.surface, onSurfaceVariant = colors.textSecondary,
        outline = colors.borderStrong, outlineVariant = colors.border, error = colors.rubyText,
    )
    val base = MaterialTheme.typography
    val typo = androidx.compose.material3.Typography(
        displayLarge = base.displayLarge.copy(fontFamily = VazirmatnFamily), displayMedium = base.displayMedium.copy(fontFamily = VazirmatnFamily),
        displaySmall = base.displaySmall.copy(fontFamily = VazirmatnFamily), headlineLarge = base.headlineLarge.copy(fontFamily = VazirmatnFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = VazirmatnFamily), headlineSmall = base.headlineSmall.copy(fontFamily = VazirmatnFamily),
        titleLarge = base.titleLarge.copy(fontFamily = VazirmatnFamily, fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontFamily = VazirmatnFamily, fontWeight = FontWeight.ExtraBold),
        titleSmall = base.titleSmall.copy(fontFamily = VazirmatnFamily, fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontFamily = VazirmatnFamily), bodyMedium = base.bodyMedium.copy(fontFamily = VazirmatnFamily),
        bodySmall = base.bodySmall.copy(fontFamily = VazirmatnFamily), labelLarge = base.labelLarge.copy(fontFamily = VazirmatnFamily),
        labelMedium = base.labelMedium.copy(fontFamily = VazirmatnFamily), labelSmall = base.labelSmall.copy(fontFamily = VazirmatnFamily),
    )
    CompositionLocalProvider(LocalHodhodColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typo,
            shapes = Shapes(
                small = RoundedCornerShape(HodhodRadius.lg), medium = RoundedCornerShape(HodhodRadius.xl),
                large = RoundedCornerShape(HodhodRadius.xxxl),
            ),
            content = content,
        )
    }
}
