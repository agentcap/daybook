package dev.daybook.app

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.daybook.app.capture.RecordingService
import dev.daybook.app.widget.NoteVoiceWidget
import dev.daybook.core.Settings
import dev.daybook.core.Snapshot
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class NoteVoiceWidgetTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await()
        withTimeout(15000) { while (RecordingService.active.value) delay(50) }
        graph.repository.change { Snapshot() }
        graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Your day, captured.").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun finish() = runBlocking {
        if (RecordingService.active.value) {
            compose.activity.startService(Intent(compose.activity, RecordingService::class.java).setAction(RecordingService.DISCARD))
            withTimeout(15000) { while (RecordingService.active.value) delay(50) }
        }
    }

    private fun widget(): View {
        lateinit var view: View
        compose.runOnUiThread {
            view = NoteVoiceWidget.views(compose.activity, 802).apply(compose.activity, FrameLayout(compose.activity))
        }
        return view
    }

    @Test fun writeButtonOpensEditorAndSavesTodaysNoteWithoutRecording() {
        val context = compose.activity
        val provider = AppWidgetManager.getInstance(context).installedProviders.first {
            it.provider == ComponentName(context, NoteVoiceWidget::class.java)
        }
        assertEquals(2, provider.targetCellWidth)
        assertEquals(1, provider.targetCellHeight)
        val view = widget()
        compose.runOnUiThread { assertTrue(view.findViewById<View>(R.id.note_voice_write).performClick()) }
        compose.onNodeWithText("Keep a moment").assertIsDisplayed()
        compose.onNodeWithText("What is on your mind?").performTextInput("A quick note from the home screen.")
        compose.onNodeWithText("Save note").performClick()
        compose.waitUntil(10000) {
            context.daybook.state.value.entries.any {
                it.text == "A quick note from the home screen." && it.day == LocalDate.now().toString()
            }
        }
        assertFalse(RecordingService.active.value)
        assertTrue(context.daybook.state.value.entries.none { it.kind == "voice" })
    }

    @Test fun recordButtonStartsVoiceAndAnotherTapKeepsTheSameRecording() {
        val view = widget()
        compose.runOnUiThread { assertTrue(view.findViewById<View>(R.id.note_voice_record).performClick()) }
        compose.waitUntil(10000) { RecordingService.active.value && RecordingService.session.value.entryId.isNotBlank() }
        compose.onNodeWithText("LIVE TRANSCRIPT").assertIsDisplayed()
        val entryId = RecordingService.session.value.entryId
        compose.runOnUiThread { view.findViewById<View>(R.id.note_voice_record).performClick() }
        compose.waitForIdle()
        assertEquals(entryId, RecordingService.session.value.entryId)
        assertEquals(1, compose.activity.daybook.state.value.entries.count { it.kind == "voice" })
        compose.onNodeWithText("Keep a moment").assertDoesNotExist()
        compose.onNode(hasText("Discard", substring = false) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText("Discard recording", substring = false).performClick()
        compose.waitUntil(15000) { !RecordingService.active.value && RecordingService.session.value.phase == "discarded" }
        compose.waitUntil(10000) { compose.activity.daybook.state.value.entries.none { it.id == entryId } }
    }
}
