package dev.daybook.app.capture
import android.content.Context
import androidx.work.*
import dev.daybook.app.daybook
import dev.daybook.app.data.MediaStore
import dev.daybook.core.Revision
import dev.daybook.core.TranscriptSegment
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile

interface SpeechTranscriber { suspend fun transcribe(model: File, recording: File): String }
class WhisperControl(private val job: Job) { fun isCancelled() = !job.isActive }
object WhisperEngine : SpeechTranscriber {
    private val lock = Mutex()
    init { System.loadLibrary("daybook_speech") }
    private external fun openPreviewNative(model: String): Long
    private external fun previewNative(handle: Long, samples: FloatArray): String
    private external fun closePreviewNative(handle: Long)
    suspend fun openPreview(model: File): PreviewSession = lock.withLock { PreviewSession(openPreviewNative(model.absolutePath)) }
    class PreviewSession(private var handle: Long) {
        private val sessionLock = Mutex()
        suspend fun transcribe(samples: FloatArray): String = lock.withLock { sessionLock.withLock {
            check(handle != 0L); previewNative(handle, samples).trim()
        } }
        suspend fun close() = sessionLock.withLock { if (handle != 0L) { closePreviewNative(handle); handle = 0 } }
    }
    private external fun timelineNative(model: String, recording: String, control: WhisperControl): Array<String>
    suspend fun timeline(model: File, recording: File): List<TranscriptSegment> = lock.withLock {
        withContext(Dispatchers.IO) {
            val output = try { timelineNative(model.absolutePath, recording.absolutePath, WhisperControl(currentCoroutineContext().job)) }
            catch (e: Exception) { ensureActive(); throw e }
            ensureActive()
            output.map { row -> val values = row.split('\t', limit = 3)
                require(values.size == 3)
                TranscriptSegment(values[0].toLong(), values[1].toLong(), values[2].trim())
            }.filter { it.text.isNotBlank() }
        }
    }
    private external fun transcribeNative(model: String, recording: String): String
    override suspend fun transcribe(model: File, recording: File): String = lock.withLock {
        withContext(Dispatchers.IO) { transcribeNative(model.absolutePath, recording.absolutePath).trim() }
    }
}
class Transcriptions(private val context: Context) {
    fun enqueue(entryId: String, replace: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork("transcribe-$entryId", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TranscriptionWorker>().addTag("transcription").setInputData(workDataOf("entryId" to entryId))
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build())
    }
    fun cancel(entryId: String) = WorkManager.getInstance(context).cancelUniqueWork("transcribe-$entryId")
}
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = applicationContext.daybook
        val id = inputData.getString("entryId") ?: return@withContext Result.failure()
        val state = graph.repository.read()
        val entry = state.entries.find { it.id == id } ?: return@withContext Result.success()
        if (entry.deletedAt > 0 || entry.status == "ready" || entry.status == "recording") return@withContext Result.success()
        var expectedPath: String? = null
        try {
            check(graph.model.ready()) { "Download the offline speech model in Settings, then retry. Your recording is saved." }
            val attachment = state.attachments.first { it.entryId == id && it.kind == "audio" }
            expectedPath = attachment.path
            val file = graph.media.file(attachment.path)
            Wav.repair(file)
            setProgress(workDataOf("message" to "Transcribing on this phone…"))
            val segments = WhisperEngine.timeline(graph.model.file, file)
            val text = segments.joinToString(" ") { it.text }
            check(text.isNotBlank()) { "No speech was detected. Replay your audio, edit the note, or retry transcription." }
            if (isStopped) return@withContext Result.failure()
            graph.repository.change { s -> if (s.attachments.none { it.id == attachment.id && it.path == attachment.path }) s else s.copy(
                revisions = s.revisions + s.entries.filter { it.id == id && it.deletedAt == 0L && it.status == "pending" && it.text.isNotBlank() && it.text != text }.map { Revision(entryId = id, text = it.text) },
                entries = s.entries.map { if (it.id == id && it.status != "ready") it.copy(originalText = text, text = text, status = "ready", error = "", segments = segments) else it },
                attachments = s.attachments.map { if (it.id == attachment.id) it.copy(bytes = file.length(), digest = MediaStore.digest(file)) else it }) }
            Result.success()
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            graph.repository.change { s -> s.copy(entries = s.entries.map {
                if (it.id == id && it.status != "ready") it.copy(status = "error", error = (e.message ?: "Offline transcription failed. Audio is preserved.").take(500)) else it
            }) }
            Result.failure(workDataOf("error" to (e.message ?: "Transcription failed.").take(500)))
        }
    }
}
object Wav {
    fun header(file: RandomAccessFile, bytes: Long) {
        fun short(value: Int) { file.write(value and 255); file.write(value shr 8 and 255) }
        fun int(value: Long) { repeat(4) { file.write((value shr (it * 8)).toInt() and 255) } }
        file.seek(0); file.writeBytes("RIFF"); int(bytes + 36); file.writeBytes("WAVEfmt "); int(16); short(1); short(1)
        int(16000); int(32000); short(2); short(16); file.writeBytes("data"); int(bytes); file.seek(bytes + 44)
    }
    fun repair(file: File) {
        require(file.isFile && file.length() > 44) { "The recording contains no saved audio." }
        RandomAccessFile(file, "rw").use { val bytes = (it.length() - 44).let { n -> n - n % 2 }; it.setLength(bytes + 44); header(it, bytes) }
    }
}
