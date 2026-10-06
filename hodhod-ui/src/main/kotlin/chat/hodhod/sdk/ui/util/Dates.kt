package chat.hodhod.sdk.ui.util

import android.icu.text.DateFormat
import android.icu.util.ULocale
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Locale-aware formatting through ICU: fa gets Jalali (Persian calendar) and Persian digits automatically. */
internal object Dates {
    private fun uloc(tag: String?): ULocale {
        val l = ULocale.forLanguageTag((tag ?: Locale.getDefault().toLanguageTag()).replace('_', '-'))
        return if (l.language == "fa" && l.getKeywordValue("calendar") == null) ULocale("fa_IR@calendar=persian") else l
    }

    fun time(epochSec: Long, tag: String?): String =
        DateFormat.getPatternInstance(DateFormat.HOUR_MINUTE, uloc(tag)).format(Date(epochSec * 1000))

    fun fullDay(epochSec: Long, tag: String?): String =
        DateFormat.getPatternInstance(DateFormat.YEAR_MONTH_WEEKDAY_DAY, uloc(tag)).format(Date(epochSec * 1000))

    fun dayShort(epochSec: Long, tag: String?): String =
        DateFormat.getPatternInstance(DateFormat.MONTH_DAY, uloc(tag)).format(Date(epochSec * 1000))

    fun dateTime(epochSec: Long, tag: String?): String =
        DateFormat.getPatternInstance(DateFormat.YEAR_ABBR_MONTH_DAY, uloc(tag)).format(Date(epochSec * 1000)) + " " + time(epochSec, tag)

    /** 0 today, -1 yesterday, else null. */
    fun relativeDay(epochSec: Long): Int? {
        val now = Calendar.getInstance()
        val d = Calendar.getInstance().apply { timeInMillis = epochSec * 1000 }
        fun day(c: Calendar) = c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
        return when (day(now) - day(d)) { 0 -> 0; 1 -> -1; else -> null }
    }

    fun sameDay(a: Long, b: Long): Boolean {
        val x = Calendar.getInstance().apply { timeInMillis = a * 1000 }
        val y = Calendar.getInstance().apply { timeInMillis = b * 1000 }
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
    }
}
