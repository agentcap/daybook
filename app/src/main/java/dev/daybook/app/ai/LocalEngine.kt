package dev.daybook.app.ai

import dev.daybook.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

interface LocalTextEngine {
    suspend fun generate(model: File, system: String, input: String, grammar: String): String
}
data class LocalGenerationStats(val stage: String = "", val promptTokens: Int = 0, val outputTokens: Int = 0, val elapsedMs: Long = 0, val firstTokenMs: Long = 0)
class LocalControl(private val job: Job) {
    private var firstToken = 0L
    fun isCancelled(): Boolean = !job.isActive
    fun progress(stage: String, prompt: Int, output: Int, elapsed: Long) {
        if (stage == "Writing review" && firstToken == 0L) firstToken = elapsed
        LocalEngine.stats.value = LocalGenerationStats(stage, prompt, output, elapsed, firstToken)
    }
}
object LocalEngine : LocalTextEngine {
    init { System.loadLibrary("daybook_llm") }
    val stats = kotlinx.coroutines.flow.MutableStateFlow(LocalGenerationStats())
    private val mutex = Mutex()
    private external fun generateNative(path: String, system: ByteArray, input: ByteArray, grammar: ByteArray, control: LocalControl): ByteArray
    override suspend fun generate(model: File, system: String, input: String, grammar: String): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val control = LocalControl(currentCoroutineContext().job)
            generateNative(model.absolutePath, system.toByteArray(Charsets.UTF_8), input.toByteArray(Charsets.UTF_8),
                grammar.toByteArray(Charsets.UTF_8), control).toString(Charsets.UTF_8).also { ensureActive() }
        }
    }
}
class LocalCleanupClient(private val file: File, private val ready: () -> Boolean,
    private val engine: LocalTextEngine = LocalEngine) {
    suspend fun generate(input: ReviewInput): ReviewDraft {
        check(ready()) { "Download the on-device review model in Settings → AI reflections first. Your notes are saved." }
        val text = LocalReviewProtocol.textInput(input)
        val payload = LocalReviewProtocol.payload(text)
        require(payload.toByteArray().size <= 70_000) { "This day is too long for on-device reflections. Use OpenAI or a day with fewer words." }
        val output = engine.generate(file, LocalReviewProtocol.instructions(), payload, LocalReviewProtocol.grammar(text))
        currentCoroutineContext().ensureActive()
        return try { LocalReviewProtocol.parse(output, text) }
        catch (_: Exception) { throw IllegalStateException("The on-device model returned an invalid review or evidence. Your originals are preserved. Try again or use OpenAI.") }
    }
}
