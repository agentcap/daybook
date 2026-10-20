package dev.daybook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.daybook.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JournalWorkflowTest {
    @get:Rule val permission: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await()
        graph.repository.change { Snapshot() }
        graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false))
    }
    @Test fun writeCreateGoalLogProgressAndSearchOffline() {
        compose.onNodeWithText("Write", useUnmergedTree = false).performClick()
        compose.onNodeWithText("What is on your mind?").performTextInput("Ate a good lunch and read about neural networks.")
        compose.onNodeWithText("Save note").performClick()
        compose.waitUntil(5000) { compose.activity.daybook.state.value.entries.size == 1 }
        compose.onNodeWithText("Goals").performClick()
        compose.onNodeWithText("New goal").performClick()
        compose.onNodeWithText("Calories", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Create goal").performClick()
        compose.waitUntil(10000) { compose.activity.daybook.state.value.goals.size == 1 }
        compose.onNodeWithText("Log activity").performClick()
        compose.onNodeWithText("Amount (kcal)").performTextInput("600")
        compose.onNodeWithText("What did you work on?").performTextInput("Lunch")
        compose.onNodeWithText("Save activity").performClick()
        compose.waitUntil(5000) { compose.activity.daybook.state.value.activities.size == 1 }
        compose.onNodeWithText("Today", substring = false).performClick()
        compose.onNodeWithText("1 goal worked on").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Search your journal").performTextInput("neural")
        compose.onNodeWithText("1 entries · 1 goals worked on").assertIsDisplayed()
        compose.onNodeWithText("Search your journal").performTextClearance()
        compose.onNodeWithText("Search your journal").performTextInput("does-not-exist")
        compose.onNodeWithText("No matching days.").assertIsDisplayed()
    }
    @Test fun reviewLinksOriginalsAndRequiresConfirmationForProgress() {
        val graph = compose.activity.daybook
        val day = java.time.LocalDate.now().toString()
        val entry = Entry(day = day, originalText = "Went to the gym.")
        val goal = Goal(title = "Gym")
        val base = Snapshot(entries = listOf(entry), goals = listOf(goal))
        val review = Review(day = day, fingerprint = JournalRules.fingerprint(base, day), journal = "I went to the gym.",
            summary = "A gym visit.", model = "synthetic test", sourceIds = listOf(entry.id))
        val suggestion = Suggestion(reviewId = review.id, goalId = goal.id, day = day, amount = 1.0,
            note = "Completed gym visit", sources = listOf(entry.id))
        runBlocking { graph.repository.change { base.copy(reviews = listOf(review), suggestions = listOf(suggestion)) } }
        compose.waitUntil(10000) { graph.state.value.reviews.size == 1 }
        compose.onNodeWithText("0 goals worked on").assertExists()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Read reflection"))
        compose.onNodeWithText("Read reflection").performClick()
        compose.onNodeWithText("Original notes").performScrollTo().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        compose.onNode(hasText("Text · ", substring = true)).assertIsDisplayed().performClick()
        compose.waitUntil(5000) { compose.onNodeWithText("Your original moment").isDisplayed() }
        compose.onNodeWithText("Your original moment").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Review & confirm"))
        compose.onNodeWithText("Review & confirm").performClick()
        compose.onNodeWithText("Confirm activity").performClick()
        compose.waitUntil(10000) { graph.state.value.activities.size == 1 }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("1 goal worked on"))
        compose.onNodeWithText("1 goal worked on").assertIsDisplayed()
    }
    @Test fun onboardingStoresChosenScheduleAndConsent() {
        runBlocking { compose.activity.daybook.preferences.save(Settings()) }
        compose.onNodeWithText("Start my journal").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start my journal").performClick()
        compose.waitUntil(5000) { compose.activity.daybook.settings.value.onboarded }
        compose.waitForIdle()
        compose.onNodeWithText("Your day, captured.").assertIsDisplayed()
        Assert.assertFalse(compose.activity.daybook.settings.value.cloudConsent)
    }
}
