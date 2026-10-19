package dev.daybook.app

import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.daybook.app.capture.Wav
import dev.daybook.app.data.*
import dev.daybook.core.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** Uses a folder explicitly granted through the real DocumentsUI picker on the disposable emulator. */
@RunWith(AndroidJUnit4::class)
class RealBackupTest {
    @Test fun scheduledFolderBackupRoundTripsMediaMetadataAndHandlesLostSecret() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("realBackup")=="true")
        val context=instrumentation.targetContext
        require(context.packageName.endsWith(".verification") && Build.MODEL.contains("sdk"))
        val graph=context.daybook; graph.initialized.await()
        val config=graph.backupJobs.read(); require(config.enabled && config.folder.isNotBlank()) { "Configure automatic backups in the verification app first." }
        val pass=graph.backupJobs.secret.read()
        val day=LocalDate.now().toString()
        val audio=graph.media.file(graph.media.newPath("wav"))
        RandomAccessFile(audio,"rw").use { it.setLength(3244); Wav.header(it,3200) }
        val e=Entry(day=day,kind="voice",originalText="Original backup fixture",keepLocal=true,
            bookmarks=listOf(AudioBookmark(ms=20)),segments=listOf(TranscriptSegment(0,80,"fixture")))
        val trashed=Entry(day=day,originalText="Recoverable trash",deletedAt=System.currentTimeMillis())
        val a=Attachment(entryId=e.id,path="media/"+audio.name,kind="audio",bytes=audio.length(),digest=MediaStore.digest(audio))
        val photoEntry=Entry(day=day,kind="photo",originalText="Photo backup fixture",includePhotosInCloud=false)
        val photo=graph.media.file(graph.media.newPath("png"))
        android.graphics.Bitmap.createBitmap(8,8,android.graphics.Bitmap.Config.ARGB_8888).also { image ->
            image.eraseColor(android.graphics.Color.BLUE); photo.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        }
        val photoAttachment=Attachment(entryId=photoEntry.id,path="media/"+photo.name,kind="photo",bytes=photo.length(),digest=MediaStore.digest(photo))
        val reflection=WeekReflection(week=WeeklyRules.start(LocalDate.now()).toString(),text="Saved weekly fixture")
        try {
            graph.repository.change { Snapshot(entries=listOf(e,trashed,photoEntry),attachments=listOf(a,photoAttachment),reflections=listOf(reflection)) }
            assertTrue(graph.backupJobs.runOnce())
            val saved=graph.backupJobs.read(); assertTrue(saved.lastSuccess>0); assertTrue(saved.owned.isNotEmpty())
            val uri=Uri.parse(saved.owned.last())
            val names=mutableListOf<String>(); var manifest=""
            context.contentResolver.openInputStream(uri)!!.use { input -> ZipInputStream(ArchiveCrypto.decrypt(input,pass.clone())).use { zip ->
                while(true) { val item=zip.nextEntry ?: break; names+=item.name; if(item.name=="bundle.json") manifest=zip.readBytes().toString(Charsets.UTF_8); zip.closeEntry() }
            } }
            assertEquals(setOf("bundle.json",a.path,photoAttachment.path),names.toSet())
            assertFalse(manifest.contains(pass.concatToString())); assertFalse(manifest.contains(config.folder)); assertFalse(manifest.contains("ciphertext"))
            graph.repository.change { Snapshot() }
            graph.backup.restore(uri,pass.clone())
            val restored=graph.repository.read()
            assertEquals(listOf(e,trashed,photoEntry),restored.entries); assertEquals(listOf(reflection),restored.reflections)
            restored.attachments.forEach { assertEquals(it.digest,MediaStore.digest(graph.media.file(it.path))) }
            val before=restored
            assertThrows(IllegalArgumentException::class.java) { runBlocking { graph.backup.restore(uri,"wrong password".toCharArray()) } }
            assertEquals(before,graph.repository.read())
            repeat(7) { assertTrue(graph.backupJobs.runOnce()) }
            val retained=graph.backupJobs.read()
            assertEquals(7,retained.owned.size); assertFalse(retained.owned.contains(uri.toString()))
            val lastGood=Uri.parse(retained.owned.last())
            context.contentResolver.openInputStream(lastGood)!!.use { input -> ArchiveCrypto.decrypt(input,pass.clone()).use { it.readBytes() } }
            graph.backupJobs.secret.clear()
            assertFalse(graph.backupJobs.runOnce())
            assertEquals(retained.lastSuccess,graph.backupJobs.read().lastSuccess)
            assertTrue(graph.backupJobs.read().lastError.isNotBlank())
        } finally { pass.fill('\u0000'); graph.backupJobs.disable(); audio.delete(); photo.delete() }
    }
}
