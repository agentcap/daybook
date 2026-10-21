package dev.daybook.app.capture

import ai.moonshine.voice.Transcriber
import com.k2fsa.sherpa.onnx.*
import dev.daybook.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** Bounded audio windows keep memory use independent of a recording's length. */
internal object SpeechAudio {
    suspend fun windows(file: File, seconds: Int = 30, consume: suspend (Long, Long, FloatArray) -> Unit) {
        require(seconds in 1..30)
        require(file.length() in 45..57_600_044) { "The saved audio could not be read. Your recording is preserved." }
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(44); input.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Unsupported recording format." }
            fun int(offset: Int) = (0..3).fold(0) { value, i -> value or ((header[offset + i].toInt() and 255) shl (i * 8)) }
            require(int(24) == 16000 && header[22].toInt() == 1 && header[34].toInt() == 16) { "Speech requires mono 16 kHz PCM audio." }
            var samplesRead = 0L
            while (input.filePointer < input.length() - 1) {
                currentCoroutineContext().ensureActive()
                val count = minOf(seconds * 16000, ((input.length() - input.filePointer) / 2).toInt())
                val bytes = ByteArray(count * 2); input.readFully(bytes)
                val samples = FloatArray(count) { i -> ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort() / 32768f }
                val start = samplesRead * 1000 / 16000
                samplesRead += count
                // Avoid invoking a speech decoder for a wholly silent window.
                if (samples.any { abs(it) > 0.0001f }) consume(start, samplesRead * 1000 / 16000, samples)
            }
        }
    }
}

/** All final speech engines run one at a time and release native memory after each note. */
object SpeechEngines {
    private val lock = Mutex()
    suspend fun timeline(choice: SpeechChoice, directory: File, recording: File): List<TranscriptSegment> =
        lock.withLock { withContext(Dispatchers.IO) {
            ensureActive()
            try {
                when (choice.engine) {
                    "whisper" -> WhisperEngine.timeline(File(directory, choice.assets.single().name), recording)
                    "moonshine" -> moonshine(choice, directory, recording)
                    "parakeet" -> parakeet(directory, recording)
                    else -> error("Unsupported speech engine.")
                }
            } catch (error: LinkageError) {
                throw IllegalStateException("This speech engine could not load. Update Daybook or select another model.", error)
            } catch (error: OutOfMemoryError) {
                throw IllegalStateException("There is not enough free memory for this model. Close other apps or select a smaller speech model.", error)
            }
        } }

    private suspend fun moonshine(choice: SpeechChoice, directory: File, recording: File): List<TranscriptSegment> {
        val engine = Transcriber()
        try {
            engine.loadFromFiles(directory.absolutePath, choice.architecture)
            val output = mutableListOf<TranscriptSegment>()
            SpeechAudio.windows(recording) { start, end, samples ->
                currentCoroutineContext().ensureActive()
                val result = engine.transcribeWithoutStreaming(samples, 16000)
                currentCoroutineContext().ensureActive()
                result.lines.orEmpty().forEach { line ->
                    val text = line.text.orEmpty().trim()
                    if (text.isNotBlank()) {
                        val from = (start + (line.startTime * 1000).toLong()).coerceIn(start, end)
                        val to = if (line.duration > 0) (from + (line.duration * 1000).toLong()).coerceIn(from, end) else end
                        output += TranscriptSegment(from, to, text)
                    }
                }
            }
            return output
        } finally { engine.close() }
    }

    private suspend fun parakeet(directory: File, recording: File): List<TranscriptSegment> {
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(directory, "encoder.int8.onnx").absolutePath,
                decoder = File(directory, "decoder.int8.onnx").absolutePath,
                joiner = File(directory, "joiner.int8.onnx").absolutePath),
            tokens = File(directory, "tokens.txt").absolutePath,
            modelType = "nemo_transducer", numThreads = 4, provider = "cpu")))
        try {
            val output = mutableListOf<TranscriptSegment>()
            SpeechAudio.windows(recording) { start, end, samples ->
                currentCoroutineContext().ensureActive()
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(samples, 16000); recognizer.decode(stream)
                    currentCoroutineContext().ensureActive()
                    val text = recognizer.getResult(stream).text.trim()
                    if (text.isNotBlank()) output += TranscriptSegment(start, end, text)
                } finally { stream.release() }
            }
            return output
        } finally { recognizer.release() }
    }
}
