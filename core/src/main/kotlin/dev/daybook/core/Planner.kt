package dev.daybook.core

import com.google.gson.Gson
import java.time.LocalDate
import java.util.Locale

data class ListItem(val id: String = newId(), val kind: String, val title: String, val details: String = "",
    val done: Boolean = false, val archived: Boolean = false, val created: Long = System.currentTimeMillis(),
    val sources: List<String> = emptyList())
data class ProposedItem(val kind: String, val title: String, val details: String, val sources: List<String>,
    val period: String = "once", val target: Double = 1.0, val unit: String = "times", val checklist: Boolean = true)
data class Proposal(val id: String = newId(), val reviewId: String, val day: String, val item: ProposedItem,
    val sourceFingerprint: String, val status: String = "pending", val createdId: String = "", val dedupeKey: String = PlannerRules.key(item))

object PlannerRules {
    fun normalized(title: String) = title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun key(item: ProposedItem) = sha256("${item.kind}|${normalized(item.title)}|${item.sources.sorted().joinToString(",")}".toByteArray())
    fun sourceFingerprint(state: Snapshot, sources: List<String>): String {
        val ids = sources.toSet()
        return sha256(Gson().toJson(listOf(state.entries.filter { it.id in ids }.sortedBy { it.id },
            state.revisions.filter { it.entryId in ids }.sortedBy { it.id },
            state.attachments.filter { it.entryId in ids }.sortedBy { it.id })).toByteArray())
    }
    fun validate(item: ProposedItem) {
        require(item.kind in setOf("goal", "todo", "idea")) { "Unknown suggestion type." }
        require(item.title.isNotBlank() && item.title.length <= 200 && item.details.length <= 4000) { "Check the title and details." }
        require(item.sources.isNotEmpty() && item.sources.distinct().size == item.sources.size)
        JournalRules.validateGoal(Goal(title = item.title, period = item.period, target = item.target, unit = item.unit, checklist = item.checklist))
    }
    fun current(state: Snapshot, proposal: Proposal) = proposal.item.sources.all { id -> state.entries.any { it.id == id && it.day == proposal.day && it.deletedAt == 0L } } &&
        proposal.sourceFingerprint == sourceFingerprint(state, proposal.item.sources)
    fun alreadyExists(state: Snapshot, item: ProposedItem) = if (item.kind == "goal")
        state.goals.any { !it.archived && normalized(it.title) == normalized(item.title) }
        else state.items.any { !it.done && !it.archived && it.kind == item.kind && normalized(it.title) == normalized(item.title) }

    fun approve(state: Snapshot, id: String, title: String, details: String, goal: Goal? = null): Snapshot {
        val p = state.proposals.first { it.id == id }
        require(p.status == "pending") { "This suggestion has already been handled." }
        require(current(state, p)) { "The supporting notes changed. Refresh the reflection before approving." }
        val edited = p.item.copy(title = title.trim(), details = details.trim())
        validate(edited)
        require(!alreadyExists(state, edited)) { "An item with this title already exists." }
        require(state.proposals.none { it.id != id && it.status == "accepted" && it.dedupeKey == p.dedupeKey }) { "This suggestion was already added." }
        val createdId: String
        val next = if (edited.kind == "goal") {
            val g = requireNotNull(goal) { "Review the goal details first." }
            require(g.title == edited.title); JournalRules.validateGoal(g)
            createdId = g.id
            state.copy(goals = state.goals + g)
        } else {
            val item = ListItem(kind = edited.kind, title = edited.title, details = edited.details, sources = edited.sources)
            createdId = item.id
            state.copy(items = state.items + item)
        }
        return next.copy(proposals = next.proposals.map { if (it.id == id) it.copy(item = edited, status = "accepted", createdId = createdId) else it })
    }
    fun undo(state: Snapshot, id: String): Snapshot {
        val p = state.proposals.first { it.id == id }
        require(p.status == "accepted") { "This item has already been removed." }
        if (p.item.kind == "goal") require(state.activities.none { it.goalId == p.createdId }) { "This goal has activity. Archive it from Goals instead." }
        else {
            val item = state.items.first { it.id == p.createdId }
            require(!item.done && !item.archived && item.title == p.item.title && item.details == p.item.details) { "This item has changed. Manage it from Lists instead." }
        }
        return state.copy(goals = state.goals.filterNot { it.id == p.createdId }, items = state.items.filterNot { it.id == p.createdId },
            proposals = state.proposals.map { if (it.id == id) it.copy(status = "undone", createdId = "") else it })
    }
    fun proposals(state: Snapshot, review: Review, draft: List<ProposedItem>): Snapshot {
        val handled = state.proposals.filter { it.status !in setOf("pending", "superseded") }.map { it.dedupeKey }.toSet()
        val fresh = draft.filter { key(it) !in handled && !alreadyExists(state, it) }.distinctBy(::key).map {
            Proposal(reviewId = review.id, day = review.day, item = it, sourceFingerprint = sourceFingerprint(state, it.sources))
        }
        return state.copy(proposals = state.proposals.map { if (it.day == review.day && it.status == "pending") it.copy(status = "superseded") else it } + fresh)
    }
}

data class JournalStats(val notes: Int, val journalDays: Int, val photos: Int, val currentStreak: Int, val longestStreak: Int)
object StatsRules {
    fun days(state: Snapshot, today: LocalDate) = state.entries.filter { it.status != "recording" && it.deletedAt == 0L }.map { LocalDate.parse(it.day) }.filter { it <= today }.toSet()
    fun calculate(state: Snapshot, today: LocalDate = LocalDate.now()): JournalStats {
        val days = days(state, today)
        var cursor = if (today in days) today else today.minusDays(1)
        var current = 0
        while (cursor in days) { current++; cursor = cursor.minusDays(1) }
        var best = 0; var run = 0; var previous: LocalDate? = null
        days.sorted().forEach { d -> run = if (previous?.plusDays(1) == d) run + 1 else 1; best = maxOf(best, run); previous = d }
        return JournalStats(state.entries.count { it.status != "recording" && it.deletedAt == 0L && LocalDate.parse(it.day) <= today }, days.size,
            state.attachments.count { it.kind == "photo" && state.entries.any { e -> e.id == it.entryId && e.deletedAt == 0L && LocalDate.parse(e.day) <= today } }, current, best)
    }
}
