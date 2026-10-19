package dev.daybook.app

import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.app.capture.RecordingService
import dev.daybook.app.widget.CaptureIntents
import dev.daybook.core.Settings
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Opt-in only: a disposable emulator must have the supplied test PIN configured. */
@RunWith(AndroidJUnit4::class)
class BiometricFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private fun shell(command: String) { automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun promptVisible(): Boolean = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Unlock Daybook")?.isNotEmpty() == true
    @Test fun automaticallyAuthenticatesAndGatesWidgetRecording() {
        val args = InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("biometric") == "true")
        val activity = compose.activity
        check(activity.packageName.endsWith(".verification") && android.os.Build.MODEL.contains("sdk"))
        val pin = args.getString("testPin").orEmpty()
        require(pin.matches(Regex("[0-9]{6}")))
        val graph = activity.daybook
        runBlocking { graph.initialized.await(); graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false)) }
        try {
            runBlocking { graph.preferences.save(graph.preferences.read().copy(biometric = true)) }
            compose.waitUntil(10000) { activity.journalLock.prompting && promptVisible() }
            shell("input keyevent KEYCODE_BACK")
            compose.waitUntil(10000) { !activity.journalLock.prompting }
            compose.onNodeWithText("Unlock journal").assertIsDisplayed()
            compose.waitForIdle()
            assertFalse(activity.journalLock.unlocked.value)
            assertFalse(activity.journalLock.prompting)
            compose.onNodeWithText("Unlock journal").performClick()
            compose.waitUntil(10000) { promptVisible() }
            shell("input text $pin"); shell("input keyevent KEYCODE_ENTER")
            compose.waitUntil(10000) { activity.journalLock.unlocked.value }
            compose.onNodeWithText("Unlock journal").assertDoesNotExist()
            // Leave and launch the same pending intent used by the actual home-screen widget.
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertFalse(activity.journalLock.unlocked.value)
            CaptureIntents.pending(activity, 102, "record").send()
            compose.waitUntil(10000) { promptVisible() }
            assertFalse(RecordingService.active.value)
            shell("input text $pin"); shell("input keyevent KEYCODE_ENTER")
            compose.waitUntil(10000) { RecordingService.active.value }
            activity.getSystemService(NotificationManager::class.java).activeNotifications.first { it.id == 50 }
                .notification.actions.first().actionIntent.send()
            compose.waitUntil(10000) { !RecordingService.active.value }
        } finally {
            runBlocking { graph.preferences.save(graph.preferences.read().copy(biometric = false)) }
            compose.runOnUiThread { activity.configureJournalLock(false) }
        }
    }
}
