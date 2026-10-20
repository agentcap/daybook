package dev.daybook.app

import android.Manifest
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.daybook.app.capture.RecordingService
import dev.daybook.app.data.SupportDiagnostics
import dev.daybook.app.widget.CaptureIntents
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReleaseWorkflowTest {
    @get:Rule val permission = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Before fun setup() = runBlocking {
        val graph=compose.activity.daybook; check(compose.activity.packageName.endsWith(".verification"))
        graph.initialized.await(); graph.repository.change { Snapshot() }
        graph.preferences.save(Settings(onboarded=true,reminders=false,autoCleanup=false))
        compose.waitUntil(10000) { compose.onAllNodesWithText("Your day, captured.").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun recordingPausesWithoutAddingAudioAndBookmarksSurviveResume() = runBlocking {
        val graph=compose.activity.daybook
        try {
            CaptureIntents.pending(compose.activity,501,"record").send()
            compose.waitUntil(10000) { RecordingService.session.value.elapsedMs>500 }
            compose.onNodeWithText("Pause",substring=false).performClick()
            compose.waitUntil(10000) { RecordingService.session.value.phase=="paused" }
            val ms=RecordingService.session.value.elapsedMs
            val audio=graph.repository.read().attachments.first { it.entryId==RecordingService.session.value.entryId }
            val bytes=graph.media.file(audio.path).length()
            delay(500)
            assertEquals(ms,RecordingService.session.value.elapsedMs); assertEquals(bytes,graph.media.file(audio.path).length())
            compose.onNodeWithText("Mark · 0").performClick()
            compose.waitUntil(10000) { RecordingService.session.value.bookmarks==1 }
            assertEquals(ms,graph.repository.read().entries.single().bookmarks.single().ms)
            compose.onNodeWithText("Resume",substring=false).performClick()
            compose.waitUntil(10000) { RecordingService.session.value.phase=="recording" && RecordingService.session.value.elapsedMs>ms+300 }
            compose.onNode(hasText("Save recording") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil(15000) { !RecordingService.active.value }
            assertTrue(graph.media.file(audio.path).length()>bytes)
            assertEquals(1,graph.repository.read().entries.single().bookmarks.size)
        } finally { compose.activity.startService(Intent(compose.activity,RecordingService::class.java).setAction(RecordingService.STOP)) }
    }
    @Test fun recentlyDeletedRestoresOriginalThroughSettings() = runBlocking {
        val graph=compose.activity.daybook
        val e=graph.repository.addText(LocalDate.now().toString(),"Recover this original memory")
        graph.deleteEntry(e.id)
        compose.waitUntil(10000) { graph.state.value.entries.isEmpty() }
        assertEquals(e.originalText,graph.repository.read().entries.single().originalText)
        compose.onNodeWithText("Settings",substring=false).performClick()
        compose.onNodeWithText("Backups & storage").performScrollTo().performClick()
        compose.onNodeWithText("Recently Deleted",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("Recover this original memory").assertIsDisplayed()
        compose.onNodeWithText("Restore note").performClick()
        compose.waitUntil(10000) { graph.state.value.entries.any { it.id==e.id } }
        assertEquals(e,graph.repository.read().entries.single())
    }
    @Test fun weeklyReflectionUsesConfirmedActivityAndPersistsPersonalReflection() = runBlocking {
        val graph=compose.activity.daybook; val day=LocalDate.now().toString()
        val g=Goal(title="Gym"); val e=Entry(day=day,originalText="I might go tomorrow.")
        graph.repository.change { Snapshot(entries=listOf(e),goals=listOf(g),activities=listOf(Activity(goalId=g.id,day=day,note="Partial effort"))) }
        compose.onNodeWithText("History",substring=false).performClick()
        compose.onNodeWithText("Weekly reflection").performClick()
        compose.onNodeWithText("1 distinct goal worked on · 1 goal/day check-in").assertIsDisplayed()
        compose.onNodeWithText("Your weekly reflection").performScrollTo().performTextInput("Keep making time for rest.")
        compose.onNodeWithText("Save weekly reflection").performScrollTo().performClick()
        compose.waitUntil(10000) { graph.state.value.reflections.singleOrNull()?.text=="Keep making time for rest." }
        assertEquals(0.0,JournalRules.progress(graph.repository.read(),g,LocalDate.now()),0.0)
    }
    @Test fun cloudPreviewOmitsPrivateNotesAndDiagnosticsExcludeContent() = runBlocking {
        val graph=compose.activity.daybook; val day=LocalDate.now().toString()
        graph.repository.change { Snapshot(entries=listOf(Entry(day=day,originalText="Public journal fixture"),Entry(day=day,originalText="TOP SECRET JOURNAL",keepLocal=true))) }
        compose.waitUntil(10000) { graph.state.value.entries.size==2 }
        compose.onNodeWithText("Create daily reflection").performScrollTo().performClick()
        compose.onNodeWithText("Create a reflection with OpenAI?").assertIsDisplayed()
        compose.onNode(hasText("Public journal fixture") and hasAnyAncestor(isDialog())).assertExists()
        compose.onNode(hasText("TOP SECRET JOURNAL") and hasAnyAncestor(isDialog())).assertDoesNotExist()
        val report=SupportDiagnostics.text(compose.activity,graph)
        assertFalse(report.contains("TOP SECRET")); assertFalse(report.contains("Public journal"))
        assertFalse(report.contains("files/")); assertFalse(report.contains("key",ignoreCase=true))
    }
    @Test fun privateEntrySwitchHasAccessibleLabelAndSavesImmediately() = runBlocking {
        val graph=compose.activity.daybook; val e=graph.repository.addText(LocalDate.now().toString(),"Private switch fixture")
        compose.runOnUiThread { compose.activity.setContent { dev.daybook.app.ui.DaybookTheme(false) {
            dev.daybook.app.ui.EntryDialog(e,Snapshot(entries=listOf(e)),graph,{},{},{},{})
        } } }
        compose.onNode(hasText("Keep this entry on-device") and isToggleable()).performScrollTo().performClick()
        withTimeout(10000) { while (!graph.repository.read().entries.single().keepLocal) delay(50) }
    }
}
