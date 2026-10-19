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
import java.io.RandomAccessFile

class RecordingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var requestedStop = false
    private var task: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { requestedStop = true; return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        val channel = NotificationChannel("recording", "Voice recording", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val stop = PendingIntent.getService(this, 51, Intent(this, RecordingService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 52, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "recording").setSmallIcon(R.drawable.ic_daybook)
            .setContentTitle("Recording your thought").setContentText("Audio stays on this phone. Tap Stop when finished.")
            .setOngoing(true).setContentIntent(open).addAction(0, "Stop & save", stop).build()
        startForeground(50, notification)
        requestedStop = false; lastError.value = null; active.value = true
        task = scope.launch { record(intent?.getStringExtra("day") ?: java.time.LocalDate.now().toString()) }
        return START_NOT_STICKY
    }
    private suspend fun record(day: String) {
        val graph = daybook
        var audio: AudioRecord? = null
        var entry: Entry? = null
        var attachment: Attachment? = null
        var wake: PowerManager.WakeLock? = null
        try {
            graph.initialized.await()
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Microphone permission is needed." }
            check(android.os.StatFs(filesDir.path).availableBytes > 20L * 1024 * 1024) { "Free some phone storage before recording." }
            val path = graph.media.newPath("wav")
            entry = Entry(day = day, kind = "voice", status = "recording")
            attachment = Attachment(entryId = entry.id, path = path, kind = "audio", bytes = 0)
            val savedEntry = entry; val savedAttachment = attachment
            graph.repository.change { it.copy(entries = it.entries + savedEntry, attachments = it.attachments + savedAttachment) }
            val bufferSize = maxOf(8192, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2)
            audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "The microphone could not start." }
            wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "daybook:recording").apply { acquire(31 * 60 * 1000L) }
            val file = graph.media.file(path)
            RandomAccessFile(file, "rw").use { out ->
                Wav.header(out, 0)
                val buffer = ByteArray(bufferSize); var bytes = 0L; var checkpoint = 0L
                audio.startRecording()
                while (!requestedStop && scope.isActive && bytes < 16000L * 2 * 60 * 30) {
                    val n = audio.read(buffer, 0, buffer.size)
                    check(n >= 0) { "The microphone stopped unexpectedly." }
                    if (n == 0) continue
                    out.write(buffer, 0, n); bytes += n
                    if (bytes - checkpoint > 32000 * 5) { Wav.header(out, bytes); out.fd.sync(); checkpoint = bytes }
                }
                Wav.header(out, bytes); out.fd.sync()
            }
            val id = entry.id
            graph.repository.change { s -> s.copy(entries = s.entries.map { if (it.id == id) it.copy(status = "pending") else it },
                attachments = s.attachments.map { if (it.id == savedAttachment.id) it.copy(bytes = file.length()) else it }) }
            graph.transcriptions.enqueue(id)
        } catch (e: Exception) {
            lastError.value = (e.message ?: "Recording could not start. Please try again.").take(500)
            val id = entry?.id
            if (id != null) graph.repository.change { s -> s.copy(entries = s.entries.map {
                if (it.id == id) it.copy(status = "interrupted", error = (e.message ?: "Recording interrupted. Saved audio is preserved.").take(500)) else it
            }) }
        } finally {
            try { audio?.stop() } catch (_: Exception) { }
            audio?.release()
            if (wake?.isHeld == true) wake.release()
            active.value = false
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    override fun onDestroy() { requestedStop = true; super.onDestroy() }
    companion object {
        const val STOP = "dev.daybook.STOP"
        val active = MutableStateFlow(false)
        val lastError = MutableStateFlow<String?>(null)
    }
}
