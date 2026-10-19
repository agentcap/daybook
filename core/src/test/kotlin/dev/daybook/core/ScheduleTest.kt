package dev.daybook.core
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleTest {
    @Test fun quietHoursAndSlots() {
        val s = Settings(wake = "09:00", sleep = "22:00", intervalHours = 3)
        val now = ZonedDateTime.parse("2026-10-18T21:01:00-07:00[America/Los_Angeles]")
        assertEquals("2026-10-19T09:00-07:00[America/Los_Angeles]", Schedule.nextReminder(now, s).toString())
        assertFalse(Schedule.awake(LocalTime.of(22, 0), s))
        assertEquals(12, Schedule.nextReminder(now.withHour(10), s).hour)
    }
    @Test fun supportsOvernightWakingHours() {
        val s = Settings(wake = "20:00", sleep = "06:00")
        assertTrue(Schedule.awake(LocalTime.of(2, 0), s))
        assertFalse(Schedule.awake(LocalTime.NOON, s))
        val now = ZonedDateTime.parse("2026-10-18T23:10:00Z")
        assertEquals(2, Schedule.nextReminder(now, s).hour)
    }
    @Test fun cleanupRemainsLocalAcrossDst() {
        val s = Settings(cleanupTime = "22:00")
        val now = ZonedDateTime.parse("2026-10-31T23:00:00-07:00[America/Los_Angeles]")
        val next = Schedule.nextCleanup(now, s)
        assertEquals(LocalDate.of(2026, 11, 1), next.toLocalDate())
        assertEquals(22, next.hour)
        assertEquals(ZoneOffset.ofHours(-8), next.offset)
    }
}
