package dev.daybook.core

import com.google.gson.Gson
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class ReleaseFeaturesTest {
    private val day = "2026-10-19"
    @Test fun legacyEntriesAndSettingsHaveSafeDefaults() {
        val e = Gson().fromJson("""{"id":"old","day":"2026-10-19","text":"hello","originalText":"hello","kind":"text","status":"ready","zone":"UTC","created":1}""", Entry::class.java)
        assertFalse(e.keepLocal); assertTrue(e.includePhotosInCloud); assertEquals(emptyList<AudioBookmark>(), e.bookmarks)
        val s = Gson().fromJson("""{"onboarded":true}""", Settings::class.java)
        assertTrue(s.wifiOnlyDownloads); assertFalse(s.cleanupChargingOnly)
    }
    @Test fun trashHidesSourcesWithoutLosingOriginalsAndRestoreRecoversThem() {
        val e = Entry(day = day, originalText = "original")
        val r = Review(day = day, sourceIds = listOf(e.id), journal = "private review")
        val g = Goal(title = "Gym")
        val s = Snapshot(entries = listOf(e), reviews = listOf(r), goals = listOf(g),
            activities = listOf(Activity(goalId = g.id, day = day, amount = 1.0, sources = listOf(e.id))))
        val deleted = RecoveryRules.trash(s,e.id,100)
        assertEquals(e.originalText, deleted.entries.single().originalText)
        val visible = RecoveryRules.visible(deleted)
        assertTrue(visible.entries.isEmpty()); assertTrue(visible.reviews.isEmpty())
        assertEquals(1, JournalRules.workedOn(visible, day))
        assertEquals(emptyList<String>(), visible.activities.single().sources)
        assertEquals(s, RecoveryRules.restore(deleted,e.id))
        assertTrue(RecoveryRules.expired(deleted,99 + RecoveryRules.RETENTION_MS).isEmpty())
        assertEquals(listOf(e.id),RecoveryRules.expired(deleted,100 + RecoveryRules.RETENTION_MS))
    }
    @Test fun cannotTrashAnActiveRecordingAndFingerprintChangesOnPrivacyChoice() {
        val e=Entry(day=day,status="recording")
        assertThrows(IllegalArgumentException::class.java) { RecoveryRules.trash(Snapshot(entries=listOf(e)),e.id) }
        val s=Snapshot(entries=listOf(e.copy(status="ready")))
        assertNotEquals(JournalRules.fingerprint(s,day),JournalRules.fingerprint(s.copy(entries=listOf(s.entries.single().copy(keepLocal=true))),day))
    }
    @Test fun cloudExcludesPrivateAndTrashedEntriesAndTheirDerivedContext() {
        val a=Entry(day=day,keepLocal=true); val b=Entry(day=day); val c=Entry(day=day,deletedAt=1)
        val g=Goal(title="Public")
        val s=Snapshot(entries=listOf(a,b,c),goals=listOf(g),
            activities=listOf(Activity(goalId=g.id,day=day,sources=listOf(a.id)),Activity(goalId=g.id,day=day)),
            items=listOf(ListItem(kind="todo",title="Private",sources=listOf(a.id)),ListItem(kind="idea",title="Public")))
        assertEquals(listOf(b.id),ReviewPrivacy.entries(s,day,"openai").map { it.id })
        assertEquals(setOf(a.id,b.id),ReviewPrivacy.entries(s,day,"local").map { it.id }.toSet())
        val cloud=ReviewPrivacy.cloudContext(s)
        assertEquals(1,cloud.activities.size); assertEquals("Public",cloud.items.single().title)
    }
    @Test fun weeklyCountsDistinctConfirmedGoalDaysAndExcludesFutureAndTrash() {
        val monday=LocalDate.parse(day); val g=Goal(title="Gym",period="weekly",target=3.0)
        val s=Snapshot(entries=listOf(Entry(day=day),Entry(day=day,deletedAt=1),Entry(day=monday.plusDays(1).toString())),
            goals=listOf(g),activities=listOf(
                Activity(goalId=g.id,day=day,amount=0.0,note="partial"),
                Activity(goalId=g.id,day=day,amount=1.0),
                Activity(goalId=g.id,day=day,amount=10.0,undone=true),
                Activity(goalId=g.id,day=monday.plusDays(1).toString(),amount=1.0)))
        val digest=WeeklyRules.digest(s,monday,monday)
        assertEquals(1,digest.journalDays); assertEquals(1,digest.entries.size)
        assertEquals(1,digest.goals.size); assertEquals(1,digest.goals.single().workedDays)
        assertEquals(1.0,digest.goals.single().amount,0.0); assertEquals(1,digest.checkins)
    }
    @Test fun newMetadataAndWeeklyReflectionsSurviveJsonAndValidation() {
        val e=Entry(day=day,bookmarks=listOf(AudioBookmark(ms=3000)),segments=listOf(TranscriptSegment(1000,2000,"hello")))
        val s=Snapshot(entries=listOf(e),reflections=listOf(WeekReflection(week=day,text="A good week")))
        val copy=Gson().fromJson(Gson().toJson(s),Snapshot::class.java)
        JournalRules.validateSnapshot(copy); assertEquals(s,copy)
        assertThrows(IllegalArgumentException::class.java) { JournalRules.validateSnapshot(s.copy(entries=listOf(e.copy(segments=listOf(TranscriptSegment(2,1,"bad")))))) }
    }

    @Test fun photoExclusionsApplyToCloudPreviewAndWorker() {
        val a=Entry(day=day,includePhotosInCloud=false)
        val b=Entry(day=day); val c=Entry(day=day,keepLocal=true); val d=Entry(day=day,deletedAt=1)
        fun photo(e: Entry)=Attachment(entryId=e.id,path="media/"+e.id+".jpg",kind="photo",bytes=1)
        val s=Snapshot(entries=listOf(a,b,c,d),attachments=listOf(a,b,c,d).map(::photo))
        assertEquals(listOf(b.id),ReviewPrivacy.photos(s,day).map { it.entryId })
    }
}
