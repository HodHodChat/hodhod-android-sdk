package chat.hodhod.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HodhodI18nTest {
    @Test fun configuredLocaleWins() {
        assertEquals("fa", HodhodI18n.resolve("fa-IR", "en", "de-DE"))
        assertEquals("en", HodhodI18n.resolve("ja", "fa", "de-DE")) // unsupported forced locale -> English fallback
    }

    @Test fun serverLocaleThenDevice() {
        assertEquals("fa", HodhodI18n.resolve(null, "fa", "de-DE"))
        assertEquals("de", HodhodI18n.resolve(null, "ja", "de-DE"))
        assertEquals("en", HodhodI18n.resolve(null, null, "ja-JP"))
    }

    @Test fun rtl() {
        assertTrue(HodhodI18n.isRtl("fa"))
        assertTrue(HodhodI18n.isRtl("ar-EG"))
        assertTrue(HodhodI18n.isRtl("he"))
        assertFalse(HodhodI18n.isRtl("en"))
        assertFalse(HodhodI18n.isRtl(null))
    }

    @Test fun normalisation() {
        assertEquals("pt", HodhodI18n.language("pt_BR"))
        assertEquals(null, HodhodI18n.language("  "))
    }
}
