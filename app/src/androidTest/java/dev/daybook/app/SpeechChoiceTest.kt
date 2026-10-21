package dev.daybook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import dev.daybook.app.capture.*
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class SpeechChoiceTest {
    @get:Rule val permission = androidx.test.rule.GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook; graph.initialized.await()
        check(compose.activity.packageName.endsWith(".verification"))
        graph.saveSettings(Settings(onboarded = true, reminders = false, autoCleanup = false))
    }
    @Test fun chooserPersistsAndDisablesLivePreviewsForAfterSaveModels() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        compose.onNodeWithText("Settings", substring = false).performClick()
        compose.onNodeWithText("Voice & offline speech").performClick()
        for (id in listOf("moonshine_small", "moonshine_medium", "parakeet_v2")) {
            compose.onAllNodesWithText(SpeechCatalog.get(id).title).onFirst().performScrollTo().performClick()
            compose.waitUntil(10000) { graph.settings.value.speechModel == id }
            assertTrue(graph.preferences.read().liveTranscripts)
            compose.onNodeWithText("Live transcript previews").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("This model transcribes after saving. Choose Whisper for live previews.").assertExists()
        }
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        assertEquals("parakeet_v2", graph.preferences.read().speechModel)
        compose.onNodeWithText("Settings", substring = false).performClick()
        if (compose.onAllNodesWithText("Voice & offline speech").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Voice & offline speech").performClick()
        compose.onAllNodesWithText(SpeechCatalog.get("parakeet_v2").title).onFirst().performScrollTo().assertExists()
        if (androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("speechScreenshots") == "true") {
            compose.onNodeWithText("Choose your speech model").performScrollTo()
            compose.waitForIdle()
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val screenshot = File(compose.activity.getExternalFilesDir(null), "screenshots/33-speech-models.png").apply { parentFile!!.mkdirs() }
            screenshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun recordingPinsSelectedModelWhileSettingsChange() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        graph.saveSettings(graph.preferences.read().copy(speechModel = "moonshine_small"))
        compose.waitUntil(10000) { graph.settings.value.speechModel == "moonshine_small" }
        dev.daybook.app.widget.CaptureIntents.pending(compose.activity, 904, "record").send()
        compose.waitUntil(15000) { RecordingService.active.value && RecordingService.session.value.elapsedMs > 500 }
        val id = RecordingService.session.value.entryId
        try {
            assertEquals("moonshine_small", RecordingService.session.value.speechModel)
            assertFalse(RecordingService.session.value.livePreview)
            graph.saveSettings(graph.preferences.read().copy(speechModel = "parakeet_v2"))
            compose.onNode(hasText("Save recording") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(15000) { !RecordingService.active.value && RecordingService.session.value.phase == "saved" }
            graph.transcriptions.cancel(id).result.get()
            val snapshot = graph.repository.read()
            assertEquals("moonshine_small", snapshot.entries.first { it.id == id }.speechModel)
            assertTrue(graph.media.file(snapshot.attachments.first { it.entryId == id && it.kind == "audio" }.path).isFile)
        } finally {
            if (RecordingService.active.value) {
                compose.activity.startService(android.content.Intent(compose.activity, RecordingService::class.java).setAction(RecordingService.DISCARD))
                withTimeout(15000) { while (RecordingService.active.value) delay(50) }
            }
            graph.transcriptions.cancel(id).result.get()
            graph.saveSettings(Settings(onboarded = true, reminders = false, autoCleanup = false))
        }
    }
    @Test fun unavailableSelectedModelFailsClearlyAndKeepsTextAndAudio() = runBlocking<Unit> {
        val graph = compose.activity.daybook; val previous = graph.repository.read()
        val id = "moonshine_small"
        val marker = graph.model.marker(id); val held = File(marker.parentFile, "test-held-verification")
        if (marker.exists()) check(marker.renameTo(held))
        val file = graph.media.file(graph.media.newPath("wav"))
        RandomAccessFile(file, "rw").use { Wav.header(it, 32000); it.seek(44); it.write(ByteArray(32000)) }
        val entry = Entry(day = "1955-01-01", kind = "voice", status = "pending", originalText = "Saved original", speechModel = id)
        val path = file.relativeTo(compose.activity.filesDir).path
        try {
            graph.repository.change { Snapshot(entries = listOf(entry), attachments = listOf(Attachment(entryId = entry.id, kind = "audio", path = path, bytes = file.length()))) }
            val result = TestListenableWorkerBuilder<TranscriptionWorker>(compose.activity)
                .setInputData(workDataOf("entryId" to entry.id, "modelId" to id)).build().doWork()
            assertTrue(result.toString().contains("Failure"))
            val saved = graph.repository.read().entries.single()
            assertTrue(saved.error.contains("Moonshine v2 Small"))
            assertEquals(entry.originalText, saved.originalText); assertTrue(file.exists())
        } finally { file.delete(); if (held.exists()) check(held.renameTo(marker)); graph.repository.change { previous } }
    }
    @Test fun boundedAudioWindowsKeepOffsetsAndCancellation() = runBlocking<Unit> {
        val file = File(compose.activity.cacheDir, "window-test.wav")
        try {
            RandomAccessFile(file, "rw").use {
                it.setLength(0); Wav.header(it, 32000 * 32L); it.seek(44)
                it.write(ByteArray(32000 * 30)); it.write(ByteArray(32000 * 2) { index -> if (index % 2 == 0) 1 else 2 })
            }
            val windows = mutableListOf<Pair<Long, Long>>()
            SpeechAudio.windows(file) { start, end, samples -> windows += start to end; assertTrue(samples.size <= 480000) }
            assertEquals(listOf(30000L to 32000L), windows)
            val pending = launch { SpeechAudio.windows(file) { _, _, _ -> awaitCancellation() } }
            yield(); pending.cancelAndJoin(); assertTrue(pending.isCancelled)
            RandomAccessFile(file, "rw").use { it.seek(24); it.writeInt(1) }
            try { SpeechAudio.windows(file) { _, _, _ -> }; fail("Reject invalid sample rate") } catch (_: IllegalArgumentException) {}
        } finally { file.delete() }
    }
}
