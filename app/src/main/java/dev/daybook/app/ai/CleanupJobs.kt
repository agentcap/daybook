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
    fun request(day: String, force: Boolean, provider: String, charging: Boolean = context.daybook.settings.value.cleanupChargingOnly): OneTimeWorkRequest {
        LocalDate.parse(day); require(provider in setOf("local", "openai"))
        return OneTimeWorkRequestBuilder<CleanupWorker>()
            .setInputData(workDataOf("day" to day, "force" to force, "provider" to provider))
            .addTag("cleanup-review").addTag("cleanup-day:$day")
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (ReviewPolicy.needsNetwork(provider)) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED).setRequiresStorageNotLow(true).setRequiresCharging(charging).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    }
    fun enqueue(day: String, force: Boolean = false, provider: String = context.daybook.settings.value.reviewProvider, replace: Boolean = false, charging: Boolean = context.daybook.settings.value.cleanupChargingOnly) {
        WorkManager.getInstance(context).enqueueUniqueWork("cleanup-$day", if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(day, force, provider, charging))
    }
    suspend fun reconfigure(provider: String) = withContext(Dispatchers.IO) {
        val work = WorkManager.getInstance(context)
        val charging = context.daybook.preferences.read().cleanupChargingOnly
        val tagged = work.getWorkInfosByTag("cleanup-review").get(10, TimeUnit.SECONDS)
            .filter { !it.state.isFinished }.flatMap { it.tags }.filter { it.startsWith("cleanup-day:") }.map { it.substringAfter(':') }
        // Include pre-0.4 jobs, which were not tagged and always required a network.
        val days = (tagged + context.daybook.repository.read().entries.map { it.day }).distinct()
        days.forEach { day ->
            if (work.getWorkInfosForUniqueWork("cleanup-$day").get(10, TimeUnit.SECONDS).any { !it.state.isFinished })
                enqueue(day, force = true, provider = provider, replace = true, charging = charging)
        }
    }
}
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val graph = applicationContext.daybook
        val day = inputData.getString("day") ?: return@withContext Result.failure()
        try {
            val settings = graph.preferences.read()
            require(inputData.getString("provider")?.let { it == settings.reviewProvider } != false) { "Review provider changed. Run cleanup again." }
            require(ReviewPolicy.allowed(settings)) { "Enable OpenAI reviews in Settings, or choose on-device cleanup." }
            val modelName = if (settings.reviewProvider == "local") LocalReviewProtocol.MODEL_NAME else settings.model
            val state = graph.repository.read()
            val entries = ReviewPrivacy.entries(state, day, settings.reviewProvider)
            if (entries.isEmpty()) {
                require(state.entries.none { it.day == day && it.deletedAt == 0L }) { "These notes are kept on-device. Choose on-device cleanup or change their privacy settings." }
                return@withContext Result.success()
            }
            if (entries.any { it.status in setOf("recording", "pending") }) {
                if (runAttemptCount < 4) return@withContext Result.retry()
                error("Some voice notes are still waiting for transcription. Finish them, then clean up this day.")
            }
            require(entries.none { it.status != "ready" && it.kind == "voice" && it.text.isBlank() }) {
                "A voice note needs attention. Retry its transcription or add text before cleaning up."
            }
            val fingerprint = JournalRules.fingerprint(state, day)
            if (!inputData.getBoolean("force", false) && state.reviews.any { it.day == day && it.fingerprint == fingerprint && it.provider == settings.reviewProvider && it.model == modelName }) return@withContext Result.success()
            val ids = entries.map { it.id }.toSet()
            val photos = if (settings.reviewProvider == "local") emptyList() else ReviewPrivacy.photos(state, day).map {
                ReviewPhoto(it.id, it.entryId, Base64.getEncoder().encodeToString(graph.media.uploadPhoto(it)))
            }
            val context = if (settings.reviewProvider == "openai") ReviewPrivacy.cloudContext(state) else state
            val input = ReviewInput(day, entries, context.goals.filter { !it.archived }, photos, context.activities.filter { it.day == day && !it.undone }, context.items.filter { !it.done && !it.archived })
            setProgress(workDataOf("message" to if (settings.reviewProvider == "local") "Cleaning up on this phone. Larger days can take a few minutes…" else "Cleaning up with OpenAI…"))
            ensureActive()
            val beforeGeneration = graph.preferences.read()
            require(beforeGeneration.reviewProvider == settings.reviewProvider && ReviewPolicy.allowed(beforeGeneration)) { "Review settings changed. Run cleanup again." }
            val draft = if (settings.reviewProvider == "local") LocalCleanupClient(graph.localModel.file, graph.localModel::ready).generate(input)
                else OpenAiClient().generate(input, graph.keys.read(), settings.model)
            ensureActive()
            require(graph.preferences.read().reviewProvider == settings.reviewProvider) { "Review provider changed. Run cleanup again." }
            val review = Review(day = day, fingerprint = fingerprint, journal = draft.journal, summary = draft.summary,
                captions = draft.captions, model = modelName, sourceIds = entries.map { it.id }, provider = settings.reviewProvider)
            graph.repository.change { current ->
                // If sources were deleted while the request was running, never bring their content back.
                require(JournalRules.fingerprint(current, day) == fingerprint) { "This day changed during cleanup. Refresh the review to include the latest notes." }
                val existing = current.suggestions.filter { it.status != "pending" && it.status != "superseded" }.map { JournalRules.suggestionKey(it.goalId, it.day, it.sources) }.toSet()
                val suggestions = draft.suggestions.filter { JournalRules.suggestionKey(it.goalId, day, it.sources) !in existing }.map {
                    Suggestion(reviewId = review.id, goalId = it.goalId, day = day, amount = it.amount, note = it.note, sources = it.sources, estimated = it.estimated)
                }
                PlannerRules.proposals(current.copy(reviews = current.reviews + review, suggestions = current.suggestions.map { if (it.day == day && it.status == "pending") it.copy(status = "superseded") else it } + suggestions), review, draft.discoveries)
            }
            Result.success(workDataOf("reviewId" to review.id))
        } catch (e: CancellationException) { throw e
        } catch (e: CleanupFailure) {
            if (e.retryable && runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf("error" to (e.message ?: "Cleanup failed.").take(500)))
        } catch (e: java.io.IOException) {
            if (applicationContext.daybook.preferences.read().reviewProvider == "local") return@withContext Result.failure(workDataOf("error" to "The on-device model could not be read. Check the download in Settings; your notes are saved."))
            if (runAttemptCount < 4) Result.retry()
            else Result.failure(workDataOf("error" to "Could not connect to OpenAI. Your notes are saved. Try again later."))
        } catch (e: Exception) {
            Result.failure(workDataOf("error" to (e.message ?: "Cleanup failed. Your originals are preserved.").take(500)))
        }
    }
}
