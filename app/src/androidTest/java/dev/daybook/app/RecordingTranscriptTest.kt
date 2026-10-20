package dev.daybook.app

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import dev.daybook.app.capture.RecordingService
import dev.daybook.app.capture.TranscriptionWorker
import dev.daybook.app.data.MediaStore
import dev.daybook.app.widget.CaptureIntents
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecordingTranscriptTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun setup() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await()
        withTimeout(15000) { while (RecordingService.active.value) delay(50) }
        graph.repository.change { Snapshot() }
        graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Your day, captured.").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun finish() = runBlocking<Unit> {
        if (RecordingService.active.value) {
            compose.activity.startService(Intent(compose.activity, RecordingService::class.java).setAction(RecordingService.DISCARD))
            withTimeout(15000) { while (RecordingService.active.value) delay(50) }
        }
    }

    /** Real microphone/save flow; synthetic text isolates editing from native inference latency. */
    private suspend fun savedRecording(text: String, status: String = "ready"): String {
        val graph = compose.activity.daybook
        CaptureIntents.pending(compose.activity, 903, "record").send()
        compose.waitUntil(15000) { RecordingService.active.value && RecordingService.session.value.elapsedMs > 500 }
        val id = RecordingService.session.value.entryId
        compose.onNode(hasText("Save recording") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15000) { !RecordingService.active.value && RecordingService.session.value.phase == "saved" }
        graph.transcriptions.cancel(id).result.get()
        graph.repository.change { state -> state.copy(entries = state.entries.map {
            if (it.id == id) it.copy(text = text, originalText = if (status == "ready") text else "", status = status, error = "") else it
        }) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Edit transcript").fetchSemanticsNodes().isNotEmpty() }
        return id
    }

    private fun editor() = compose.onNode(hasSetTextAction() and hasText("Transcript"))
    private fun saveEdits() = compose.onNodeWithContentDescription("Save transcript changes").assertIsEnabled()
        .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "screenshots/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun savedTranscriptCanBeCorrectedHereWithoutChangingAudioOrOriginals() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        val original = "I went to the jim and cooked diner."
        val correction = "I went to the gym and cooked dinner."
        val id = savedRecording(original)
        val before = graph.repository.read()
        val attachment = before.attachments.single { it.entryId == id && it.kind == "audio" }
        val file = graph.media.file(attachment.path)
        val digest = MediaStore.digest(file)
        val entry = before.entries.single { it.id == id }
        val review = Review(day = entry.day, sourceIds = listOf(id), journal = "Earlier reflection",
            fingerprint = JournalRules.fingerprint(before, entry.day))
        graph.repository.change { it.copy(reviews = listOf(review)) }
        compose.onNodeWithText("Edit transcript").performClick()
        editor().performTextReplacement(correction)
        shot("31-edit-saved-transcript")
        saveEdits()
        compose.waitUntil(10000) { graph.state.value.entries.any { it.id == id && it.text == correction } }
        compose.onNodeWithText("A thought, kept.").assertIsDisplayed()
        compose.onNodeWithText(correction).assertIsDisplayed()
        compose.onNodeWithText("Transcript updated.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Back to my day").assertIsDisplayed()
        val after = graph.repository.read()
        assertEquals(original, after.entries.single { it.id == id }.originalText)
        assertTrue(after.revisions.any { it.entryId == id && it.text == original })
        assertEquals(digest, MediaStore.digest(file))
        assertNotEquals(review.fingerprint, JournalRules.fingerprint(after, entry.day))
        TestListenableWorkerBuilder<TranscriptionWorker>(compose.activity)
            .setInputData(workDataOf("entryId" to id)).build().doWork()
        assertEquals(correction, graph.repository.read().entries.single { it.id == id }.text)
        shot("32-transcript-corrected")
    }

    @Test fun incomingTranscriptionAndRecreationKeepTheUnsavedDraft() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        val id = savedRecording("A live draft.", "pending")
        compose.onNodeWithText("Edit transcript").performClick()
        editor().performTextReplacement("My corrected words stay here.")
        graph.repository.change { state -> state.copy(entries = state.entries.map {
            if (it.id == id) it.copy(originalText = "The full machine transcript.", text = "The full machine transcript.", status = "ready") else it
        }) }
        compose.waitForIdle()
        editor().assertTextContains("My corrected words stay here.")
        compose.activityRule.scenario.recreate()
        editor().assertTextContains("My corrected words stay here.")
        saveEdits()
        compose.waitUntil(10000) { graph.state.value.entries.any { it.id == id && it.text == "My corrected words stay here." } }
        val saved = graph.repository.read()
        assertEquals("The full machine transcript.", saved.entries.single { it.id == id }.originalText)
        assertTrue(saved.revisions.any { it.entryId == id && it.text == "The full machine transcript." })
    }

    @Test fun blankTranscriptsCanBeAddedAndUnsavedChangesCanBeCancelled() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        val id = savedRecording("", "error")
        compose.onNodeWithText("Edit transcript").performClick()
        compose.onNodeWithContentDescription("Save transcript changes").assertIsNotEnabled()
        editor().performTextReplacement("Unsaved words.")
        compose.onNodeWithContentDescription("Close recording").performClick()
        compose.onNodeWithText("Discard transcript changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        editor().assertTextContains("Unsaved words.")
        compose.onNode(hasText("Cancel", substring = false) and hasAnyAncestor(isDialog())).performClick()
        assertEquals("", graph.repository.read().entries.single { it.id == id }.text)
        compose.onNodeWithText("Edit transcript").performClick()
        editor().performTextReplacement("A caption added to my voice note.")
        saveEdits()
        compose.waitUntil(10000) { graph.state.value.entries.any { it.id == id && it.text == "A caption added to my voice note." && it.status == "ready" } }
        compose.onNodeWithText("Back to my day").performClick()
        compose.onNodeWithText("Your day, captured.").assertIsDisplayed()
    }
}
