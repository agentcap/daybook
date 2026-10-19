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
                graph.repository.read().entries.filter { it.status == "pending" }.forEach { graph.transcriptions.enqueue(it.id) }
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
    val transcriptions = Transcriptions(context)
    val scheduler = Scheduler(context)
    val cleanup = CleanupJobs(context)
    val backup = Backups(context, repository, preferences, media)
    val state = repository.changes.stateIn(scope, SharingStarted.Eagerly, Snapshot())
    val settings = preferences.changes.stateIn(scope, SharingStarted.Eagerly, Settings())
    suspend fun saveSettings(s: Settings) { preferences.save(s); scheduler.reschedule() }
    suspend fun deleteEntry(id: String) {
        transcriptions.cancel(id)
        repository.deleteEntry(id).forEach { media.file(it.path).delete() }
    }
}
val Context.daybook: AppGraph get() = (applicationContext as DaybookApp).graph
