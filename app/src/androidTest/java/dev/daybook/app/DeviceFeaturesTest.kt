package dev.daybook.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
}
