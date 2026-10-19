package dev.daybook.app.ai

import android.content.Context
import androidx.work.*
import dev.daybook.app.daybook
import dev.daybook.app.data.MediaStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class LocalModel(private val context: Context) {
    val directory = File(context.filesDir, "models/review")
    val file = File(directory, "qwen2.5-1.5b-q4_k_m.gguf")
    val partial = File(directory, "download.partial")
    private val marker get() = File(directory, "verified")
    internal val files = Mutex()
    fun ready() = runCatching { file.isFile && file.length() == SIZE && marker.isFile && marker.readText() == SHA }.getOrDefault(false)
    fun usage() = directory.listFiles()?.sumOf { it.length() } ?: 0L
    fun download() {
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<LocalModelDownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun pause() = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    suspend fun delete() = withContext(Dispatchers.IO) {
        pause().result.get(10, TimeUnit.SECONDS)
        files.withLock { marker.delete(); file.delete(); partial.delete() }
        Unit
    }
    companion object {
        const val WORK = "local-review-model"
        const val SHA = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e"
        const val SIZE = 1117320736L
        const val URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf"
    }
}
class LocalModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = applicationContext.daybook.localModel
        if (model.ready()) return@withContext Result.success()
        model.directory.mkdirs()
        val temp = model.partial
        try {
            var offset = if (temp.isFile) temp.length() else 0L
            if (offset > LocalModel.SIZE) { temp.delete(); offset = 0 }
            check(model.directory.usableSpace > LocalModel.SIZE - offset + 64L * 1024 * 1024) { "Not enough free space. Free up about 1.2 GB, then resume the model download." }
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(LocalModel.URL).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416) { temp.delete(); return@withContext Result.retry() }
                check(response.isSuccessful) { "Review model download failed (HTTP ${response.code})." }
                val append = response.code == 206 && offset > 0
                if (append) check(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "Invalid resumed download." }
                var total = if (append) offset else 0L; var reported = total
                check(model.directory.usableSpace > LocalModel.SIZE - total + 64L * 1024 * 1024) { "Not enough free space to download the review model." }
                response.body!!.byteStream().use { input -> FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        ensureActive(); if (isStopped) throw CancellationException("Download paused")
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= LocalModel.SIZE) { "Unexpected review model size." }; output.write(buffer, 0, n)
                        if (total - reported >= 4 * 1024 * 1024) {
                            setProgress(workDataOf("percent" to (total * 100 / LocalModel.SIZE).toInt())); reported = total
                        }
                    }
                } }
            }
            setProgress(workDataOf("percent" to 100, "verifying" to true))
            if (temp.length() != LocalModel.SIZE || MediaStore.digest(temp) != LocalModel.SHA) {
                temp.delete()
                error("Review model checksum mismatch. Download it again.")
            }
            model.files.withLock {
                ensureActive(); check(!isStopped)
                check(temp.renameTo(model.file)) { "Could not install the on-device review model." }
                File(model.directory, "verified").writeText(LocalModel.SHA)
            }
            Result.success()
        } catch (e: CancellationException) { throw e
        } catch (e: java.io.IOException) {
            if (runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf("error" to "Download interrupted. Tap Download to resume."))
        } catch (e: Exception) {
            // Keep valid partial bytes for resumable failures such as low storage.
            if (temp.length() > LocalModel.SIZE) temp.delete()
            Result.failure(workDataOf("error" to (e.message ?: "Review model download failed.").take(500)))
        }
    }
}
