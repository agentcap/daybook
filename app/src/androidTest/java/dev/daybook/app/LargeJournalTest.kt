package dev.daybook.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LargeJournalTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun largeJournalLoadsSearchesAndOpensWeeklyReflection() = runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("largeJournal")=="true")
        val graph=compose.activity.daybook; check(compose.activity.packageName.endsWith(".verification")); graph.initialized.await()
        graph.preferences.save(Settings(onboarded=true,reminders=false,autoCleanup=false))
        val entries=(0 until 1000).map { Entry(day=LocalDate.now().minusDays((it%180).toLong()).toString(),originalText="Journal fixture bench-"+"%04d".format(it)+" "+("A captured moment. ".repeat(15))) }
        val started=System.nanoTime()
        graph.repository.change { Snapshot(entries=entries) }
        compose.waitUntil(20000) { graph.state.value.entries.size==1000 }
        val loaded=(System.nanoTime()-started)/1000000
        compose.onNodeWithText("History",substring=false).performClick()
        compose.onNodeWithText("Search your journal").performTextInput("bench-0999")
        compose.waitUntil(10000) { compose.onAllNodesWithText("6 entries · 0 goals worked on").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open matching note").performScrollTo().performClick()
        compose.onNodeWithText("Your original moment").assertIsDisplayed()
        compose.onNodeWithText("Close",substring=false).performClick()
        compose.onNodeWithText("Weekly reflection").performScrollTo().performClick()
        compose.onNodeWithText("Your week").assertIsDisplayed()
        File(compose.activity.getExternalFilesDir("verification"),"large-journal.txt").apply { parentFile!!.mkdirs() }
            .writeText("1000 synthetic entries persisted and observed in $loaded ms; History search and weekly reflection passed. Emulator measurement only.\n")
    }
}
