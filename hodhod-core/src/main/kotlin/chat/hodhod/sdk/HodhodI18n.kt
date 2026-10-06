package chat.hodhod.sdk

import java.util.Locale

/** Locale + layout-direction decision shared by core and UI. */
public object HodhodI18n {
    /** Locales with full UI translations. Any other locale falls back to [FALLBACK]. */
    public val SUPPORTED: List<String> = listOf("fa", "en", "ar", "de", "es", "fr")

    /** Fallback language for unsupported locales. */
    public const val FALLBACK: String = "en"

    private val RTL = setOf("fa", "ar", "he", "ur", "ps", "ckb", "yi", "dv", "ku")

    /** Normalise to a language code (`fa-IR` -> `fa`, `FA_ir` -> `fa`); blank -> null. */
    public fun language(tag: String?): String? =
        tag?.trim()?.takeIf { it.isNotEmpty() }?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)

    /**
     * Effective UI locale, same precedence as the web widget (host-forced locale, else the inbox account locale), then the device language:
     * [configured] ([HodhodConfig.locale]) > [serverLocale] > [deviceLocale]. The first in [SUPPORTED] wins, otherwise [FALLBACK]. A configured
     * but unsupported locale resolves to [FALLBACK] (the host asked for something specific).
     */
    public fun resolve(configured: String?, serverLocale: String?, deviceLocale: String? = Locale.getDefault().toLanguageTag()): String {
        val configuredLang = language(configured)
        if (configuredLang != null) return if (configuredLang in SUPPORTED) configuredLang else FALLBACK
        val server = language(serverLocale)
        if (server != null && server in SUPPORTED) return server
        val device = language(deviceLocale)
        if (device != null && device in SUPPORTED) return device
        return FALLBACK
    }

    /** True for right-to-left languages. */
    public fun isRtl(locale: String?): Boolean = language(locale) in RTL

    /** Query value for the server `locale` parameter (the server falls back itself for unknown locales). */
    internal fun serverParam(locale: String): String = locale
}
