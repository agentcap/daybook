package dev.daybook.app.capture

import android.content.Context
import androidx.work.*
import dev.daybook.app.daybook
import dev.daybook.app.data.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class SpeechModel(private val context: Context) {
    val file = File(context.filesDir, "models/ggml-base.en.bin")
    fun ready() = file.isFile && file.length() == SIZE && File(file.parentFile, "verified").readTextOrEmpty() == SHA
    fun download(wifiOnly: Boolean = context.daybook.settings.value.wifiOnlyDownloads) {
        val constraints = Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()
        WorkManager.getInstance(context).enqueueUniqueWork("speech-model", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>().setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancel() = WorkManager.getInstance(context).cancelUniqueWork("speech-model")
    fun delete() { cancel(); file.delete(); File(file.parentFile, "verified").delete() }
    companion object {
        const val SHA = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"
        const val SIZE = 147964211L
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en.bin"
    }
}
private fun File.readTextOrEmpty() = if (isFile) readText() else ""
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val model = applicationContext.daybook.model
        if (model.ready()) return@withContext Result.success()
        model.file.parentFile!!.mkdirs()
        val temp = File(model.file.parentFile, "download.partial")
        try {
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            var offset = if (temp.isFile) temp.length() else 0
            if (offset > SpeechModel.SIZE) { temp.delete(); offset = 0 }
            val request = Request.Builder().url(SpeechModel.URL).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            client.newCall(request).execute().use { response ->
                if (response.code == 416) { temp.delete(); return@withContext Result.retry() }
                check(response.isSuccessful) { "Speech model download failed (HTTP ${response.code})." }
                val append = response.code == 206 && offset > 0
                if (append) check(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "Invalid resumed download." }
                var total = if (append) offset else 0L
                response.body!!.byteStream().use { input ->
                    temp.outputStream(append).use { output ->
                        val buffer = ByteArray(65536); var reported = total
                        while (true) {
                            if (isStopped) return@withContext Result.failure(workDataOf("error" to "Download paused. Tap Download to resume."))
                            val n = input.read(buffer); if (n == -1) break
                            total += n; check(total <= SpeechModel.SIZE) { "Unexpected model size." }; output.write(buffer, 0, n)
                            if (total - reported >= 2 * 1024 * 1024) { setProgress(workDataOf("percent" to (total * 100 / SpeechModel.SIZE).toInt())); reported = total }
                        }
                    }
                }
            }
            check(temp.length() == SpeechModel.SIZE && MediaStore.digest(temp) == SpeechModel.SHA) { "Speech model checksum mismatch. Retry the download." }
            check(temp.renameTo(model.file)) { "Could not install speech model." }
            File(model.file.parentFile, "verified").writeText(SpeechModel.SHA)
            applicationContext.daybook.repository.read().entries.filter { it.kind == "voice" && it.status in setOf("pending", "error", "interrupted") }
                .forEach { applicationContext.daybook.transcriptions.enqueue(it.id) }
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: java.io.IOException) {
            if (runAttemptCount < 4) Result.retry() else Result.failure(workDataOf("error" to "Download interrupted. Tap Download to resume."))
        } catch (e: Exception) {
            temp.delete()
            Result.failure(workDataOf("error" to (e.message ?: "Model download failed.").take(500)))
        }
    }
}
private fun File.outputStream(append: Boolean) = java.io.FileOutputStream(this, append)
