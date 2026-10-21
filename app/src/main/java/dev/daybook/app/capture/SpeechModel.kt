package dev.daybook.app.capture

import android.content.Context
import androidx.work.*
import dev.daybook.app.daybook
import dev.daybook.app.data.MediaStore
import dev.daybook.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SpeechModel(private val context: Context) {
    // Keep this path and the original marker compatible with existing Whisper installs.
    val file = File(context.filesDir, "models/ggml-base.en.bin")
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun selectedId() = context.daybook.settings.value.speechModel
    fun directory(id: String): File = if (SpeechCatalog.get(id).id == SpeechCatalog.WHISPER) file.parentFile!!
        else File(context.filesDir, "models/speech/$id")
    fun marker(id: String) = File(directory(id), "verified")
    fun ready(id: String = selectedId()) = runCatching {
        val choice = SpeechCatalog.get(id)
        marker(id).isFile && marker(id).readText() == choice.verification &&
            choice.assets.all { val f = File(directory(id), it.name); f.isFile && f.length() == it.bytes }
    }.getOrDefault(false)
    fun usage(id: String): Long = SpeechCatalog.get(id).assets.sumOf {
        File(directory(id), it.name).length() + File(directory(id), it.name + ".partial").length()
    } + if (id == SpeechCatalog.WHISPER) File(directory(id), "download.partial").length() else 0L
    fun usage() = SpeechCatalog.choices.sumOf { usage(it.id) }
    internal suspend fun <T> files(id: String, action: suspend () -> T): T =
        locks.getOrPut(id) { Mutex() }.withLock { action() }
    fun download(wifiOnly: Boolean = context.daybook.settings.value.wifiOnlyDownloads) = download(selectedId(), wifiOnly)
    fun download(id: String, wifiOnly: Boolean = context.daybook.settings.value.wifiOnlyDownloads) {
        SpeechCatalog.get(id)
        val constraints = Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()
        WorkManager.getInstance(context).enqueueUniqueWork(SpeechCatalog.workName(id), ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ModelDownloadWorker>().setInputData(workDataOf("modelId" to id)).addTag("speech-model-download")
                .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
    fun cancel(id: String = selectedId()) = WorkManager.getInstance(context).cancelUniqueWork(SpeechCatalog.workName(id))
    suspend fun remove(id: String) {
        cancel(id).result.get()
        files(id) {
            marker(id).delete()
            SpeechCatalog.get(id).assets.forEach {
                check(File(directory(id), it.name).let { f -> !f.exists() || f.delete() }) { "Could not remove a speech model file." }
                File(directory(id), it.name + ".partial").delete()
            }
            if (id == SpeechCatalog.WHISPER) File(directory(id), "download.partial").delete()
        }
    }
    /** Legacy synchronous helper used by existing Whisper-only tooling. */
    fun delete() { cancel(SpeechCatalog.WHISPER); file.delete(); marker(SpeechCatalog.WHISPER).delete() }
    companion object {
        const val SHA = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"
        const val SIZE = 147964211L
        const val URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/5359861c739e955e79d9a303bcbc70fb988958b1/ggml-base.en.bin"
    }
}

/** Resumable file transfer. Size and SHA-256 are checked before a file becomes usable. */
internal class SpeechAssetTransfer(private val client: OkHttpClient) {
    suspend fun fetch(asset: SpeechAsset, target: File, partial: File, onBytes: suspend (Long) -> Unit) {
        if (target.isFile && target.length() == asset.bytes && MediaStore.digest(target) == asset.sha256) {
            onBytes(asset.bytes); return
        }
        target.parentFile!!.mkdirs()
        var offset = partial.length()
        if (offset > asset.bytes) { partial.delete(); offset = 0 }
        if (offset != asset.bytes) {
            val request = Request.Builder().url(asset.url).header("User-Agent", "Daybook/0.6 (Android offline speech models)")
                .apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            val call = client.newCall(request)
            val context = currentCoroutineContext()
            val cancel = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { call.cancel() }
            }
            try {
                call.execute().use { response ->
                    if (response.code == 416) { partial.delete(); throw java.io.IOException("Download range changed. Retrying.") }
                    check(response.isSuccessful) { "Speech model download failed (HTTP ${response.code})." }
                    val append = response.code == 206 && offset > 0
                    check(response.code != 206 || response.header("Content-Range")?.startsWith("bytes ${if (append) offset else 0}-") == true) { "Invalid resumed speech download." }
                    var total = if (append) offset else 0L
                    response.body!!.byteStream().use { input ->
                        FileOutputStream(partial, append).use { output ->
                            val buffer = ByteArray(65536); var reported = total
                            while (true) {
                                context.ensureActive()
                                val n = input.read(buffer); if (n == -1) break
                                total += n
                                check(total <= asset.bytes) { "Unexpected speech model size." }
                                output.write(buffer, 0, n)
                                if (total - reported >= 2 * 1024 * 1024) { onBytes(total); reported = total }
                            }
                            output.fd.sync()
                        }
                    }
                }
            } catch (e: java.io.IOException) { context.ensureActive(); throw e } finally { cancel.cancel() }
        }
        if (partial.length() != asset.bytes || MediaStore.digest(partial) != asset.sha256) {
            partial.delete()
            throw IllegalStateException("Speech model checksum mismatch. Retry the download.")
        }
        check(partial.renameTo(target)) { "Could not install speech model." }
        onBytes(asset.bytes)
    }
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // An old queued Whisper request has no modelId.
        val id = inputData.getString("modelId") ?: SpeechCatalog.WHISPER
        val graph = applicationContext.daybook
        val choice = runCatching { SpeechCatalog.get(id) }.getOrElse {
            return@withContext Result.failure(workDataOf("error" to "Unknown speech model. Choose one in Settings."))
        }
        graph.model.files(id) {
            if (graph.model.ready(id)) return@files Result.success()
            try {
                val directory = graph.model.directory(id).apply { mkdirs() }
                val missing = choice.assets.sumOf { (it.bytes - File(directory, it.name).length() - File(directory, it.name + ".partial").length()).coerceAtLeast(0) }
                check(directory.usableSpace > missing + 50L * 1024 * 1024) { "Free more phone storage before downloading ${choice.title}." }
                val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
                val transfer = SpeechAssetTransfer(client); var completed = 0L
                for (asset in choice.assets) {
                    ensureActive()
                    val partial = File(directory, asset.name + ".partial")
                    if (id == SpeechCatalog.WHISPER && !partial.exists()) File(directory, "download.partial").takeIf { it.exists() }?.renameTo(partial)
                    transfer.fetch(asset, File(directory, asset.name), partial) { bytes ->
                        setProgress(workDataOf("percent" to ((completed + bytes) * 100 / choice.bytes).toInt(), "modelId" to id))
                    }
                    completed += asset.bytes
                }
                graph.model.marker(id).writeText(choice.verification)
                // Only this model's pending recordings are resumed; other downloads cannot change their engine.
                graph.repository.read().entries.filter {
                    it.kind == "voice" && it.deletedAt == 0L && it.status in setOf("pending", "error", "interrupted") &&
                        (it.speechModel.ifBlank { SpeechCatalog.WHISPER } == id)
                }.forEach { graph.transcriptions.enqueue(it.id, modelId = id) }
                Result.success()
            } catch (e: CancellationException) { throw e
            } catch (e: java.io.IOException) {
                setProgress(workDataOf("message" to ("Download interrupted: " + (e.message ?: "connection unavailable")).take(300), "modelId" to id))
                if (runAttemptCount < 4) Result.retry() else Result.failure(workDataOf("error" to "Download interrupted. Tap Download to resume."))
            } catch (e: Exception) {
                Result.failure(workDataOf("error" to (e.message ?: "Speech model download failed.").take(500)))
            }
        }
    }
}
