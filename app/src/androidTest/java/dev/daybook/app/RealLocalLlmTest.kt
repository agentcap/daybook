package dev.daybook.app

import android.os.Build
import android.net.ConnectivityManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import dev.daybook.app.ai.*
import dev.daybook.app.capture.WhisperEngine
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
class RealLocalLlmTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun optIn() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("localLlm") == "true")
        check(context.packageName.endsWith(".verification"))
        require(Build.MODEL.contains("sdk"))
    }
    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    @Test fun a_downloadPinnedModelAndVerifyChecksum() = runBlocking<Unit> {
        val graph = context.daybook; graph.initialized.await()
        val result = TestListenableWorkerBuilder<LocalModelDownloadWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(LocalModel.SHA, MediaStore.digest(graph.localModel.file))
        assertTrue(graph.localModel.ready())
    }
    @Test fun c_existingGoalActivityUsesTheActualLocalEngine() = runBlocking<Unit> {
        val graph = context.daybook; graph.initialized.await()
        val note = Entry(id = "activity-source", day = "2026-10-18", originalText = "I completed my gym goal this morning. I went to the gym.")
        val input = ReviewInput(note.day, listOf(note), listOf(Goal(id = "gym", title = "Go to the gym")), emptyList())
        val actual = object : LocalTextEngine {
            override suspend fun generate(model: File, system: String, input: String, grammar: String): String {
                val raw = LocalEngine.generate(model, system, input, grammar)
                File(context.getExternalFilesDir("verification"), "local-activity-result.txt").apply { parentFile!!.mkdirs() }
                    .writeText(input + "\n" + grammar + "\nRESULT\n" + raw + "\n" + LocalEngine.stats.value)
                return raw
            }
        }
        val result = LocalCleanupClient(graph.localModel.file, graph.localModel::ready, actual).generate(input)
        assertTrue(result.suggestions.any { it.goalId == "gym" && it.amount == 1.0 })
        assertTrue(result.discoveries.isEmpty())
    }
    @Test fun d_futurePlansDoNotBecomeGoalProgress() = runBlocking<Unit> {
        val graph = context.daybook; graph.initialized.await()
        val note = Entry(id = "future-source", day = "2026-10-18", originalText = "I plan to go to the gym tomorrow. I have not gone today.")
        val input = ReviewInput(note.day, listOf(note), listOf(Goal(id = "gym", title = "Go to the gym")), emptyList())
        val result = LocalCleanupClient(graph.localModel.file, graph.localModel::ready).generate(input)
        assertTrue(result.suggestions.isEmpty())
        assertTrue(result.journal.contains("tomorrow", true))
    }
    @Test fun b_actualOfflineCleanupKeepsOriginalsAndCoexistsWithWhisper() = runBlocking<Unit> {
        val graph = context.daybook; graph.initialized.await()
        assertTrue(graph.localModel.ready())
        val previous = graph.repository.read(); val settings = graph.preferences.read()
        val entry = Entry(id = "offline-source", day = "2026-10-18", originalText = "I went to the gym this morning. I want to learn Italian. Tomorrow I need to book a dentist appointment. Maybe I could try pottery.")
        val goal = Goal(id = "gym", title = "Go to the gym")
        val fixture = File(context.cacheDir, "llm-speech-test.wav")
        instrumentation.context.assets.open("jfk.wav").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        try {
            graph.repository.change { Snapshot(entries = listOf(entry), goals = listOf(goal)) }
            graph.saveSettings(Settings(onboarded = true, reviewProvider = "local", cloudConsent = false, autoCleanup = false, reminders = false))
            shell("cmd connectivity airplane-mode enable"); shell("svc wifi disable"); shell("svc data disable")
            val network = context.getSystemService(ConnectivityManager::class.java)
            repeat(20) { if (network.activeNetwork != null) delay(250) }
            assertNull(network.activeNetwork)
            val start = System.currentTimeMillis()
            val result = TestListenableWorkerBuilder<CleanupWorker>(context)
                .setInputData(workDataOf("day" to entry.day, "force" to true, "provider" to "local")).build().doWork()
            val state = graph.repository.read()
            File(context.getExternalFilesDir("verification"), "local-review-result.txt").apply { parentFile!!.mkdirs() }
                .writeText("Qwen2.5-1.5B Q4_K_M; airplane mode; ${System.currentTimeMillis() - start} ms\n$result\n${LocalEngine.stats.value}\n${com.google.gson.Gson().toJson(state)}")
            assertTrue(result.toString().contains("Success"))
            assertEquals(entry, state.entries.single())
            assertEquals("local", state.reviews.single().provider)
            assertTrue(state.reviews.single().journal.contains("gym", true))
            assertTrue(state.suggestions.any { it.goalId == goal.id && it.amount == 1.0 })
            assertTrue(state.proposals.any { it.item.kind == "goal" && it.item.title.contains("Italian", true) && it.item.period == "once" })
            assertTrue(state.proposals.any { it.item.kind == "todo" && it.item.title.contains("dentist", true) })
            assertTrue(state.proposals.any { it.item.kind == "idea" && it.item.title.contains("potter", true) })
            assertEquals(0, JournalRules.workedOn(state, entry.day))
            assertTrue(state.items.isEmpty())
            if (graph.model.ready()) assertTrue(WhisperEngine.transcribe(graph.model.file, fixture).contains("country", true))
            assertNull(network.activeNetwork)
        } finally {
            fixture.delete(); shell("cmd connectivity airplane-mode disable"); shell("svc wifi enable"); shell("svc data enable")
            graph.repository.change { previous }; graph.saveSettings(settings)
        }
    }
}
