package dev.daybook.core

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlannerTest {
    private val day = "2026-10-18"
    private val entry = Entry(id = "entry", day = day, originalText = "I want to learn Rust. Book a dentist visit. Maybe try pottery.")
    private val review = Review(id = "review", day = day, fingerprint = "", journal = entry.text, summary = "Plans.", model = "fixture")
    private val goal = ProposedItem("goal", "Learn Rust", "An aspiration, not completed activity.", listOf(entry.id))
    private val todo = ProposedItem("todo", "Book a dentist visit", "Call to arrange a visit.", listOf(entry.id))
    private fun pending() = PlannerRules.proposals(Snapshot(entries = listOf(entry), reviews = listOf(review)), review, listOf(goal, todo))
    @Test fun approvalsCreateSeparateItemsButNeverCountAsGoalActivity() {
        val state = pending()
        assertTrue(state.goals.isEmpty()); assertTrue(state.items.isEmpty())
        val goalId = state.proposals.first { it.item.kind == "goal" }.id
        val approved = PlannerRules.approve(state, goalId, goal.title, goal.details, Goal(title = goal.title, period = "once"))
        assertEquals(1, approved.goals.size)
        assertEquals(0, JournalRules.workedOn(approved, day))
        val todoProposal = approved.proposals.first { it.item.kind == "todo" }
        assertTrue(PlannerRules.current(approved, todoProposal))
        val both = PlannerRules.approve(approved, todoProposal.id, todo.title, todo.details)
        assertEquals("todo", both.items.single().kind); assertFalse(both.items.single().done)
        JournalRules.validateSnapshot(both)
    }
    @Test fun duplicatesAndDismissedSuggestionsStayHandledAfterRegeneration() {
        var state = pending()
        val p = state.proposals.first()
        state = PlannerRules.approve(state, p.id, goal.title, goal.details, Goal(title = goal.title))
        val todoId = state.proposals.first { it.item.kind == "todo" }.id
        state = state.copy(proposals = state.proposals.map { if (it.id == todoId) it.copy(status = "rejected") else it })
        val regenerated = PlannerRules.proposals(state, review.copy(id = "review2"), listOf(goal.copy(title = " Learn   RUST! "), todo))
        assertTrue(regenerated.proposals.none { it.status == "pending" })
        assertThrows(IllegalArgumentException::class.java) { PlannerRules.approve(state, p.id, goal.title, goal.details, Goal(title = goal.title)) }
    }
    @Test fun finishedListItemsAndArchivedGoalsAllowNewIntentionsFromNewNotes() {
        val existing = Snapshot(goals = listOf(Goal(title = goal.title, archived = true)),
            items = listOf(ListItem(kind = "todo", title = todo.title, done = true)))
        assertFalse(PlannerRules.alreadyExists(existing, goal))
        assertFalse(PlannerRules.alreadyExists(existing, todo))
        assertTrue(PlannerRules.alreadyExists(existing.copy(items = existing.items.map { it.copy(done = false) }), todo))
        assertTrue(PlannerRules.alreadyExists(existing.copy(goals = existing.goals.map { it.copy(archived = false) }), goal))
    }
    @Test fun editedSourcesInvalidatePendingProposals() {
        val state = pending()
        val changed = state.copy(entries = listOf(entry.copy(text = "Changed my mind.")))
        assertFalse(PlannerRules.current(changed, changed.proposals.first()))
        assertThrows(IllegalArgumentException::class.java) { PlannerRules.approve(changed, changed.proposals.first().id, goal.title, goal.details, Goal(title = goal.title)) }
    }
    @Test fun undoCannotDeleteAGoalWithRecordedActivity() {
        val state = pending()
        val approved = PlannerRules.approve(state, state.proposals.first().id, goal.title, goal.details, Goal(title = goal.title))
        assertTrue(PlannerRules.undo(approved, state.proposals.first().id).goals.isEmpty())
        val worked = approved.copy(activities = listOf(Activity(goalId = approved.goals.single().id, day = day, amount = 0.0, note = "Read a tutorial.")))
        assertThrows(IllegalArgumentException::class.java) { PlannerRules.undo(worked, state.proposals.first().id) }
    }
    @Test fun oldSnapshotJsonLoadsNewCollectionsAsEmpty() {
        val state = Gson().fromJson("""{"entries":[],"revisions":[],"attachments":[],"goals":[],"activities":[],"reviews":[],"suggestions":[]}""", Snapshot::class.java)
        assertTrue(state.items.isEmpty()); assertTrue(state.proposals.isEmpty()); JournalRules.validateSnapshot(state)
    }
    @Test fun streaksUseSavedDistinctDatesAndGiveTodayAGracePeriod() {
        val today = LocalDate.parse(day)
        val state = Snapshot(entries = listOf(-4L, -3L, -2L, -1L, -1L, 1L).map { offset -> Entry(day = today.plusDays(offset).toString(), originalText = "A moment") } +
            Entry(day = day, status = "recording"))
        val stats = StatsRules.calculate(state, today)
        assertEquals(4, stats.currentStreak); assertEquals(4, stats.longestStreak)
        assertEquals(4, stats.journalDays); assertEquals(5, stats.notes)
        assertEquals(0, StatsRules.calculate(state, today.plusDays(3)).currentStreak)
        assertEquals(5, StatsRules.calculate(state.copy(entries = state.entries + Entry(day = day, originalText = "Saved")), today).currentStreak)
    }
    @Test fun liveTranscriptReplacesDraftsOfTheSameChunk() {
        val live = LiveTranscript()
        assertEquals("I want", live.update(0, "I want"))
        assertEquals("I want to learn.", live.update(0, "I want to learn."))
        assertEquals("I want to learn. Tomorrow.", live.update(1, "Tomorrow."))
        assertEquals("I want to learn. Tomorrow.", live.update(1, ""))
        assertThrows(IllegalArgumentException::class.java) { live.update(-1, "bad") }
    }
    @Test fun structuredDiscoveriesRejectInventedSourcesAndUnsupportedTypes() {
        val input = ReviewInput(day, listOf(entry), emptyList(), emptyList())
        val json = """{"journal":"Plans.","summary":"Plans.","captions":[],"suggestions":[],"discoveries":[{"kind":"goal","title":"Learn Rust","details":"A plan.","sources":["entry"],"period":"once","target":1,"unit":"times","checklist":true}]}"""
        assertEquals("goal", ReviewProtocol.parse(json, input).discoveries.single().kind)
        assertThrows(IllegalArgumentException::class.java) { ReviewProtocol.parse(json.replace("\"entry\"", "\"invented\""), input) }
        assertThrows(IllegalArgumentException::class.java) { ReviewProtocol.parse(json.replace("\"goal\"", "\"purchase\""), input) }
    }
}
