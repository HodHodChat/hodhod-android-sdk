package chat.hodhod.sdk.ui

import chat.hodhod.sdk.ui.screens.parseJsRegex
import chat.hodhod.sdk.ui.screens.toAsciiDigits
import chat.hodhod.sdk.ui.theme.isRtlLanguage
import chat.hodhod.sdk.ui.util.isSafeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtilTest {
    @Test fun safeUrlSchemes() {
        assertTrue(isSafeUrl("https://x.y")); assertTrue(isSafeUrl("mailto:a@b.c"))
        assertFalse(isSafeUrl("file:///etc/passwd")); assertFalse(isSafeUrl("javascript:alert(1)")); assertFalse(isSafeUrl("intent://x#Intent;end"))
    }

    @Test fun jsRegexConversion() {
        val r = parseJsRegex("/^[0-9]{10}\$/")
        assertNotNull(r); assertTrue(r!!.matches("1234567890")); assertFalse(r.matches("12345"))
        assertTrue(parseJsRegex("/^abc\$/i")!!.matches("ABC"))
    }

    @Test fun digitsNormalised() { assertEquals("0912345", toAsciiDigits("۰۹۱۲۳۴۵")); assertEquals("123", toAsciiDigits("١٢٣")) }

    @Test fun rtlDetection() {
        assertTrue(isRtlLanguage("fa")); assertTrue(isRtlLanguage("ar-EG")); assertFalse(isRtlLanguage("de")); assertFalse(isRtlLanguage(null))
    }
}
