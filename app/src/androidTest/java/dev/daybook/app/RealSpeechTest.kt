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
}
