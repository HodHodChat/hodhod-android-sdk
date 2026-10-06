package chat.hodhod.sdk.ui.util

import android.content.Context
import chat.hodhod.sdk.Agent
import chat.hodhod.sdk.WidgetConfig
import chat.hodhod.sdk.ui.R
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Port of widget `useAvailability`: online when working hours are enabled and now is inside, else when any agent is online. */
internal fun isTeamOnline(config: WidgetConfig, agents: List<Agent>, now: Long = System.currentTimeMillis()): Boolean =
    if (config.workingHoursEnabled) config.isInWorkingHours(now) else agents.any { it.availability == "online" }

private fun zoneOf(c: WidgetConfig): TimeZone {
    val z = TimeZone.getTimeZone(c.timezone)
    if (z.id == "GMT" && c.timezone != "GMT" && c.timezone != "UTC") c.utcOffset?.let { return TimeZone.getTimeZone("GMT$it") }
    return z
}

/** Next time (epoch millis) the inbox opens within 7 days, or null. Uses java.util only (minSdk 24). */
internal fun nextOpening(config: WidgetConfig, now: Long = System.currentTimeMillis()): Long? {
    if (!config.workingHoursEnabled || config.workingHours.isEmpty()) return null
    val zone = zoneOf(config)
    for (d in 0..7) {
        val day = Calendar.getInstance(zone).apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, d) }
        val dow = day.get(Calendar.DAY_OF_WEEK) - 1 // Sunday = 0
        val slot = config.workingHours.firstOrNull { it.dayOfWeek == dow } ?: continue
        if (slot.closedAllDay) continue
        val key = "%04d-%02d-%02d".format(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
        if (key in config.holidays) continue
        val open = (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, if (slot.openAllDay) 0 else slot.openHour); set(Calendar.MINUTE, if (slot.openAllDay) 0 else slot.openMinutes)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (open.timeInMillis > now) return open.timeInMillis
    }
    return null
}

/** Reply-time / back-online sentence shown under the title (widget AvailabilityText.vue). */
internal fun availabilityText(ctx: Context, config: WidgetConfig, agents: List<Agent>, now: Long = System.currentTimeMillis()): String {
    if (isTeamOnline(config, agents, now)) {
        val key = when (config.replyTime) {
            "in_a_few_minutes" -> R.string.hodhod_reply_time_in_a_few_minutes
            "in_a_few_hours" -> R.string.hodhod_reply_time_in_a_few_hours
            "in_a_day" -> R.string.hodhod_reply_time_in_a_day
            else -> R.string.hodhod_team_availability_online
        }
        return ctx.getString(key)
    }
    val next = nextOpening(config, now) ?: return ctx.getString(R.string.hodhod_team_availability_back_as_soon_as_possible)
    val zone = zoneOf(config)
    val mins = (next - now) / 60_000
    val locale: Locale = ctx.resources.configuration.locales[0]
    val timeStr = android.icu.text.DateFormat.getPatternInstance(android.icu.text.DateFormat.HOUR_MINUTE, locale).apply { timeZone = android.icu.util.TimeZone.getTimeZone(zone.id) }.format(java.util.Date(next))
    fun dayKey(ms: Long, plus: Int = 0) = Calendar.getInstance(zone).apply { timeInMillis = ms; add(Calendar.DAY_OF_YEAR, plus) }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    return when {
        mins < 60 -> ctx.getString(R.string.hodhod_reply_time_back_in_minutes, mins.coerceAtLeast(1).toString())
        dayKey(next) == dayKey(now) ->
            if (mins < 6 * 60) (mins / 60).toInt().coerceAtLeast(1).let { ctx.resources.getQuantityString(R.plurals.hodhod_reply_time_back_in_hours, it, it) }
            else ctx.getString(R.string.hodhod_reply_time_back_at_time, timeStr)
        dayKey(next) == dayKey(now, 1) -> ctx.getString(R.string.hodhod_reply_time_back_tomorrow)
        else -> ctx.getString(R.string.hodhod_reply_time_back_on_day,
            android.icu.text.SimpleDateFormat("EEEE", locale).apply { timeZone = android.icu.util.TimeZone.getTimeZone(zone.id) }.format(java.util.Date(next)))
    }
}
