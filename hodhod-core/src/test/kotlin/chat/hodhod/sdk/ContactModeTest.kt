package chat.hodhod.sdk

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactModeTest {
    private fun slot(dow: Int, oh: Int = 9, om: Int = 0, ch: Int = 18, cm: Int = 0, allDay: Boolean = false, closed: Boolean = false) =
        WorkingHour(dow, allDay, closed, oh, om, ch, cm)

    private val allWeek = (0..6).map { slot(it) }
    private fun cfg(mode: ContactMode, hours: List<WorkingHour> = allWeek, enabled: Boolean = true, holidays: List<String> = emptyList(), tz: String = "Asia/Tehran") =
        FakeHodhodRepository.sampleConfig(mode).copy(workingHoursEnabled = enabled, workingHours = hours, holidays = holidays, timezone = tz)

    private fun at(iso: String, tz: String = "Asia/Tehran"): Long = ZonedDateTime.parse("$iso[$tz]").toInstant().toEpochMilli()

    @Test fun chatModeAlwaysChat() {
        assertEquals(StartDecision(StartMode.CHAT, false), cfg(ContactMode.CHAT).startMode(at("2026-10-07T03:00:00+03:30")))
    }

    @Test fun ticketModeAlwaysTicket() {
        assertEquals(StartDecision(StartMode.TICKET, false), cfg(ContactMode.TICKET).startMode(at("2026-10-07T12:00:00+03:30")))
    }

    @Test fun bothIsChoice() {
        assertEquals(StartDecision(StartMode.CHOICE, false), cfg(ContactMode.BOTH).startMode(at("2026-10-07T12:00:00+03:30")))
    }

    @Test fun ticketWhenOfflineSwitchesOnWorkingHours() {
        val c = cfg(ContactMode.TICKET_WHEN_OFFLINE)
        assertEquals(StartDecision(StartMode.CHAT, false), c.startMode(at("2026-10-07T12:00:00+03:30")))
        assertEquals(StartDecision(StartMode.TICKET, true), c.startMode(at("2026-10-07T20:00:00+03:30")))
        assertEquals(StartDecision(StartMode.TICKET, true), c.startMode(at("2026-10-07T08:59:00+03:30")))
        // closing minute is exclusive, opening minute inclusive
        assertEquals(StartDecision(StartMode.TICKET, true), c.startMode(at("2026-10-07T18:00:00+03:30")))
        assertEquals(StartDecision(StartMode.CHAT, false), c.startMode(at("2026-10-07T09:00:00+03:30")))
    }

    @Test fun workingHoursDisabledMeansAlwaysOpen() {
        val c = cfg(ContactMode.TICKET_WHEN_OFFLINE, enabled = false)
        assertEquals(StartDecision(StartMode.CHAT, false), c.startMode(at("2026-10-07T03:00:00+03:30")))
        assertTrue(c.isInWorkingHours(at("2026-10-07T03:00:00+03:30")))
    }

    @Test fun holidayClosesWholeDay() {
        val c = cfg(ContactMode.TICKET_WHEN_OFFLINE, holidays = listOf("2026-10-07"))
        assertEquals(StartDecision(StartMode.TICKET, true), c.startMode(at("2026-10-07T12:00:00+03:30")))
        assertEquals(StartDecision(StartMode.CHAT, false), c.startMode(at("2026-10-08T12:00:00+03:30")))
    }

    @Test fun holidayEvaluatedInInboxTimezoneNotDeviceZone() {
        // 2026-10-06T21:00Z is already 2026-10-07 00:30 in Tehran => holiday there
        val c = cfg(ContactMode.TICKET_WHEN_OFFLINE, hours = (0..6).map { slot(it, allDay = true) }, holidays = listOf("2026-10-07"))
        val instant = ZonedDateTime.of(2026, 10, 6, 21, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertFalse(c.isInWorkingHours(instant))
    }

    @Test fun openAllDayAndClosedAllDay() {
        val wed = 3 // 2026-10-07 is a Wednesday
        val open = cfg(ContactMode.CHAT, hours = listOf(slot(wed, allDay = true)))
        assertTrue(open.isInWorkingHours(at("2026-10-07T03:00:00+03:30")))
        val closed = cfg(ContactMode.CHAT, hours = listOf(slot(wed, closed = true)))
        assertFalse(closed.isInWorkingHours(at("2026-10-07T12:00:00+03:30")))
        // missing slot for the day => closed
        assertFalse(cfg(ContactMode.CHAT, hours = listOf(slot(1))).isInWorkingHours(at("2026-10-07T12:00:00+03:30")))
    }

    @Test fun overnightSlotCrossesMidnight() {
        val c = cfg(ContactMode.CHAT, hours = (0..6).map { slot(it, oh = 22, ch = 6) })
        assertTrue(c.isInWorkingHours(at("2026-10-07T23:30:00+03:30")))
        assertTrue(c.isInWorkingHours(at("2026-10-07T05:59:00+03:30")))
        assertFalse(c.isInWorkingHours(at("2026-10-07T12:00:00+03:30")))
    }

    @Test fun emptyScheduleWithEnabledHoursIsClosed() {
        assertFalse(cfg(ContactMode.CHAT, hours = emptyList()).isInWorkingHours(at("2026-10-07T12:00:00+03:30")))
    }

    @Test fun contactModeWireParsing() {
        assertEquals(ContactMode.TICKET_WHEN_OFFLINE, ContactMode.fromWire("ticket_when_offline"))
        assertEquals(ContactMode.CHAT, ContactMode.fromWire("garbage"))
        assertEquals(ContactMode.CHAT, ContactMode.fromWire(null))
    }
}
