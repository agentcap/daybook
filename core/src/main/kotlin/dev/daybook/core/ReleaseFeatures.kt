package dev.daybook.core
import java.time.*
import java.time.temporal.TemporalAdjusters

object RecoveryRules {
    const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    fun trash(s: Snapshot, id: String, now: Long = System.currentTimeMillis()): Snapshot {
        require(s.entries.first { it.id == id }.status != "recording") { "Stop recording before moving this note." }
        return s.copy(entries = s.entries.map { if (it.id == id && it.deletedAt == 0L) it.copy(deletedAt = now) else it })
    }
    fun restore(s: Snapshot, id: String) = s.copy(entries = s.entries.map {
        if (it.id == id) it.copy(deletedAt = 0, status = if (it.status == "pending") "interrupted" else it.status) else it })
    fun expired(s: Snapshot, now: Long = System.currentTimeMillis()) =
        s.entries.filter { it.deletedAt > 0 && now - it.deletedAt >= RETENTION_MS }.map { it.id }
    fun visible(s: Snapshot): Snapshot {
        val entries = s.entries.filter { it.deletedAt == 0L }; val ids = entries.map { it.id }.toSet()
        val hiddenDays = s.entries.filter { it.deletedAt > 0 }.map { it.day }.toSet()
        val reviews = s.reviews.filter { (it.sourceIds.isNotEmpty() || it.day !in hiddenDays) && it.sourceIds.all(ids::contains) }; val reviewIds = reviews.map { it.id }.toSet()
        return s.copy(entries = entries, attachments = s.attachments.filter { it.entryId in ids },
            revisions = s.revisions.filter { it.entryId in ids }, reviews = reviews,
            suggestions = s.suggestions.filter { it.reviewId in reviewIds && it.sources.all(ids::contains) },
            proposals = s.proposals.filter { it.reviewId in reviewIds && it.item.sources.all(ids::contains) },
            activities = s.activities.map { it.copy(sources = it.sources.filter(ids::contains)) },
            items = s.items.map { it.copy(sources = it.sources.filter(ids::contains)) })
    }
}
object ReviewPrivacy {
    fun entries(s: Snapshot, day: String, provider: String) =
        s.entries.filter { it.day == day && it.deletedAt == 0L && (provider == "local" || !it.keepLocal) }.sortedBy { it.created }
    fun photos(s: Snapshot, day: String): List<Attachment> {
        val ids = entries(s, day, "openai").filter { it.includePhotosInCloud }.map { it.id }.toSet()
        return s.attachments.filter { it.entryId in ids && it.kind == "photo" }
    }
    fun cloudContext(s: Snapshot): Snapshot {
        val publicIds = s.entries.filter { it.deletedAt == 0L && !it.keepLocal }.map { it.id }.toSet()
        val privateGoals = s.proposals.filter { it.status == "accepted" && it.item.kind == "goal" && it.item.sources.any { id -> id !in publicIds } }.map { it.createdId }.toSet()
        return s.copy(goals = s.goals.filter { it.id !in privateGoals },
            activities = s.activities.filter { it.goalId !in privateGoals && it.sources.all(publicIds::contains) },
            items = s.items.filter { it.sources.all(publicIds::contains) })
    }
}
data class WeeklyGoal(val goal: Goal, val workedDays: Int, val amount: Double)
data class WeeklyDigest(val start: LocalDate, val end: LocalDate, val entries: List<Entry>, val goals: List<WeeklyGoal>,
    val journalDays: Int, val photos: Int, val checkins: Int)
object WeeklyRules {
    fun start(day: LocalDate) = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun digest(raw: Snapshot, day: LocalDate, today: LocalDate = LocalDate.now()): WeeklyDigest {
        val s = RecoveryRules.visible(raw); val start = start(day); val end = minOf(start.plusDays(6), today)
        fun inWeek(d: String) = LocalDate.parse(d).let { !it.isBefore(start) && !it.isAfter(end) }
        val entries = s.entries.filter { inWeek(it.day) }.sortedBy { it.created }
        val activities = s.activities.filter { !it.undone && inWeek(it.day) }
        val goals = s.goals.mapNotNull { g -> val a = activities.filter { it.goalId == g.id }
            if (a.isEmpty()) null else WeeklyGoal(g, a.map { it.day }.distinct().size, a.sumOf { it.amount }) }
        val ids = entries.map { it.id }.toSet()
        return WeeklyDigest(start, end, entries, goals, entries.map { it.day }.distinct().size,
            s.attachments.count { it.entryId in ids && it.kind == "photo" }, activities.map { it.goalId to it.day }.distinct().size)
    }
}
