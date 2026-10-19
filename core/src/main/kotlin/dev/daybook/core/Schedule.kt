package dev.daybook.core
import java.time.*

object Schedule {
    fun awake(time: LocalTime, s: Settings): Boolean {
        val wake = LocalTime.parse(s.wake); val sleep = LocalTime.parse(s.sleep)
        return if (wake < sleep) time >= wake && time < sleep else time >= wake || time < sleep
    }
    fun nextReminder(now: ZonedDateTime, s: Settings): ZonedDateTime {
        JournalRules.validateSettings(s)
        val candidates = mutableListOf<ZonedDateTime>()
        for (offset in -1L..3L) {
            val start = now.toLocalDate().plusDays(offset).atTime(LocalTime.parse(s.wake)).atZone(now.zone)
            val endDate = if (LocalTime.parse(s.sleep) > LocalTime.parse(s.wake)) start.toLocalDate() else start.toLocalDate().plusDays(1)
            val end = endDate.atTime(LocalTime.parse(s.sleep)).atZone(now.zone)
            var slot = start
            while (slot < end) {
                if (slot > now && awake(slot.toLocalTime(), s)) candidates += slot
                slot = slot.plusHours(s.intervalHours.toLong())
            }
        }
        return candidates.minBy { it.toInstant() }
    }
    fun nextCleanup(now: ZonedDateTime, s: Settings): ZonedDateTime {
        val today = now.toLocalDate().atTime(LocalTime.parse(s.cleanupTime)).atZone(now.zone)
        return if (today > now) today else now.toLocalDate().plusDays(1).atTime(LocalTime.parse(s.cleanupTime)).atZone(now.zone)
    }
}
