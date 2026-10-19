package dev.daybook.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.app.capture.*
import dev.daybook.app.data.KeyVault
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceFeaturesTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook; check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await(); graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false))
    }
    @Test fun keystoreRoundTripAndRemoval() {
        val vault = KeyVault(compose.activity)
        try {
            vault.save("synthetic-personal-key-for-testing-only")
            assertTrue(vault.hasKey())
            assertEquals("synthetic-personal-key-for-testing-only", vault.read())
        } finally { vault.save("") }
        assertFalse(vault.hasKey())
    }
    @Test fun reschedulingPreservesMissedNightlyReview() = runBlocking {
        val graph = compose.activity.daybook
        val work = androidx.work.WorkManager.getInstance(compose.activity)
        val previous = graph.preferences.read()
        val day = java.time.LocalDate.now().minusDays(2).toString()
        val pending = androidx.work.OneTimeWorkRequestBuilder<dev.daybook.app.jobs.NightlyDispatchWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .addTag("nightly-dispatch").addTag("nightly-day:$day")
            .setInputData(androidx.work.workDataOf("day" to day)).build()
        try {
            work.enqueueUniqueWork("missed-nightly-fixture", androidx.work.ExistingWorkPolicy.REPLACE, pending).result.get()
            graph.preferences.save(previous.copy(onboarded = true, autoCleanup = true, cloudConsent = true, reminders = false))
            graph.scheduler.reschedule()
            withTimeout(10000) {
                while (work.getWorkInfosForUniqueWork("cleanup-$day").get().isEmpty()) delay(50)
            }
            assertEquals(androidx.work.WorkInfo.State.CANCELLED, work.getWorkInfoById(pending.id).get()!!.state)
            assertTrue(work.getWorkInfosForUniqueWork("cleanup-$day").get().isNotEmpty())
        } finally {
            graph.preferences.save(previous); graph.scheduler.reschedule()
        }
    }
    @Test fun recordingRemainsForegroundWithScreenOffAndNotificationStopsIt() = runBlocking {
        val activity = compose.activity; val graph = activity.daybook
        compose.runOnUiThread {
            ContextCompat.startForegroundService(activity, Intent(activity, RecordingService::class.java).putExtra("day", java.time.LocalDate.now().toString()))
        }
        try {
            withTimeout(10000) { while (!graph.repository.read().entries.any { it.status == "recording" }) delay(50) }
            val shell = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP")
            shell.close()
            delay(1000)
            assertTrue(RecordingService.active.value)
            val notification = activity.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 50 }
            assertNotNull(notification.notification.actions.first().actionIntent)
            notification.notification.actions.first().actionIntent.send()
            withTimeout(10000) { while (RecordingService.active.value) delay(50) }
            val entry = graph.repository.read().entries.last { it.kind == "voice" }
            val attachment = graph.repository.read().attachments.first { it.entryId == entry.id && it.kind == "audio" }
            assertTrue(graph.media.file(attachment.path).length() > 44)
            assertNotEquals("recording", entry.status)
        } finally {
            activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.STOP))
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        }
    }
    @Test fun recordingViewShowsLiveStatusAndCanRunInBackground() = runBlocking {
        val activity = compose.activity; val graph = activity.daybook
        compose.onNodeWithText("Record a thought").performClick()
        try {
            compose.waitUntil(10000) { RecordingService.active.value && RecordingService.session.value.elapsedMs >= 1000 }
            compose.onNodeWithText("LIVE TRANSCRIPT").assertIsDisplayed()
            compose.onNodeWithText("Recording your day").assertIsDisplayed()
            val id = RecordingService.session.value.entryId
            compose.onNodeWithContentDescription("Keep recording in background").performClick()
            compose.onNodeWithText("History", substring = false).performClick()
            assertTrue(RecordingService.active.value)
            compose.onNodeWithText("Today", substring = false).performClick()
            compose.onNodeWithText("View live transcript").performScrollTo().performClick()
            assertEquals(id, RecordingService.session.value.entryId)
            compose.onNode(hasText("Stop & save recording") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(15000) { !RecordingService.active.value }
            compose.onNodeWithText("A thought, kept.").assertIsDisplayed()
            compose.onNodeWithText("Back to my day").performClick()
        } finally { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.STOP)) }
        Unit
    }
    @Test fun failedFinalTranscriptionDoesNotEraseLiveDraft() = runBlocking {
        val graph = compose.activity.daybook
        val entry = Entry(day = java.time.LocalDate.now().toString(), kind = "voice", text = "A draft that was already visible.", status = "pending")
        val path = graph.media.newPath("wav")
        val file = graph.media.file(path)
        java.io.RandomAccessFile(file, "rw").use { Wav.header(it, 0) }
        val attachment = Attachment(entryId = entry.id, path = path, kind = "audio", bytes = file.length())
        graph.repository.change { it.copy(entries = it.entries + entry, attachments = it.attachments + attachment) }
        try {
            androidx.work.testing.TestListenableWorkerBuilder<TranscriptionWorker>(compose.activity)
                .setInputData(androidx.work.workDataOf("entryId" to entry.id)).build().doWork()
            val retained = graph.repository.read().entries.first { it.id == entry.id }
            assertEquals(entry.text, retained.text)
            assertEquals("error", retained.status)
            assertTrue(file.isFile)
        } finally { graph.deleteEntry(entry.id) }
    }

}
