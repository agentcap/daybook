package dev.daybook.app

import android.app.Application
import android.content.Context
import dev.daybook.app.data.*
import dev.daybook.app.capture.*
import dev.daybook.app.ai.*
import dev.daybook.app.jobs.*
import dev.daybook.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

class DaybookApp : Application() {
    lateinit var graph: AppGraph; private set
    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.scope.launch {
            try {
                graph.repository.change { state ->
                    state.copy(entries = state.entries.map { if (it.status == "recording") it.copy(status = "interrupted", error = "Recording was interrupted. Saved audio is available; retry transcription.") else it })
                }
                graph.scheduler.reschedule()
                graph.backupJobs.reschedule()
                graph.purgeTrash()
                graph.repository.read().entries.filter { it.status == "pending" && it.deletedAt == 0L }.forEach { graph.transcriptions.enqueue(it.id, modelId = it.speechModel.ifBlank { SpeechCatalog.WHISPER }) }
            } finally { graph.initialized.complete(Unit) }
        }
    }
}
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val initialized = CompletableDeferred<Unit>()
    val database = JournalDatabase.open(context)
    val repository = JournalRepository(database)
    val preferences = Preferences(context)
    val keys = KeyVault(context)
    val media = MediaStore(context)
    val model = SpeechModel(context)
    val localModel = LocalModel(context)
    val transcriptions = Transcriptions(context)
    val scheduler = Scheduler(context)
    val cleanup = CleanupJobs(context)
    val backup = Backups(context, repository, preferences, media)
    val backupJobs = BackupJobs(context)
    val rawState = repository.changes.stateIn(scope, SharingStarted.Eagerly, Snapshot())
    val state = rawState.map(RecoveryRules::visible).stateIn(scope, SharingStarted.Eagerly, Snapshot())
    val settings = preferences.changes.stateIn(scope, SharingStarted.Eagerly, Settings())
    suspend fun saveSettings(s: Settings) {
        val previous = preferences.read()
        preferences.save(s)
        if (previous.reviewProvider != s.reviewProvider || previous.cleanupChargingOnly != s.cleanupChargingOnly) cleanup.reconfigure(s.reviewProvider)
        scheduler.reschedule()
    }
    suspend fun deleteEntry(id: String) {
        transcriptions.cancel(id)
        repository.trash(id)
    }
    suspend fun purgeEntry(id: String) = media.operations.withLock {
        require(repository.read().entries.first { it.id == id }.deletedAt > 0) { "Move the note to Recently Deleted first." }
        transcriptions.cancel(id)
        repository.deleteEntry(id).forEach { media.file(it.path).delete() }
    }
    suspend fun purgeTrash() { RecoveryRules.expired(repository.read()).forEach { purgeEntry(it) } }
    suspend fun retryTranscription(id: String) {
        val modelId = preferences.read().speechModel
        transcriptions.cancel(id).result.get()
        repository.change { s -> s.copy(entries = s.entries.map {
            if (it.id == id && it.deletedAt == 0L && it.status != "recording") it.copy(status = "pending", error = "", speechModel = modelId) else it }) }
        transcriptions.enqueue(id, replace = true, modelId = modelId)
    }
    suspend fun cancelTranscription(id: String) {
        transcriptions.cancel(id)
        repository.change { s -> s.copy(entries = s.entries.map {
            if (it.id == id && it.deletedAt == 0L && it.status == "pending") it.copy(status = "interrupted", error = "Transcription cancelled. Your audio is saved; retry whenever you want.") else it }) }
    }
}
val Context.daybook: AppGraph get() = (applicationContext as DaybookApp).graph
