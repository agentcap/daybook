package dev.daybook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybook.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlannerAndHistoryTest {
    @get:Rule val permission: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await(); graph.repository.change { Snapshot() }
        graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Your day, captured.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun manualTodoAndIdeaCanBeCompletedEditedAndArchived() {
        val graph = compose.activity.daybook
        compose.onNodeWithText("Lists", substring = false).performClick()
        compose.onNodeWithText("New to-do").performClick()
        compose.onNodeWithText("Item title").performTextInput("Book dentist")
        compose.onNodeWithText("Details", substring = false).performTextInput("Call next week")
        compose.onNodeWithText("Save item").performClick()
        compose.waitUntil(10000) { graph.state.value.items.size == 1 }
        compose.onNodeWithContentDescription("Mark Book dentist done").performScrollTo().performClick()
        compose.waitUntil(10000) { graph.state.value.items.single().done }
        compose.onNodeWithText("Done", substring = false).performClick()
        compose.onNodeWithText("Book dentist").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Archive item").performScrollTo().performClick()
        compose.onNodeWithText("Archived", substring = false).performClick()
        compose.onNodeWithText("Book dentist").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ideas", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Open", substring = false).performClick()
        compose.onNodeWithText("New idea").performClick()
        compose.onNodeWithText("Item title").performTextInput("Try pottery")
        compose.onNodeWithText("Save item").performClick()
        compose.waitUntil(10000) { graph.state.value.items.size == 2 }
        assertEquals(0, JournalRules.workedOn(graph.state.value, LocalDate.now().toString()))
    }
    @Test fun aiGoalsTodosAndIdeasRequireApprovalAndDoNotReappear() {
        val graph = compose.activity.daybook
        val day = LocalDate.now().toString()
        val entry = Entry(day = day, originalText = "I want to learn Rust. Book a dentist visit. Maybe try pottery.")
        val drafts = listOf(ProposedItem("goal", "Learn Rust", "A future learning goal.", listOf(entry.id)),
            ProposedItem("todo", "Book a dentist visit", "A future task.", listOf(entry.id)),
            ProposedItem("idea", "Try pottery", "Something to explore.", listOf(entry.id)))
        runBlocking { graph.repository.change {
            val state = Snapshot(entries = listOf(entry))
            val review = Review(day = day, fingerprint = JournalRules.fingerprint(state, day), journal = entry.text, summary = "Plans.", model = "fixture")
            PlannerRules.proposals(state.copy(reviews = listOf(review)), review, drafts)
        } }
        compose.onNodeWithText("Lists", substring = false).performClick()
        compose.onNodeWithText("Suggestions", substring = true).performClick()
        compose.onAllNodesWithText("Review suggestion").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Goal title").assertTextContains("Learn Rust")
        compose.onNodeWithText("Create goal").performClick()
        compose.waitUntil(10000) { graph.state.value.goals.size == 1 }
        assertEquals(0, JournalRules.workedOn(graph.state.value, day))
        compose.onAllNodesWithText("Review suggestion").onFirst().performScrollTo().performClick()
        compose.onNodeWithText("Item title").performTextReplacement("Book a dental checkup")
        compose.onNodeWithText("Approve & add").performClick()
        compose.waitUntil(10000) { graph.state.value.items.size == 1 }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Review suggestion"))
        compose.onNodeWithText("Review suggestion").performClick()
        compose.onNodeWithText("Approve & add").performClick()
        compose.waitUntil(10000) { graph.state.value.items.size == 2 }
        assertTrue(graph.state.value.items.none { it.done })
        runBlocking { graph.repository.change { s ->
            val review = s.reviews.single().copy(id = newId(), fingerprint = JournalRules.fingerprint(s, day))
            PlannerRules.proposals(s.copy(reviews = s.reviews + review), review, drafts)
        } }
        compose.waitUntil(10000) { graph.state.value.reviews.size == 2 }
        assertTrue(graph.state.value.proposals.none { it.status == "pending" })
        val todoProposal = graph.state.value.proposals.first { it.item.kind == "todo" }
        runBlocking { graph.repository.undoProposal(todoProposal.id) }
        assertFalse(graph.repository.readBlockingForTest().items.any { it.kind == "todo" })
    }
    @Test fun calendarAndStatsLinkSavedDaysAndSearchStillWorks() {
        val graph = compose.activity.daybook; val today = LocalDate.now()
        runBlocking { graph.repository.change { Snapshot(entries = listOf(Entry(day = today.toString(), originalText = "Today’s thought"),
            Entry(day = today.minusDays(1).toString(), originalText = "Yesterday’s moment"))) } }
        compose.onNodeWithText("History", substring = false).performClick()
        compose.onNodeWithText("2 day streak").assertIsDisplayed()
        compose.onNodeWithText("Stats", substring = false).performClick()
        compose.onNodeWithText("Days journaled").assertIsDisplayed()
        compose.onNodeWithText("Calendar", substring = false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("$today · 1 notes").performScrollTo().performClick()
        compose.onNodeWithText("Today’s thought").performScrollTo().performClick()
        compose.onNodeWithText("Your original moment").assertIsDisplayed()
        compose.onNodeWithText("Close", substring = false).performClick()
        compose.onNodeWithText("Journal", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Search your journal").performTextInput("Yesterday")
        compose.onNodeWithText("1 entries · 0 goals worked on").assertIsDisplayed()
    }
}
private fun dev.daybook.app.data.JournalRepository.readBlockingForTest() = runBlocking { read() }
