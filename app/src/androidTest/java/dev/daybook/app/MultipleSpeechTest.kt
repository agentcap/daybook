package dev.daybook.app

import android.net.ConnectivityManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import dev.daybook.app.capture.*
import dev.daybook.app.data.MediaStore
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Opt-in: verified model assets must be preinstalled in the dedicated emulator. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
class MultipleSpeechTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
    }
    @Before fun optIn() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("multipleSpeech") == "true")
        check(context.packageName.endsWith(".verification") && Build.MODEL.contains("sdk"))
    }
    @Test fun a_resumesMissingMoonshineComponentFromItsActualHost() = runBlocking {
        val graph = context.daybook; graph.initialized.await()
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        withTimeout(30000) {
            while (connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) } != true) delay(250)
        }
        val choice = SpeechCatalog.get("moonshine_small")
        val asset = choice.assets.first { it.name == "tokenizer.bin" }
        val target = File(graph.model.directory(choice.id), asset.name)
        assertTrue(target.isFile)
        val bytes = target.readBytes()
        val partial = File(target.parentFile, asset.name + ".partial")
        try {
            graph.model.marker(choice.id).delete()
            partial.writeBytes(bytes.copyOfRange(0, bytes.size / 2))
            assertTrue(target.delete())
            assertFalse(graph.model.ready(choice.id))
            val result = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
                .setInputData(workDataOf("modelId" to choice.id)).build().doWork()
            if (result == ListenableWorker.Result.retry()) {
                // Surface the actual transport cause instead of a generic Retry assertion.
                SpeechAssetTransfer(okhttp3.OkHttpClient()).fetch(asset, target, partial) {}
            }
            assertEquals(ListenableWorker.Result.success(), result)
            assertTrue(graph.model.ready(choice.id))
            assertEquals(asset.sha256, MediaStore.digest(target))
        } finally {
            if (!target.exists()) target.writeBytes(bytes)
            partial.delete()
        }
    }
    @Test fun b_allModelsRunOfflinePreserveOriginalsAndCoexistInBothLoadingOrders() = runBlocking {
        val graph = context.daybook; graph.initialized.await()
        val previous = graph.repository.read()
        val prefs = graph.preferences.read()
        val report = StringBuilder()
        val audio = graph.media.file(graph.media.newPath("wav"))
        instrumentation.context.assets.open("jfk.wav").use { input -> audio.outputStream().use { input.copyTo(it) } }
        try {
            // Verify every byte through the production downloader before disconnecting.
            for (choice in SpeechCatalog.choices) {
                assertTrue("Preinstall fixtures for ${choice.title}", choice.assets.all { File(graph.model.directory(choice.id), it.name).isFile })
                graph.model.marker(choice.id).delete()
                val result = TestListenableWorkerBuilder<ModelDownloadWorker>(context)
                    .setInputData(workDataOf("modelId" to choice.id)).build().doWork()
                assertEquals(ListenableWorker.Result.success(), result)
                assertTrue(graph.model.ready(choice.id))
            }
            shell("cmd connectivity airplane-mode enable"); shell("svc wifi disable"); shell("svc data disable")
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            withTimeout(10000) { while (connectivity.activeNetwork != null) delay(250) }
            assertNull(connectivity.activeNetwork)
            val order = SpeechCatalog.choices + SpeechCatalog.choices.reversed()
            for ((index, choice) in order.withIndex()) {
                // Deliberately choose a different default: the worker must honor its queued input.
                graph.preferences.save(prefs.copy(speechModel = if (choice.id == SpeechCatalog.WHISPER) "parakeet_v2" else SpeechCatalog.WHISPER))
                val original = "My original manually edited note ${choice.id}."
                val entry = Entry(day = "1955-01-01", kind = "voice", status = "pending", originalText = original, text = original, speechModel = choice.id)
                val attachment = Attachment(entryId = entry.id, kind = "audio", path = audio.relativeTo(context.filesDir).path, bytes = audio.length(), digest = MediaStore.digest(audio))
                graph.repository.change { Snapshot(entries = listOf(entry), attachments = listOf(attachment)) }
                val started = System.currentTimeMillis()
                val result = TestListenableWorkerBuilder<TranscriptionWorker>(context)
                    .setInputData(workDataOf("entryId" to entry.id, "modelId" to choice.id)).build().doWork()
                val state = graph.repository.read(); val saved = state.entries.single()
                assertEquals("Offline ${choice.title}: ${saved.error}", ListenableWorker.Result.success(), result)
                assertTrue("${choice.title}: ${saved.text}", saved.text.contains("country", ignoreCase = true))
                assertEquals(original, saved.originalText)
                assertEquals(original, state.revisions.single().text)
                assertEquals(choice.id, saved.speechModel)
                assertEquals("ready", saved.status)
                assertTrue(audio.isFile)
                assertTrue(saved.segments.all { it.ms >= 0 && it.endMs >= it.ms })
                report.append("${index + 1}. ${choice.title}: ${System.currentTimeMillis() - started} ms\n${saved.text}\n")
                // A later worker must not replace a user-approved transcript.
                graph.repository.editEntry(entry.id, "User approved text")
                TestListenableWorkerBuilder<TranscriptionWorker>(context).setInputData(workDataOf("entryId" to entry.id, "modelId" to choice.id)).build().doWork()
                assertEquals("User approved text", graph.repository.read().entries.single().text)
            }
            File(context.getExternalFilesDir("verification"), "multiple-speech-result.txt").apply { parentFile!!.mkdirs() }.writeText(report.toString())
        } finally {
            audio.delete(); graph.repository.change { previous }; graph.preferences.save(prefs)
            shell("cmd connectivity airplane-mode disable"); shell("svc wifi enable"); shell("svc data enable")
        }
    }
    @Test fun c_multipleWindowsRetainOffsetsAndRuntimeCanBeCancelledAndReused() = runBlocking {
        val graph = context.daybook; graph.initialized.await()
        val fixture = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
        val audio = File(context.cacheDir, "speech-window-fixture.wav")
        java.io.RandomAccessFile(audio, "rw").use { wav ->
            wav.setLength(0)
            Wav.header(wav, 32000 * 30L + fixture.size - 44)
            wav.seek(44); wav.write(fixture, 44, fixture.size - 44)
            wav.write(ByteArray(32000 * 30 - (fixture.size - 44)))
            wav.write(fixture, 44, fixture.size - 44)
        }
        try {
            for (choice in SpeechCatalog.choices.filter { it.engine != "whisper" }) {
                assertTrue(graph.model.ready(choice.id))
                val parts = SpeechEngines.timeline(choice, graph.model.directory(choice.id), audio)
                assertTrue(choice.title, parts.any { it.ms < 30000 && it.text.contains("country", ignoreCase = true) })
                assertTrue(choice.title, parts.any { it.ms >= 30000 && it.text.contains("country", ignoreCase = true) })
                assertTrue(parts.zipWithNext().all { (a, b) -> a.ms <= b.ms })
                val pending = launch(Dispatchers.IO) { SpeechEngines.timeline(choice, graph.model.directory(choice.id), audio) }
                delay(100); pending.cancelAndJoin(); assertTrue(pending.isCancelled)
                assertTrue(SpeechEngines.timeline(choice, graph.model.directory(choice.id), audio).isNotEmpty())
            }
        } finally { audio.delete() }
    }

}
