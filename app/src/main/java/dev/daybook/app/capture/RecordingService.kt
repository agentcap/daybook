package dev.daybook.app.capture

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.daybook.app.MainActivity
import dev.daybook.app.R
import dev.daybook.app.daybook
import dev.daybook.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.withLock
import java.io.RandomAccessFile

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var requestedStop = false
    @Volatile private var requestedPause = false
    @Volatile private var requestedDiscard = false
    private val finishLock = Any()
    private var task: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DISCARD) {
            synchronized(finishLock) {
                if (task?.isActive == true && session.value.phase in setOf("starting", "recording", "paused")) {
                    requestedDiscard = true; requestedStop = true
                    session.value = session.value.copy(phase = "discarding", level = 0f, previewStatus = "Discarding your recording…")
                } else if (task?.isActive != true) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == STOP) { requestedStop = true; if (task?.isActive != true) stopSelf(startId); return START_NOT_STICKY }
        if (intent?.action in setOf(PAUSE, BOOKMARK)) {
            if (task?.isActive != true) { stopSelf(startId); return START_NOT_STICKY }
            if (intent?.action == PAUSE) requestedPause = !requestedPause
            else {
                val mark = session.value
                if (mark.entryId.isNotBlank() && mark.phase in setOf("recording", "paused")) scope.launch {
                    daybook.repository.change { state -> state.copy(entries = state.entries.map {
                        if (it.id == mark.entryId && it.status == "recording" && it.bookmarks.size < 1000)
                            it.copy(bookmarks = it.bookmarks + AudioBookmark(ms = mark.elapsedMs, label = "Bookmark ${it.bookmarks.size + 1}")) else it
                    }) }
                    if (session.value.entryId == mark.entryId) session.value = session.value.copy(bookmarks = session.value.bookmarks + 1)
                }
            }
            return START_NOT_STICKY
        }
        if (task?.isActive == true) return START_NOT_STICKY
        notifyRecording(false, initial = true)
        requestedStop = false; requestedPause = false; requestedDiscard = false; lastError.value = null; session.value = RecordingSession(phase = "starting"); active.value = true
        task = scope.launch { record(intent?.getStringExtra("day") ?: java.time.LocalDate.now().toString()) }
        return START_NOT_STICKY
    }
    private fun notifyRecording(paused: Boolean, initial: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("recording", "Voice recording", NotificationManager.IMPORTANCE_LOW))
        fun action(code: Int, name: String) = PendingIntent.getService(this, code,
            Intent(this, RecordingService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 52, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "recording").setSmallIcon(R.drawable.ic_daybook)
            .setContentTitle(if (paused) "Recording paused" else "Recording your thought")
            .setContentText(if (paused) "Tap Resume to continue this note." else "Audio stays on this phone. Stop when finished.")
            .setOngoing(true).setContentIntent(open).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "Stop & save", action(51, STOP))
            .addAction(0, if (paused) "Resume" else "Pause", action(53, PAUSE))
            .addAction(0, "Bookmark", action(54, BOOKMARK)).build()
        if (initial) startForeground(50, notification) else manager.notify(50, notification)
    }
    private suspend fun record(day: String) {
        val graph = daybook
        var audio: AudioRecord? = null
        var entry: Entry? = null
        var attachment: Attachment? = null
        var wake: PowerManager.WakeLock? = null
        var preview: RecordingPreview? = null
        var registeredAudio: AudioRecord? = null
        var callback: AudioManager.AudioRecordingCallback? = null
        var speechSettings = Settings()
        try {
            graph.initialized.await()
            speechSettings = graph.preferences.read()
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is needed." }
            check(android.os.StatFs(filesDir.path).availableBytes > 20L * 1024 * 1024) { "Free some phone storage before recording." }
            val path = graph.media.newPath("wav")
            entry = Entry(day = day, kind = "voice", status = "recording", speechModel = speechSettings.speechModel)
            attachment = Attachment(entryId = entry.id, path = path, kind = "audio", bytes = 0)
            val savedEntry = entry; val savedAttachment = attachment
            graph.media.restoreGate.withLock { check(!requestedStop) { "Recording cancelled before the microphone started." }; graph.repository.change { it.copy(entries = it.entries + savedEntry, attachments = it.attachments + savedAttachment) } }
            session.value = RecordingSession(entryId = entry.id, phase = "recording", speechModel = speechSettings.speechModel,
                livePreview = speechSettings.liveTranscripts && SpeechCatalog.get(speechSettings.speechModel).livePreview)
            preview = RecordingPreview(graph, entry.id, scope, session)
            val bufferSize = maxOf(8192, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not start." }
            val recorder = audio
            callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (configs.any { it.clientAudioSessionId == recorder.audioSessionId && it.isClientSilenced }) {
                        requestedPause = true
                        session.value = session.value.copy(previewStatus = "The microphone became unavailable. Resume when your call or other recording is finished.")
                    }
                }
            }
            audio.registerAudioRecordingCallback(mainExecutor, callback)
            registeredAudio = audio
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daybook:recording").apply { acquire(31 * 60 * 1000L) }
            val file = graph.media.file(path)
            RandomAccessFile(file, "rw").use { out ->
                Wav.header(out, 0)
                val buffer = ByteArray(bufferSize); var bytes = 0L; var checkpoint = 0L; var capturing = true
                audio.startRecording()
                while (!requestedStop && scope.isActive && bytes < 16000L * 2 * 60 * 30) {
                    if (requestedPause) {
                        if (capturing) {
                            audio.stop(); capturing = false
                            if (wake?.isHeld == true) wake.release()
                            session.value = session.value.copy(phase = "paused", level = 0f)
                            notifyRecording(true)
                        }
                        delay(100); continue
                    }
                    if (!capturing) {
                        audio.startRecording(); capturing = true
                        if (wake?.isHeld != true) wake?.acquire(31 * 60 * 1000L)
                        session.value = session.value.copy(phase = "recording")
                        notifyRecording(false)
                    }
                    val n = audio.read(buffer, 0, buffer.size)
                    check(n >= 0) { "The microphone stopped unexpectedly." }
                    if (n == 0) continue
                    out.write(buffer, 0, n); bytes += n
                    val level = preview.feed(buffer, n)
                    session.value = session.value.copy(elapsedMs = bytes * 1000 / 32000, level = level)
                    if (bytes - checkpoint > 32000 * 5) { Wav.header(out, bytes); out.fd.sync(); checkpoint = bytes; check(android.os.StatFs(filesDir.path).availableBytes > 5L * 1024 * 1024) { "Recording stopped because phone storage is almost full. Saved audio is preserved." } }
                }
                Wav.header(out, bytes); out.fd.sync()
            }
            runCatching { audio.stop() }; callback?.let { audio.unregisterAudioRecordingCallback(it) }; audio.release(); audio = null
            val discard = synchronized(finishLock) {
                session.value = session.value.copy(phase = if (requestedDiscard) "discarding" else "saving", level = 0f)
                requestedDiscard
            }
            preview.stop(); preview = null
            if (discard) return
            val id = entry.id
            graph.repository.change { s -> s.copy(entries = s.entries.map { if (it.id == id) it.copy(status = "pending") else it },
                attachments = s.attachments.map { if (it.id == savedAttachment.id) it.copy(bytes = file.length()) else it }) }
            graph.transcriptions.enqueue(id, modelId = speechSettings.speechModel)
            session.value = session.value.copy(phase = "saved", previewStatus = "Saved. ${SpeechCatalog.get(speechSettings.speechModel).title} is transcribing on this phone.")
        } catch (e: Exception) {
            if (!requestedDiscard) {
                lastError.value = (e.message ?: "Recording could not start. Please try again.").take(500)
                session.value = session.value.copy(phase = "error", previewStatus = lastError.value.orEmpty())
                val id = entry?.id
                if (attachment != null) runCatching {
                    val saved = attachment
                    val file = graph.media.file(saved.path)
                    if (file.length() > 44) Wav.repair(file)
                    graph.repository.change { state -> state.copy(attachments = state.attachments.map { if (it.id == saved.id) it.copy(bytes = file.length()) else it }) }
                }
                if (id != null) graph.repository.change { s -> s.copy(entries = s.entries.map {
                    if (it.id == id) it.copy(status = "interrupted", error = (e.message ?: "Recording interrupted. Saved audio is preserved.").take(500)) else it
                }) }
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { callback?.let { registeredAudio?.unregisterAudioRecordingCallback(it) }; audio?.stop() }
                runCatching { audio?.release() }
                if (wake?.isHeld == true) wake.release()
                preview?.stop()
                if (requestedDiscard) {
                    try {
                        graph.media.operations.withLock {
                            val removed = graph.repository.discardRecording(entry?.id.orEmpty())
                            (removed.map { it.path } + listOfNotNull(attachment?.path)).distinct().forEach { path ->
                                val file = graph.media.file(path)
                                check(!file.exists() || file.delete()) { "The recording left your journal, but its audio could not be removed from phone storage." }
                            }
                        }
                        lastError.value = null
                        session.value = RecordingSession(phase = "discarded", previewStatus = "Recording discarded.")
                    } catch (e: Exception) {
                        val message = (e.message ?: "Could not discard the recording. Saved audio is preserved.").take(500)
                        runCatching { graph.repository.change { state -> state.copy(entries = state.entries.map {
                            if (it.id == entry?.id && it.status == "recording") it.copy(status = "interrupted", error = message) else it
                        }) } }
                        lastError.value = message
                        session.value = session.value.copy(phase = "error", previewStatus = message)
                    }
                }
            }
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                // Serialize final service state with onStartCommand so an immediate next capture is not lost.
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                task = null
                active.value = false
            }
        }
    }
    override fun onDestroy() { requestedStop = true; super.onDestroy() }
    companion object {
        const val DISCARD = "dev.daybook.DISCARD"
        const val STOP = "dev.daybook.STOP"
        const val PAUSE = "dev.daybook.PAUSE"
        const val BOOKMARK = "dev.daybook.BOOKMARK"
        val session = MutableStateFlow(RecordingSession())
        val active = MutableStateFlow(false)
        val lastError = MutableStateFlow<String?>(null)
    }
}
