package dev.daybook.app
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.ListenableWorker
import dev.daybook.app.capture.*
import dev.daybook.app.data.MediaStore
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RealSpeechTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun optIn() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("realSpeech") == "true")
        check(context.packageName.endsWith(".verification"))
    }
    @Test fun a_downloadRealModelAndVerifyChecksum() = runBlocking {
        val graph = context.daybook; graph.initialized.await()
        val result = TestListenableWorkerBuilder<ModelDownloadWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(SpeechModel.SHA, MediaStore.digest(graph.model.file))
        assertTrue(graph.model.ready())
    }
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } } }
    @Test fun b_realModelTranscribesFixtureInAirplaneMode() = runBlocking {
        val graph = context.daybook; graph.initialized.await()
        assertTrue("Run the download test first.", graph.model.ready())
        require(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Automatic airplane-mode changes are restricted to emulators." }
        val path = graph.media.newPath("wav"); val file = graph.media.file(path)
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            shell("cmd connectivity airplane-mode enable"); shell("svc wifi disable"); shell("svc data disable")
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            repeat(20) { if (connectivity.activeNetwork != null) delay(250) }
            assertNull("The inference check must have no active network.", connectivity.activeNetwork)
            val started = System.currentTimeMillis()
            val text = WhisperEngine.transcribe(graph.model.file, file)
            assertTrue("Actual offline transcription should recognize the fixture.", text.contains("country", ignoreCase = true))
            File(context.getExternalFilesDir("verification"), "speech-result.txt").apply { parentFile!!.mkdirs() }
                .writeText("Whisper base.en; airplane mode; JFK fixture; ${System.currentTimeMillis() - started} ms\n$text\n")
        } finally { file.delete(); shell("cmd connectivity airplane-mode disable"); shell("svc wifi enable"); shell("svc data enable") }
    }
    @Test fun c_realLivePreviewReusesModelAndReplacesPartialTextOffline() = runBlocking {
        val graph = context.daybook; graph.initialized.await()
        assertTrue(graph.model.ready())
        require(Build.MODEL.contains("sdk"))
        val bytes = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
        val count = (bytes.size - 44) / 2
        val pcm = FloatArray(count) { i -> ((bytes[44 + i * 2].toInt() and 255) or (bytes[45 + i * 2].toInt() shl 8)).toShort() / 32768f }
        try {
            shell("cmd connectivity airplane-mode enable"); shell("svc wifi disable"); shell("svc data disable")
            withContext(Dispatchers.IO) {
                val started = System.currentTimeMillis()
                val engine = WhisperEngine.openPreview(graph.model.file)
                try {
                    val live = LiveTranscript()
                    live.update(0, engine.transcribe(pcm.copyOfRange(0, minOf(count, 16000 * 3))))
                    val first = live.update(0, engine.transcribe(pcm.copyOfRange(0, minOf(count, 16000 * 6))))
                    val full = if (count > 16000 * 6) live.update(1, engine.transcribe(pcm.copyOfRange(16000 * 6, minOf(count, 16000 * 12)))) else first
                    assertTrue("Real on-device preview should recognize spoken words.", full.contains("country", ignoreCase = true))
                    File(context.getExternalFilesDir("verification"), "live-speech-result.txt").apply { parentFile!!.mkdirs() }
                        .writeText("Actual base.en live windows; airplane mode; ${System.currentTimeMillis() - started} ms\n$full\n")
                } finally { engine.close() }
            }
        } finally { shell("cmd connectivity airplane-mode disable"); shell("svc wifi enable"); shell("svc data enable") }
    }


    @Test fun d_timestampedTranscriptSupportsSeekingOffline() = runBlocking {
        val graph=context.daybook; graph.initialized.await(); assertTrue(graph.model.ready())
        val file=graph.media.file(graph.media.newPath("wav"))
        val fixture = instrumentation.context.assets.open("jfk.wav").use { it.readBytes() }
        java.io.RandomAccessFile(file,"rw").use { wav ->
            Wav.header(wav, (fixture.size - 44 + 32000 * 30).toLong())
            wav.seek(44); wav.write(ByteArray(32000 * 30)); wav.write(fixture,44,fixture.size-44)
        }
        try {
            val segments=WhisperEngine.timeline(graph.model.file,file)
            assertTrue(segments.joinToString(" ") { it.text }.contains("country",ignoreCase=true))
            val duration=(file.length()-44)*1000/32000
            assertTrue(segments.all { it.ms>=0 && it.endMs>=it.ms && it.endMs<=duration+1000 })
            assertTrue(segments.zipWithNext().all { (a,b) -> a.ms<=b.ms })
            assertTrue("Speech after a silent 30-second window must keep its audio offset.", segments.all { it.ms>=30000 })
        } finally { file.delete() }
    }
    @Test fun e_timestampedInferenceCanBeCancelledAndRuntimeReused() = runBlocking {
        val graph=context.daybook; graph.initialized.await(); assertTrue(graph.model.ready())
        val file=graph.media.file(graph.media.newPath("wav"))
        instrumentation.context.assets.open("jfk.wav").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            val pending=launch(Dispatchers.IO) { WhisperEngine.timeline(graph.model.file,file) }
            delay(100); pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(WhisperEngine.timeline(graph.model.file,file).isNotEmpty())
        } finally { file.delete() }
    }
}
