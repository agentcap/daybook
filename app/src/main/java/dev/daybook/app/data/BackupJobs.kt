package dev.daybook.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.*
import com.google.gson.Gson
import dev.daybook.app.daybook
import dev.daybook.core.newId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.*
import javax.crypto.spec.GCMParameterSpec

private val Context.backupPreferences by preferencesDataStore("backup_schedule")
data class BackupConfig(val id: String = "", val enabled: Boolean = false, val folder: String = "",
    val lastSuccess: Long = 0, val lastError: String = "", val owned: List<String> = emptyList())

/** Separate Keystore secret: never part of Settings, journal exports, or diagnostics. */
class BackupSecret(context: Context) {
    private val prefs = context.getSharedPreferences("private_backup_secret", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val alias = "daybook_backup_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(pass: CharArray) {
        require(pass.size >= 10)
        val bytes = pass.concatToString().toByteArray(Charsets.UTF_8)
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val data = c.doFinal(bytes)
            check(prefs.edit().putString("value", Base64.encodeToString(data, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP)).commit())
        } finally { bytes.fill(0); pass.fill('\u0000') }
    }
    fun read(): CharArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        }
        val bytes = c.doFinal(Base64.decode(prefs.getString("value", "")!!, Base64.NO_WRAP))
        return try { bytes.toString(Charsets.UTF_8).toCharArray() } finally { bytes.fill(0) }
    }
    fun clear() { check(prefs.edit().clear().commit()) }
}

class BackupJobs(private val context: Context) {
    private val gson = Gson()
    private val prefKey = stringPreferencesKey("config")
    private val mutex = Mutex()
    val secret = BackupSecret(context)
    val changes = context.backupPreferences.data.map { it[prefKey]?.let { json -> gson.fromJson(json, BackupConfig::class.java) } ?: BackupConfig() }
    suspend fun read() = changes.first()
    private suspend fun save(config: BackupConfig) { context.backupPreferences.edit { it[prefKey] = gson.toJson(config) } }
    suspend fun configure(folder: Uri, password: CharArray) {
        try {
            require(DocumentsContract.isTreeUri(folder))
            context.contentResolver.takePersistableUriPermission(folder,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            secret.save(password)
            save(BackupConfig(id = newId(), enabled = true, folder = folder.toString()))
            reschedule(); now()
        } finally { password.fill('\u0000') }
    }
    suspend fun disable() {
        save(read().copy(id = newId(), enabled = false))
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(WORK); work.cancelUniqueWork(NOW)
        secret.clear()
    }
    suspend fun reschedule() {
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork("trash-maintenance", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashMaintenanceWorker>(12, TimeUnit.HOURS).build())
        if (read().enabled) work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutomaticBackupWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()).build())
        else work.cancelUniqueWork(WORK)
    }
    fun now() = WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<AutomaticBackupWorker>().setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build())
    suspend fun runOnce(): Boolean = mutex.withLock {
        val config = read()
        if (!config.enabled) return@withLock true
        var created: Uri? = null
        try {
            check(context.daybook.repository.read().entries.none { it.status == "recording" }) { "A recording is active. The backup will retry." }
            val tree = Uri.parse(config.folder)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val name = "daybook-" + Instant.now().toString().replace(":", "-") + ".daybook"
            created = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name)
                ?: error("Backup destination unavailable")
            val pass = secret.read()
            context.daybook.backup.export(created, pass)
            currentCoroutineContext().ensureActive()
            if (read().id != config.id) return@withLock true
            val owned = (config.owned + created.toString()).distinct()
            // Only remove documents recorded as this app's own successful backups.
            owned.dropLast(7).forEach { old -> runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(old)) } }
            save(config.copy(lastSuccess = System.currentTimeMillis(), lastError = "", owned = owned.takeLast(7)))
            true
        } catch (e: CancellationException) {
            created?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }; throw e
        } catch (_: Exception) {
            created?.let { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            if (read().id == config.id) save(config.copy(lastError = "Backup could not finish. Stop any recording, check the selected folder and free space, then retry. Reconfigure if folder access or the saved passphrase was lost."))
            false
        }
    }
    companion object { const val WORK = "automatic-backup"; const val NOW = "automatic-backup-now" }
}
class AutomaticBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork() = withContext(Dispatchers.IO) {
        if (applicationContext.daybook.backupJobs.runOnce()) Result.success()
        else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
class TrashMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.daybook.purgeTrash()
        return Result.success()
    }
}
