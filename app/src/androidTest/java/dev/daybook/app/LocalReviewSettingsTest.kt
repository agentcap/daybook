package dev.daybook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import dev.daybook.app.ai.*
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalReviewSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await()
        graph.saveSettings(Settings(onboarded = true, autoCleanup = false, reminders = false))
    }
    @Test fun chooseLocalWithoutAKeyAndSwitchBackWithoutEnablingCloudConsent() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        compose.onNodeWithText("Settings", substring = false).performClick()
        compose.onNodeWithText("AI daily reviews").performClick()
        compose.onNodeWithText("On-device", substring = false).performClick()
        compose.waitUntil(10000) { graph.settings.value.reviewProvider == "local" }
        compose.onNodeWithText("Qwen 2.5 · 1.5B").performScrollTo().assertIsDisplayed()
        assertFalse(graph.preferences.read().cloudConsent)
        compose.onNodeWithText("OpenAI", substring = false).performScrollTo().performClick()
        compose.waitUntil(10000) { graph.settings.value.reviewProvider == "openai" }
        assertFalse(graph.preferences.read().cloudConsent)
        compose.onNodeWithText("OpenAI API key").performScrollTo().assertIsDisplayed()
    }
    @Test fun providerControlsNetworkConstraintsAndOfflineNightlyDispatch() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        assertEquals(NetworkType.NOT_REQUIRED, graph.cleanup.request("2026-10-18", true, "local").workSpec.constraints.requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, graph.cleanup.request("2026-10-18", true, "openai").workSpec.constraints.requiredNetworkType)
        try {
            graph.saveSettings(Settings(onboarded = true, reviewProvider = "local", cloudConsent = false, autoCleanup = true, reminders = false))
            val infos = WorkManager.getInstance(compose.activity).getWorkInfosByTag("nightly-dispatch").get()
            assertTrue(infos.any { !it.state.isFinished })
        } finally { graph.saveSettings(Settings(onboarded = true, autoCleanup = false, reminders = false)) }
    }
    @Test fun switchingToLocalReplacesAnUnfinishedLegacyCloudJob() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        val previous = graph.repository.read()
        val day = "1950-01-01"
        val work = WorkManager.getInstance(compose.activity)
        val old = OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("day" to day, "provider" to "openai")).build()
        try {
            graph.repository.change { Snapshot(entries = listOf(Entry(day = day, kind = "photo"))) }
            work.enqueueUniqueWork("cleanup-$day", ExistingWorkPolicy.REPLACE, old).result.get()
            graph.saveSettings(Settings(onboarded = true, reviewProvider = "local", cloudConsent = false, autoCleanup = false, reminders = false))
            withTimeout(10000) {
                while (true) {
                    val oldState = work.getWorkInfoById(old.id).get()?.state
                    val replacementExists = work.getWorkInfosForUniqueWork("cleanup-$day").get().any { it.id != old.id }
                    // REPLACE may remove the old record after cancelling it.
                    if ((oldState == null || oldState == WorkInfo.State.CANCELLED) && replacementExists) break
                    delay(50)
                }
            }
            val replacement = work.getWorkInfosForUniqueWork("cleanup-$day").get().first { it.id != old.id }
            assertTrue("cleanup-day:$day" in replacement.tags)
            assertTrue(graph.repository.read().reviews.isEmpty())
        } finally {
            work.cancelUniqueWork("cleanup-$day").result.get()
            graph.repository.change { previous }
            graph.saveSettings(Settings(onboarded = true, autoCleanup = false, reminders = false))
        }
    }
    @Test fun missingModelFailsClearlyAndPreservesTheOriginalNote() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        val previous = graph.repository.read()
        val model = graph.localModel.file
        val held = java.io.File(model.parentFile, "test-held-model")
        if (model.isFile) check(model.renameTo(held))
        val entry = Entry(day = "2026-10-18", originalText = "I want to learn Italian.")
        try {
            graph.repository.change { Snapshot(entries = listOf(entry)) }
            graph.preferences.save(Settings(onboarded = true, reviewProvider = "local", cloudConsent = false, autoCleanup = false, reminders = false))
            val result = TestListenableWorkerBuilder<CleanupWorker>(compose.activity)
                .setInputData(workDataOf("day" to entry.day, "force" to true, "provider" to "local")).build().doWork()
            assertTrue(result.toString().contains("Download"))
            val state = graph.repository.read()
            assertEquals(entry, state.entries.single()); assertTrue(state.reviews.isEmpty()); assertTrue(state.proposals.isEmpty())
        } finally {
            if (held.isFile) check(held.renameTo(model))
            graph.repository.change { previous }
            graph.saveSettings(Settings(onboarded = true, autoCleanup = false, reminders = false))
        }
    }
}
