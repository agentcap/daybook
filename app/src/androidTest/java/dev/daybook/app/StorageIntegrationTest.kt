package dev.daybook.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.app.data.*
import dev.daybook.app.capture.Wav
import dev.daybook.core.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.util.zip.*

@RunWith(AndroidJUnit4::class)
class StorageIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: JournalDatabase
    private lateinit var repo: JournalRepository
    private lateinit var media: MediaStore
    private lateinit var backups: Backups
    private val files = mutableListOf<File>()
    private val password = "synthetic backup phrase".toCharArray()
    @Before fun setup() {
        check(context.packageName.endsWith(".verification")) { "Tests require the isolated verification package." }
        db = JournalDatabase.open(context, "test-${newId()}.sqlite")
        repo = JournalRepository(db); media = MediaStore(context); backups = Backups(context, repo, Preferences(context), media)
    }
    @After fun close() = runBlocking {
        repo.read().attachments.forEach { media.file(it.path).delete() }
        db.close(); context.deleteDatabase(db.openHelper.databaseName)
        files.forEach { it.delete() }
    }
    private fun temp(ext: String) = File(context.cacheDir, "${newId()}.$ext").also { files += it }
    @Test fun originalsPhotosGoalsAndReviewsSurviveEncryptedBackup() = runBlocking {
        val e = repo.addText("2026-10-18", "Raw unedited dinner note.")
        repo.editEntry(e.id, "I ate dinner.")
        val goal = Goal(title = "Calories", target = 3000.0, unit = "kcal", checklist = false)
        repo.addGoal(goal); repo.logActivity(Activity(goalId = goal.id, day = e.day, amount = 600.0, note = "Dinner"))
        val source = temp("jpg")
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        media.importPhoto(Uri.fromFile(source), e.day, repo)
        source.delete()
        val photo = repo.read().attachments.single()
        assertTrue(media.file(photo.path).isFile)
        repo.change { state ->
            val review = Review(day = e.day, fingerprint = JournalRules.fingerprint(state, e.day), journal = "I ate dinner.",
                summary = "Dinner.", captions = listOf(PhotoCaption(photo.id, "Synthetic photo.")), model = "test")
            state.copy(reviews = listOf(review), suggestions = listOf(Suggestion(reviewId = review.id, goalId = goal.id,
                day = e.day, amount = 600.0, note = "Dinner estimate", sources = listOf(e.id), estimated = true)))
        }
        repo.change { state ->
            val proposed = ProposedItem("todo", "Book a cooking class", "Call tomorrow.", listOf(e.id, photo.entryId))
            val staged = PlannerRules.proposals(state, state.reviews.single(), listOf(proposed))
            PlannerRules.approve(staged, staged.proposals.single().id, proposed.title, proposed.details)
        }
        val before = repo.read()
        val backup = temp("daybook")
        backups.export(Uri.fromFile(backup), password.clone())
        repo.change { Snapshot() }
        backups.restore(Uri.fromFile(backup), password.clone())
        val after = repo.read()
        assertEquals(before.entries, after.entries)
        assertEquals("Raw unedited dinner note.", after.entries.first { it.id == e.id }.originalText)
        assertEquals("I ate dinner.", after.entries.first { it.id == e.id }.text)
        assertEquals(before.revisions, after.revisions)
        assertEquals(before.activities, after.activities)
        assertEquals(before.items, after.items)
        assertEquals(before.proposals.single().createdId, after.proposals.single().createdId)
        assertTrue(PlannerRules.current(after, after.proposals.single()))
        assertEquals("I ate dinner.", after.reviews.single().journal)
        assertEquals(JournalRules.fingerprint(after, e.day), after.reviews.single().fingerprint)
        assertEquals(before.suggestions, after.suggestions)
        assertEquals(1, JournalRules.workedOn(after, e.day))
        assertTrue(media.file(after.attachments.single().path).isFile)
        val archive = ArchiveCrypto.decrypt(backup.inputStream(), password).use { it.readBytes() }
        ZipInputStream(archive.inputStream()).use { zip ->
            val names = mutableListOf<String>()
            while (true) { val item = zip.nextEntry ?: break; names += item.name; zip.closeEntry() }
            assertEquals(setOf("bundle.json", photo.path), names.toSet())
            assertFalse(names.any { "key" in it || "model" in it })
        }
    }
    @Test fun wrongPasswordAndMaliciousPathsLeaveCurrentJournalUntouched() = runBlocking {
        val e = repo.addText("2026-10-18", "Keep this note.")
        val backup = temp("daybook")
        backups.export(Uri.fromFile(backup), password.clone())
        try { backups.restore(Uri.fromFile(backup), "wrong password".toCharArray()); fail("Expected restore failure") } catch (_: IllegalArgumentException) { }
        assertEquals(e, repo.read().entries.single())
        val malicious = temp("daybook")
        ZipOutputStream(ArchiveCrypto.encrypt(malicious.outputStream(), password)).use { zip ->
            zip.putNextEntry(ZipEntry("../escape")); zip.write("bad".toByteArray()); zip.closeEntry()
        }
        try { backups.restore(Uri.fromFile(malicious), password.clone()); fail("Expected unsafe path rejection") } catch (_: Exception) { }
        assertEquals(e, repo.read().entries.single())
    }
    @Test fun wavCheckpointRecoveryPreservesCapturedBytes() {
        val file = temp("wav")
        RandomAccessFile(file, "rw").use { out -> Wav.header(out, 0); out.write(ByteArray(16000) { 42 }) }
        Wav.repair(file)
        assertEquals(16044L, file.length())
        RandomAccessFile(file, "r").use { input -> input.seek(40); assertEquals(16000, Integer.reverseBytes(input.readInt())); assertEquals(42, input.read()) }
    }
}
