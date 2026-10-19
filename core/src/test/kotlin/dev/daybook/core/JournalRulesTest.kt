package dev.daybook.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class JournalRulesTest {
    private val day = "2026-10-18"
    @Test fun distinctWorkedGoalsIncludesPartialEffortAndExcludesUndo() {
        val goal = Goal(title = "Blog", period = "once")
        val s = Snapshot(goals = listOf(goal), activities = listOf(
            Activity(goalId = goal.id, day = day, note = "Outlined post"),
            Activity(goalId = goal.id, day = day, note = "Wrote introduction"),
            Activity(goalId = "other", day = day, amount = 1.0, undone = true)))
        assertEquals(1, JournalRules.workedOn(s, day))
        assertEquals(0.0, JournalRules.progress(s, goal, LocalDate.parse(day)), 0.0)
    }
    @Test fun weeklyProgressUsesMondayAndNumericDailyGoalsReset() {
        val weekly = Goal(title = "Gym", period = "weekly", target = 3.0)
        val daily = Goal(title = "Calories", unit = "kcal", target = 3000.0, checklist = false)
        val s = Snapshot(goals = listOf(weekly, daily), activities = listOf(
            Activity(goalId = weekly.id, day = "2026-10-12", amount = 1.0),
            Activity(goalId = weekly.id, day = "2026-10-18", amount = 1.0),
            Activity(goalId = weekly.id, day = "2026-10-11", amount = 1.0),
            Activity(goalId = daily.id, day = "2026-10-17", amount = 3100.0)))
        assertEquals(2.0, JournalRules.progress(s, weekly, LocalDate.parse(day)), 0.0)
        assertEquals(0.0, JournalRules.progress(s, daily, LocalDate.parse(day)), 0.0)
    }
    private fun ready(): Pair<Snapshot, Suggestion> {
        val e = Entry(day = day, originalText = "Went to gym.")
        val g = Goal(title = "Gym")
        val state = Snapshot(entries = listOf(e), goals = listOf(g))
        val review = Review(day = day, fingerprint = JournalRules.fingerprint(state, day), journal = "I went to the gym.", summary = "Gym.", model = "test")
        val suggestion = Suggestion(reviewId = review.id, day = day, goalId = g.id, amount = 1.0, note = "Gym", sources = listOf(e.id))
        return state.copy(reviews = listOf(review), suggestions = listOf(suggestion)) to suggestion
    }
    @Test fun confirmationIsExplicitAndCannotBeRepeated() {
        val (state, suggestion) = ready()
        assertEquals(0, JournalRules.workedOn(state, day))
        val next = JournalRules.confirm(state, suggestion.id, 1.0, "Confirmed gym")
        assertEquals(1, JournalRules.workedOn(next, day))
        assertThrows(IllegalArgumentException::class.java) { JournalRules.confirm(next, suggestion.id, 1.0, "") }
    }
    @Test fun regeneratedOverlappingEvidenceCannotCountTwice() {
        val (state, suggestion) = ready()
        val next = JournalRules.confirm(state, suggestion.id, 1.0, "Gym")
        val duplicate = suggestion.copy(id = newId(), status = "pending")
        assertThrows(IllegalArgumentException::class.java) {
            JournalRules.confirm(next.copy(suggestions = next.suggestions + duplicate), duplicate.id, 1.0, "")
        }
    }
    @Test fun changedEntryMakesReviewStale() {
        val (state, suggestion) = ready()
        val changed = state.copy(entries = state.entries.map { it.copy(text = "I planned to go, but did not.") })
        assertThrows(IllegalArgumentException::class.java) { JournalRules.confirm(changed, suggestion.id, 1.0, "") }
    }
    @Test fun invalidProgressAndUnsafeAttachmentAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { JournalRules.validateGoal(Goal(title = "Bad", target = Double.NaN)) }
        val e = Entry(day = day)
        assertThrows(IllegalArgumentException::class.java) { JournalRules.validateSnapshot(Snapshot(entries = listOf(e),
            attachments = listOf(Attachment(entryId = e.id, path = "../key", kind = "photo", bytes = 2)))) }
    }
    @Test fun originalsAndRevisionsArePartOfFingerprint() {
        val e = Entry(day = day, originalText = "Raw")
        val state = Snapshot(entries = listOf(e))
        assertNotEquals(JournalRules.fingerprint(state, day), JournalRules.fingerprint(state.copy(revisions = listOf(Revision(entryId = e.id, text = "Raw"))), day))
    }
}
