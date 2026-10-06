package chat.hodhod.sdk.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Colour tokens of the «Masir» widget look, mirrored from widget/assets/scss/woot.scss
 * (slate/blue/ruby/amber/teal steps + panel surfaces) for light and dark.
 * [accent] is the inbox `widgetColor`; [onAccent] a contrast-safe text colour on it.
 */
@Immutable
public class HodhodColors(
    public val isDark: Boolean,
    public val background: Color,
    public val surface: Color,        // card (solid-1)
    public val surfaceField: Color,   // field / option (slate-2 light, solid-2 dark)
    public val surfaceMuted: Color,   // slate-3
    public val agentBubble: Color,    // slate-3 / slate-4
    public val agentBubbleBorder: Color,
    public val borderWeak: Color,
    public val border: Color,         // slate-5 / slate-6
    public val borderStrong: Color,
    public val text: Color,           // slate-12
    public val textSecondary: Color,  // slate-11
    public val textTertiary: Color,   // slate-10
    public val placeholder: Color,    // slate-9
    public val accent: Color,
    public val onAccent: Color,
    public val accentText: Color,
    public val teal: Color,
    public val tealText: Color,
    public val tealSoft: Color,
    public val amber: Color,
    public val amberText: Color,
    public val amberSoft: Color,
    public val ruby: Color,
    public val rubyText: Color,
    public val rubySoft: Color,
    public val rubyBorder: Color,
    public val violetSoft: Color,
    public val violetText: Color,
)

internal const val DEFAULT_ACCENT: Long = 0xFF7A4FD1

internal fun hodhodColors(dark: Boolean, accent: Color): HodhodColors {
    val onAccent = onAccentFor(accent)
    return if (dark) HodhodColors(
        isDark = true,
        background = Color(0xFF0F0E13), surface = Color(0xFF15141B), surfaceField = Color(0xFF17161D),
        surfaceMuted = Color(0xFF15141B), agentBubble = Color(0xFF1A1822),
        agentBubbleBorder = Color(0xFF3A3050).copy(alpha = 0.7f),
        borderWeak = Color(0xFF24222B), border = Color(0xFF24222B), borderStrong = Color(0xFF2E2C37),
        text = Color(0xFFEEEDF2), textSecondary = Color(0xFF9A97A6), textTertiary = Color(0xFF8A8796),
        placeholder = Color(0xFF5F5C6B),
        accent = accent, onAccent = onAccent, accentText = reachContrast(accent, Color(0xFF0F0E13), Color(0xFFEEEDF2), 4.5),
        teal = Color(0xFF4FC38A), tealText = Color(0xFF4FC38A), tealSoft = Color(0xFF12241C),
        amber = Color(0xFFE0A458), amberText = Color(0xFFE0A458), amberSoft = Color(0xFF221C12),
        ruby = Color(0xFFE5484D), rubyText = Color(0xFFFF8F86), rubySoft = Color(0xFF1F1416), rubyBorder = Color(0xFF3D2023),
        violetSoft = Color(0xFF1A1726), violetText = Color(0xFFC2ACF5),
    ) else HodhodColors(
        isDark = false,
        background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), surfaceField = Color(0xFFF7F6FA),
        surfaceMuted = Color(0xFFF1F0F5), agentBubble = Color(0xFFF1F0F5), agentBubbleBorder = Color(0xFFE4E2EB),
        borderWeak = Color(0xFFECEAF1), border = Color(0xFFE4E2EB), borderStrong = Color(0xFFD0CDD9),
        text = Color(0xFF17151D), textSecondary = Color(0xFF5E5B6B), textTertiary = Color(0xFF686576),
        placeholder = Color(0xFF8F8B9C),
        accent = accent, onAccent = onAccent, accentText = reachContrast(accent, Color.White, Color(0xFF17151D), 4.5),
        teal = Color(0xFF4FC38A), tealText = Color(0xFF16734A), tealSoft = Color(0xFFE3F6EC),
        amber = Color(0xFFE0A458), amberText = Color(0xFF9A6212), amberSoft = Color(0xFFFDF1DC),
        ruby = Color(0xFFE5484D), rubyText = Color(0xFFC62A2F), rubySoft = Color(0xFFFEEBE9), rubyBorder = Color(0xFFF5BAB5),
        violetSoft = Color(0xFFF2EDFD), violetText = Color(0xFF6035B9),
    )
}

private const val INK = 0xFF17151D

private fun lum(c: Color): Double {
    fun ch(v: Float): Double { val d = v.toDouble(); return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4) }
    return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
}

internal fun contrast(a: Color, b: Color): Double {
    val l1 = lum(a); val l2 = lum(b)
    return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
}

/** White or ink, whichever contrasts better on [accent] (widget `getOnAccentColor`). */
internal fun onAccentFor(accent: Color): Color =
    if (contrast(accent, Color.White) >= contrast(accent, Color(INK))) Color.White else Color(INK)

/** Mixes [base] towards [toward] until it reaches [ratio] against [surface] (widget `reachContrast`). */
internal fun reachContrast(base: Color, surface: Color, toward: Color, ratio: Double): Color {
    var c = base
    var i = 0
    while (contrast(c, surface) < ratio && i < 20) {
        c = androidx.compose.ui.graphics.lerp(c, toward, 0.08f * (i + 1).coerceAtMost(6)); i++
    }
    return c
}
