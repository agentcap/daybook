package dev.daybook.app.capture

import dev.daybook.app.AppGraph
import dev.daybook.core.LiveTranscript
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

data class RecordingSession(val entryId: String = "", val elapsedMs: Long = 0, val level: Float = 0f,
    val transcript: String = "", val previewStatus: String = "Preparing your microphone…", val phase: String = "ready")

/** Audio capture never waits for inference. At most one pending six-second window is retained. */
class RecordingPreview(private val graph: AppGraph, private val entryId: String, scope: CoroutineScope,
    private val state: MutableStateFlow<RecordingSession>) {
    private data class Window(val index: Int, val samples: FloatArray)
    private val channel = Channel<Window>(1, BufferOverflow.DROP_OLDEST)
    private val samples = FloatArray(16000 * 6)
    private var count = 0
    private var index = 0
    private var reported = 0
    private val enabled = graph.model.ready()
    private val job = if (enabled) scope.launch {
        var engine: WhisperEngine.PreviewSession? = null
        try {
            state.value = state.value.copy(previewStatus = "Loading on-device speech…")
            engine = WhisperEngine.openPreview(graph.model.file)
            val transcript = LiveTranscript()
            for (window in channel) {
                ensureActive()
                state.value = state.value.copy(previewStatus = "Listening · preview updates in short batches")
                val text = engine.transcribe(window.samples)
                ensureActive()
                val full = transcript.update(window.index, text)
                if (full.isNotBlank()) {
                    // Persist drafts periodically so interruption does not discard visible words.
                    graph.repository.change { s -> s.copy(entries = s.entries.map {
                        if (it.id == entryId && it.status == "recording") it.copy(text = full) else it
                    }) }
                    state.value = state.value.copy(transcript = full)
                }
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            state.value = state.value.copy(previewStatus = "Live preview unavailable. Audio is still being saved.")
        } finally { withContext(NonCancellable) { engine?.close() } }
    } else null

    init {
        if (!enabled) state.value = state.value.copy(previewStatus = "Download the English model in Settings for live text. Audio is being saved.")
    }
    fun feed(pcm: ByteArray, size: Int): Float {
        var energy = 0.0; var n = 0
        var offset = 0
        while (offset + 1 < size) {
            val value = ((pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)).toShort() / 32768f
            energy += value * value; n++
            if (enabled) {
                samples[count++] = value
                if (count - reported >= 16000 * 3) {
                    channel.trySend(Window(index, samples.copyOf(count))); reported = count
                }
                if (count == samples.size) { index++; count = 0; reported = 0 }
            }
            offset += 2
        }
        return if (n == 0) 0f else (sqrt(energy / n) * 5).toFloat().coerceIn(0f, 1f)
    }
    suspend fun stop() {
        channel.close()
        // A final accurate pass uses the complete saved WAV in WorkManager.
        job?.cancelAndJoin()
    }
}
