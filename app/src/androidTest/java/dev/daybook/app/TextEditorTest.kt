package dev.daybook.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.app.ui.*
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.runtime.*

@RunWith(AndroidJUnit4::class)
class TextEditorTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph = compose.activity.daybook
        check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await(); graph.repository.change { Snapshot() }
        graph.preferences.save(Settings(onboarded = true, reminders = false, autoCleanup = false))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Your day, captured.").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun field(label: String = "What is on your mind?") = compose.onNode(hasSetTextAction() and (hasText(label) or hasContentDescription(label)))
    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("textScreenshots") != "true") return
        // Android window/IME rotation animations are outside Compose's idle clock.
        Thread.sleep(650)
        compose.waitForIdle()
        val file = File(compose.activity.getExternalFilesDir(null), "screenshots/$name.png").apply { parentFile!!.mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun newNoteKeepsMultilineDraftAcrossRecreationAndRotationWithSaveAboveKeyboard() = runBlocking<Unit> {
        val text = "I made time for a walk today.\n\nA small win: I cooked dinner instead of ordering in.\n\nTomorrow, I want to keep a little space for reading."
        compose.onNodeWithText("Write", substring = false).performClick()
        compose.onNodeWithText("Save note").assertIsNotEnabled()
        field().performTextInput(text)
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Hide keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Unsaved draft").assertIsDisplayed()
        compose.onNodeWithText("Save note").assertIsDisplayed().assertIsEnabled()
        shot("34-writing-note")
        compose.activityRule.scenario.recreate()
        field().assertTextContains(text)
        try {
            compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            compose.waitUntil(15000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            compose.waitUntil(10000) { compose.onAllNodesWithText("Unsaved draft").fetchSemanticsNodes().isEmpty() }
            field().assertTextContains(text).assertIsDisplayed().assertIsFocused()
            field().performTextInput(" A little more.")
            compose.onNodeWithText("Save note").assertIsDisplayed().assertIsEnabled()
            shot("35-writing-landscape")
            compose.onNodeWithText("Save note").performClick()
            compose.waitUntil(10000) { compose.activity.daybook.state.value.entries.any { it.text == text + " A little more." } }
            val entries = compose.activity.daybook.repository.read().entries
            assertEquals(1, entries.size); assertEquals(text + " A little more.", entries.single().originalText)
        } finally { compose.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
    }
    @Test fun closingAsksBeforeDiscardAndKeepWritingRetainsTheDraft() {
        compose.onNodeWithText("Write", substring = false).performClick()
        field().performTextInput("Keep this unsaved thought.")
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Hide keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Hide keyboard").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Show keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Show keyboard").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Hide keyboard").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Close note").performClick()
        compose.onNodeWithText("Discard this draft?").assertIsDisplayed()
        compose.onNodeWithText("Keep writing").performClick()
        field().assertTextContains("Keep this unsaved thought.")
        compose.onNodeWithContentDescription("Close note").performClick()
        compose.onNodeWithText("Discard draft", substring = false).performClick()
        compose.onNodeWithText("Your day, captured.").assertIsDisplayed()
        assertTrue(compose.activity.daybook.state.value.entries.isEmpty())
    }
    @Test fun editingSavedNoteUsesTheRoomyEditorAndPreservesOriginals() = runBlocking<Unit> {
        val graph = compose.activity.daybook
        val entry = graph.repository.addText(LocalDate.now().toString(), "An original journal note.")
        compose.waitUntil(10000) { graph.state.value.entries.any { it.id == entry.id } }
        compose.onNodeWithText("An original journal note.").performScrollTo().performClick()
        compose.onNodeWithText("Edit note", substring = false).performScrollTo().performClick()
        field("Note text").performTextReplacement("A corrected journal note.\nWith room for another thought.")
        compose.onNodeWithText("Save changes").assertIsDisplayed().performClick()
        compose.waitUntil(10000) { graph.state.value.entries.first { it.id == entry.id }.text.startsWith("A corrected") }
        val after = graph.repository.read()
        assertEquals(entry.originalText, after.entries.single().originalText)
        assertTrue(after.revisions.any { it.entryId == entry.id && it.text == entry.text })
    }
    @Test fun saveFailureKeepsDraftAndRepeatedTapCannotDuplicateAnInFlightSave() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val finish = CompletableDeferred<Unit>()
        compose.activity.setContent {
            DaybookTheme(dark = true) {
                var open by remember { mutableStateOf(true) }
                if (open) JournalTextEditor(title = "Keep a moment", day = LocalDate.now().toString(), label = "What is on your mind?",
                    initialText = "", saveLabel = "Save note", onDismiss = { open = false }, onSave = {
                        if (calls.incrementAndGet() == 1) throw IllegalStateException("Storage busy. Please try again.")
                        finish.await(); open = false
                    })
                else androidx.compose.material3.Text("Saved successfully")
            }
        }
        field().performTextInput("A draft that must survive a save error.")
        compose.onNodeWithText("Save note").performClick()
        compose.onNodeWithText("Storage busy. Please try again.").assertIsDisplayed()
        field().assertTextContains("A draft that must survive a save error.")
        shot("36-writing-save-error-dark")
        compose.onNodeWithText("Save note").performClick()
        compose.onNodeWithText("Saving…", substring = false).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close note").assertIsNotEnabled()
        assertEquals(2, calls.get())
        finish.complete(Unit)
        compose.waitUntil(10000) { compose.onAllNodesWithText("Saved successfully").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, calls.get())
    }
}
