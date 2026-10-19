package dev.daybook.app.ai
import android.content.Context
import androidx.work.*
import dev.daybook.app.daybook
import dev.daybook.core.*
import kotlinx.coroutines.*
import java.time.LocalDate
import java.util.Base64
import java.util.concurrent.TimeUnit

class CleanupJobs(private val context: Context) {
    fun enqueue(day: String, force: Boolean = false) {
        LocalDate.parse(day)
        WorkManager.getInstance(context).enqueueUniqueWork("cleanup-$day", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CleanupWorker>().setInputData(workDataOf("day" to day, "force" to force))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
    }
}
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = applicationContext.daybook
        val day = inputData.getString("day") ?: return@withContext Result.failure()
        try {
            val settings = graph.preferences.read()
            require(settings.cloudConsent) { "Enable sending journal text and attached photos to OpenAI in Settings." }
            val state = graph.repository.read()
            val entries = state.entries.filter { it.day == day }.sortedBy { it.created }
            if (entries.isEmpty()) return@withContext Result.success()
            if (entries.any { it.status in setOf("recording", "pending") }) {
                if (runAttemptCount < 4) return@withContext Result.retry()
                error("Some voice notes are still waiting for transcription. Finish them, then clean up this day.")
            }
            require(entries.none { it.status != "ready" && it.kind == "voice" && it.text.isBlank() }) {
                "A voice note needs attention. Retry its transcription or add text before cleaning up."
            }
            val fingerprint = JournalRules.fingerprint(state, day)
            if (!inputData.getBoolean("force", false) && state.reviews.any { it.day == day && it.fingerprint == fingerprint }) return@withContext Result.success()
            val ids = entries.map { it.id }.toSet()
            val photos = state.attachments.filter { it.entryId in ids && it.kind == "photo" }.map {
                ReviewPhoto(it.id, it.entryId, Base64.getEncoder().encodeToString(graph.media.uploadPhoto(it)))
            }
            val input = ReviewInput(day, entries, state.goals.filter { !it.archived }, photos, state.activities.filter { it.day == day && !it.undone })
            val draft = OpenAiClient().generate(input, graph.keys.read(), settings.model)
            ensureActive()
            val review = Review(day = day, fingerprint = fingerprint, journal = draft.journal, summary = draft.summary,
                captions = draft.captions, model = settings.model, sourceIds = entries.map { it.id })
            graph.repository.change { current ->
                // If sources were deleted while the request was running, never bring their content back.
                require(JournalRules.fingerprint(current, day) == fingerprint) { "This day changed during cleanup. Refresh the review to include the latest notes." }
                val existing = current.suggestions.filter { it.status != "pending" && it.status != "superseded" }.map { JournalRules.suggestionKey(it.goalId, it.day, it.sources) }.toSet()
                val suggestions = draft.suggestions.filter { JournalRules.suggestionKey(it.goalId, day, it.sources) !in existing }.map {
                    Suggestion(reviewId = review.id, goalId = it.goalId, day = day, amount = it.amount, note = it.note, sources = it.sources, estimated = it.estimated)
                }
                current.copy(reviews = current.reviews + review, suggestions = current.suggestions.map { if (it.day == day && it.status == "pending") it.copy(status = "superseded") else it } + suggestions)
            }
            Result.success(workDataOf("reviewId" to review.id))
        } catch (e: CancellationException) { throw e
        } catch (e: CleanupFailure) {
            if (e.retryable && runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "Cleanup failed.").take(500)))
        } catch (e: java.io.IOException) {
            if (runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf("error" to "Could not connect to OpenAI. Your notes are saved. Try again later."))
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to (e.message ?: "Cleanup failed. Your originals are preserved.").take(500)))
        }
    }
}
