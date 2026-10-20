package dev.daybook.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.daybook.app.capture.RecordingService
import dev.daybook.app.widget.CaptureIntents
import dev.daybook.core.Settings
import dev.daybook.core.Snapshot
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureAndSettingsTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await()
        graph.repository.change { Snapshot() }
        graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Your day, captured.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun widgetStartsImmediatelyDoesNotDuplicateAndRotationDoesNotReplayIt() = runBlocking {
        val context = compose.activity
        val graph = context.daybook
        try {
            assertEquals("", CaptureIntents.captureFrom(context, Intent(context, MainActivity::class.java).putExtra("capture", "record")))
            CaptureIntents.pending(context, 101, "record").send()
            compose.waitUntil(10000) { graph.state.value.entries.any { it.status == "recording" } }
            assertTrue(RecordingService.active.value)
            compose.onNodeWithText("Start recording").assertDoesNotExist()
            CaptureIntents.pending(context, 101, "record").send()
            compose.waitForIdle(); delay(300)
            assertEquals(1, graph.state.value.entries.count { it.kind == "voice" })
            context.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 50 }
                .notification.actions.first().actionIntent.send()
            withTimeout(10000) { while (RecordingService.active.value) delay(50) }
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
            delay(500)
            assertFalse(RecordingService.active.value)
            assertEquals(1, graph.state.value.entries.count { it.kind == "voice" })
            val audio = graph.state.value.attachments.first { it.kind == "audio" }
            assertTrue(graph.media.file(audio.path).length() > 44)
        } finally {
            context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.STOP))
        }
    }
    @Test fun focusedSettingsSaveScheduleAndThemeWithoutGlobalSave() {
        compose.onNodeWithText("Settings", substring = false).performClick()
        compose.onNodeWithText("Reminders & rhythm").performClick()
        compose.onNodeWithText("3 hours", substring = false).performClick()
        compose.onNodeWithText("4 hours", substring = false).performClick()
        compose.onNodeWithText("Save rhythm").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.activity.daybook.settings.value.intervalHours == 4 }
        compose.onNodeWithText("Changes saved").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All settings").performScrollTo().performClick()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.onNodeWithText("Dark", substring = false).performClick()
        compose.waitUntil(10000) { compose.activity.daybook.settings.value.theme == "dark" }
        compose.onNodeWithText("Changes saved").assertIsDisplayed()
        compose.onNodeWithText("Light", substring = false).performClick()
        compose.waitUntil(10000) { compose.activity.daybook.settings.value.theme == "light" }
        compose.onNodeWithText("All settings").performScrollTo().performClick()
        compose.onNodeWithText("Home-screen recording").performScrollTo().performClick()
        compose.onNodeWithText("Add Record widget").performScrollTo().assertIsDisplayed()
    }
    @Test fun apiKeyStatusUpdatesAfterSaveAndRemoval() = runBlocking<Unit> {
        compose.activity.daybook.keys.save("")
        compose.onNodeWithText("Settings", substring = false).performClick()
        compose.onNodeWithText("AI reflections").performClick()
        compose.onNodeWithText("OpenAI API key").performScrollTo().performTextInput("synthetic-api-key-for-ui-testing")
        compose.runOnUiThread {
            compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithText("Save key").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.activity.daybook.keys.hasKey() }
        compose.onNodeWithText("Your API key is saved").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove key").performScrollTo().performClick()
        compose.waitUntil(10000) { !compose.activity.daybook.keys.hasKey() }
        compose.onNodeWithText("Connect your OpenAI key").performScrollTo().assertIsDisplayed()
    }
}
