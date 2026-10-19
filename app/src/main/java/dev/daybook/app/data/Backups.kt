package dev.daybook.app.data

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import dev.daybook.core.*
import java.io.*
import java.util.zip.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class Backups(private val context: Context, private val repo: JournalRepository, private val prefs: Preferences, private val media: MediaStore) {
    private val gson = Gson()
    suspend fun export(uri: Uri, password: CharArray) = media.operations.withLock {
        try {
            val state = repo.read()
            require(state.entries.none { it.status == "recording" }) { "Stop recording before creating a backup." }
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the backup destination.")
            ZipOutputStream(ArchiveCrypto.encrypt(output, password)).use { zip ->
                zip.putNextEntry(ZipEntry("bundle.json"))
                zip.write(gson.toJson(BackupBundle(state = state, settings = prefs.read())).toByteArray())
                zip.closeEntry()
                state.attachments.distinctBy { it.path }.forEach { attachment ->
                    val file = media.file(attachment.path)
                    require(file.isFile) { "An attachment is missing. Backup stopped so it does not silently lose media." }
                    zip.putNextEntry(ZipEntry(attachment.path)); file.inputStream().use { copyChecked(it, zip, 1024L * 1024 * 1024) }; zip.closeEntry()
                }
            }
        } finally { password.fill('\u0000') }
    }
    suspend fun restore(uri: Uri, password: CharArray) = media.restoreGate.withLock { media.operations.withLock {
        val staging = File(context.cacheDir, "restore-${newId()}").apply { mkdirs() }
        val copied = mutableListOf<File>()
        var committed = false
        try {
            require(repo.read().entries.none { it.status == "recording" }) { "Stop recording before restoring." }
            val archive = File(staging, "authenticated.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    ArchiveCrypto.decrypt(input, password).use { plain ->
                        archive.outputStream().use { copyChecked(plain, it, 8L * 1024 * 1024 * 1024) }
                    }
                } ?: error("Could not open this backup.")
            } catch (e: Exception) { throw IllegalArgumentException("Backup could not be unlocked. Check the passphrase; the file may be incomplete or damaged.", e) }
            var bundle: BackupBundle? = null
            val seen = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && seen.add(entry.name) && seen.size <= 100_000) { "Invalid or duplicate backup entry." }
                    if (entry.name == "bundle.json") {
                        val json = ByteArrayOutputStream().also { copyChecked(zip, it, 32L * 1024 * 1024) }.toString("UTF-8")
                        bundle = gson.fromJson(json, BackupBundle::class.java)
                    } else {
                        require(entry.name.matches(Regex("media/[a-zA-Z0-9_-]+\\.(wav|jpg|jpeg|png|webp|heic)"))) { "Unsafe file path in backup." }
                        val target = File(staging, entry.name).apply { parentFile!!.mkdirs() }
                        target.outputStream().use { copyChecked(zip, it, 1024L * 1024 * 1024) }
                        total += target.length(); require(total <= 8L * 1024 * 1024 * 1024) { "Backup exceeds the restore size limit." }
                    }
                    zip.closeEntry()
                }
            }
            val data = bundle ?: error("This backup has no journal data.")
            require(data.version in setOf(1, 2)) { "This backup requires a newer Daybook version." }
            JournalRules.validateSnapshot(data.state); JournalRules.validateSettings(data.settings)
            val required = data.state.attachments.map { it.path }.toSet() + "bundle.json"
            require(seen == required) { "Backup attachments do not match its manifest." }
            val paths = mutableMapOf<String, String>()
            data.state.attachments.distinctBy { it.path }.forEach { attachment ->
                val source = File(staging, attachment.path)
                require(source.isFile && (attachment.bytes == 0L || source.length() == attachment.bytes)) { "An attachment is missing or incomplete." }
                if (attachment.digest.isNotBlank()) require(MediaStore.digest(source) == attachment.digest) { "Attachment checksum mismatch." }
                val newPath = media.newPath(attachment.path.substringAfterLast('.'))
                val target = media.file(newPath)
                copied += target
                source.inputStream().use { input -> target.outputStream().use { copyChecked(input, it, 1024L * 1024 * 1024) } }
                paths[attachment.path] = newPath
            }
            val state = data.state.copy(
                attachments = data.state.attachments.map { it.copy(path = paths.getValue(it.path)) },
                entries = data.state.entries.map { if (it.status == "recording") it.copy(status = "interrupted", error = "Recording restored from backup. Retry transcription.") else it })
            // File paths are local references. Recompute review fingerprints only for reviews that were current at export.
            val updated = state.copy(proposals = state.proposals.map { p ->
                if (p.sourceFingerprint == PlannerRules.sourceFingerprint(data.state, p.item.sources)) p.copy(sourceFingerprint = PlannerRules.sourceFingerprint(state, p.item.sources)) else p
            }, reviews = state.reviews.map { r ->
                if (r.fingerprint == JournalRules.fingerprint(data.state, r.day)) r.copy(fingerprint = JournalRules.fingerprint(state, r.day)) else r
            })
            repo.change { updated }; committed = true
            prefs.save(data.settings.copy(biometric = false))
            // Keep old media until the database commit succeeded; never overwrite preexisting files during validation.
            val retained = updated.attachments.map { media.file(it.path).name }.toSet()
            media.directory.listFiles()?.filter { it.name !in retained }?.forEach { it.delete() }
        } finally {
            if (!committed) copied.forEach { it.delete() }
            staging.deleteRecursively(); password.fill('\u0000')
        }
    } }
    private suspend fun copyChecked(input: InputStream, output: OutputStream, limit: Long) {
        val buffer = ByteArray(65536); var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer); if (n < 0) break
            count += n; require(count <= limit) { "The backup exceeds its size limit." }
            output.write(buffer, 0, n)
        }
    }
    suspend fun markdown(uri: Uri, day: String) {
        val state = RecoveryRules.visible(repo.read())
        val text = buildString {
            appendLine("# Daybook · $day"); appendLine()
            appendLine("Goals worked on: ${JournalRules.workedOn(state, day)}"); appendLine()
            state.entries.filter { it.day == day }.sortedBy { it.created }.forEach { entry ->
                appendLine("## ${java.time.Instant.ofEpochMilli(entry.created).atZone(java.time.ZoneId.of(entry.zone)).toLocalTime()} · ${entry.kind}")
                appendLine(); appendLine(entry.text.ifBlank { "(Photo or audio attachment)" })
                if (entry.text != entry.originalText) { appendLine(); appendLine("### Original"); appendLine(entry.originalText) }
                state.revisions.filter { it.entryId == entry.id }.sortedBy { it.created }.forEach { appendLine(); appendLine("### Earlier version"); appendLine(it.text) }
                state.attachments.filter { it.entryId == entry.id }.forEach { appendLine(); appendLine("Attachment: ${it.id} (${it.kind}; included in full encrypted backup)") }
                appendLine()
            }
            state.items.filter { item -> item.sources.any { id -> state.entries.any { it.id == id && it.day == day } } }.forEach { item ->
                appendLine("## ${if (item.kind == "todo") "To-do" else "Idea"} · ${item.title}"); appendLine(item.details); appendLine("Status: ${if (item.done) "done / tried" else "open"}"); appendLine()
            }
            state.reviews.filter { it.day == day }.sortedBy { it.created }.forEach {
                appendLine("## Cleaned review · ${java.time.Instant.ofEpochMilli(it.created)}"); appendLine()
                if (it.provider == "local") appendLine("On-device text review; attached photos were not analyzed.")
                appendLine(it.summary); appendLine(); appendLine(it.journal); appendLine()
                it.captions.forEach { c -> appendLine("Photo ${c.attachmentId}: ${c.description}") }
            }
        }
        context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) } ?: error("Could not export this day.")
    }
}
