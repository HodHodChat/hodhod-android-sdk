package chat.hodhod.sdk

import java.util.Calendar
import java.util.TimeZone

/** Port of widget/helpers/availabilityHelpers.js (isInWorkingHours + Lark holidays). Uses java.util only (java.time needs API 26, minSdk is 24). */
internal object WorkingHoursCalculator {
    fun zone(config: WidgetConfig): TimeZone {
        if (config.timezone in TimeZone.getAvailableIDs()) return TimeZone.getTimeZone(config.timezone)
        config.utcOffset?.takeIf { Regex("^[+-]\\d{2}:\\d{2}$").matches(it) }?.let { return TimeZone.getTimeZone("GMT$it") }
        return TimeZone.getTimeZone("UTC")
    }

    fun isInWorkingHours(config: WidgetConfig, nowMillis: Long): Boolean {
        if (config.workingHours.isEmpty()) return false
        val cal = Calendar.getInstance(zone(config)).apply { timeInMillis = nowMillis }
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // JS getDay(): Sunday = 0
        val slot = config.workingHours.firstOrNull { it.dayOfWeek == dow } ?: return false
        val date = "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        if (date in config.holidays) return false // holiday = closed all day
        if (slot.openAllDay) return true
        if (slot.closedAllDay) return false
        val current = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val open = slot.openHour * 60 + slot.openMinutes
        val close = slot.closeHour * 60 + slot.closeMinutes
        val crossesMidnight = close <= open
        return if (crossesMidnight) current >= open || current < close else current >= open && current < close
    }
}
