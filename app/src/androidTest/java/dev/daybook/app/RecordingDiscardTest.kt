package dev.daybook.app

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkManager
import dev.daybook.app.capture.RecordingService
import dev.daybook.app.widget.CaptureIntents
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RecordingDiscardTest {
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
            compose.activity.startService(Intent(compose.activity, RecordingService::class.java).setAction(RecordingService.STOP))
            withTimeout(15000) { while (RecordingService.active.value) delay(50) }
        }
    }
    @Test fun discardConfirmationKeepsOrDeletesOnlyTheCurrentDraft() = runBlocking {
        val graph = compose.activity.daybook; val day = LocalDate.now().toString()
        val original = Entry(day = day, originalText = "An earlier memory stays.")
        val base = Snapshot(entries = listOf(original))
        val reflection = Review(day = day, journal = "Earlier reflection", summary = "Preserved",
            sourceIds = listOf(original.id), fingerprint = JournalRules.fingerprint(base, day))
        graph.repository.change { base.copy(reviews = listOf(reflection)) }
        CaptureIntents.pending(compose.activity, 701, "record").send()
        compose.waitUntil(15000) { RecordingService.active.value && RecordingService.session.value.phase == "recording" && RecordingService.session.value.elapsedMs > 500 }
        val id = RecordingService.session.value.entryId
        val audio = graph.repository.read().attachments.single { it.entryId == id }
        compose.onNodeWithText("Discard", substring = false).performClick()
        compose.onNodeWithText("Discard this recording?").assertIsDisplayed()
        compose.onNodeWithText("Keep recording").performClick()
        assertTrue(RecordingService.active.value)
        assertTrue(graph.media.file(audio.path).isFile)
        compose.onNodeWithText("Pause", substring = false).performClick()
        compose.waitUntil(10000) { RecordingService.session.value.phase == "paused" }
        graph.repository.change { it.copy(entries = it.entries.map { e -> if (e.id == id) e.copy(text = "Discard this live draft") else e },
            revisions = it.revisions + Revision(entryId = id, text = "Discard this earlier draft")) }
        compose.onNodeWithText("Discard", substring = false).performClick()
        compose.onNodeWithText("Discard recording", substring = false).performClick()
        compose.waitUntil(15000) { !RecordingService.active.value && RecordingService.session.value.phase == "discarded" }
        assertEquals(base.copy(reviews = listOf(reflection)), graph.repository.read())
        assertFalse(graph.media.file(audio.path).exists())
        assertTrue(WorkManager.getInstance(compose.activity).getWorkInfosForUniqueWork("transcribe-" + id).get().isEmpty())
        assertNull(RecordingService.lastError.value)
        compose.onNode(hasText("Save recording") and hasAnyAncestor(isDialog())).assertDoesNotExist()
    }
    @Test fun discardDuringStartupLeavesNoNoteOrAudioAndNextRecordingCanSave() = runBlocking {
        val graph = compose.activity.daybook
        val before = graph.media.directory.listFiles().orEmpty().map { it.name }.toSet()
        compose.runOnUiThread {
            ContextCompat.startForegroundService(compose.activity, Intent(compose.activity, RecordingService::class.java))
            compose.activity.startService(Intent(compose.activity, RecordingService::class.java).setAction(RecordingService.DISCARD))
        }
        compose.waitUntil(15000) { RecordingService.session.value.phase == "discarded" && !RecordingService.active.value }
        assertTrue(graph.repository.read().entries.isEmpty())
        assertEquals(before, graph.media.directory.listFiles().orEmpty().map { it.name }.toSet())
        CaptureIntents.pending(compose.activity, 702, "record").send()
        try { compose.waitUntil(15000) { RecordingService.active.value && RecordingService.session.value.phase == "recording" && RecordingService.session.value.elapsedMs > 400 } }
        catch (e: Exception) { throw AssertionError("Next recording: active=" + RecordingService.active.value + " session=" + RecordingService.session.value +
            " capture=" + compose.activity.capture.value + " lifecycle=" + compose.activity.lifecycle.currentState + " entries=" + graph.repository.read().entries, e) }
        val id = RecordingService.session.value.entryId
        compose.onNode(hasText("Save recording") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15000) { !RecordingService.active.value }
        assertEquals(id, graph.repository.read().entries.single().id)
        assertTrue(graph.repository.read().attachments.all { graph.media.file(it.path).isFile })
    }
    @Test fun aSavedVoiceEntryCannotBeDiscardedAsAnUnfinishedRecording() = runBlocking {
        val graph = compose.activity.daybook
        val saved = Entry(day = LocalDate.now().toString(), kind = "voice", originalText = "Previously saved voice")
        val before = Snapshot(entries = listOf(saved))
        graph.repository.change { before }
        var rejected = false
        try { graph.repository.discardRecording(saved.id) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertEquals(before, graph.repository.read())
    }
}
