package dev.daybook.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.work.*
import dev.daybook.app.*
import dev.daybook.app.capture.RecordingService
import dev.daybook.app.data.*
import dev.daybook.core.*
import kotlinx.coroutines.*
import java.time.*

@Composable fun ReleaseHub(graph: AppGraph, activity: MainActivity, initial: String, onDismiss: () -> Unit, onEntry: (String) -> Unit) {
    val state by graph.state.collectAsState()
    val raw by graph.rawState.collectAsState()
    val settings by graph.settings.collectAsState()
    val backup by graph.backupJobs.changes.collectAsState(initial = BackupConfig())
    var page by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var askPassword by remember { mutableStateOf(false) }
    var showPolicy by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var purge by remember { mutableStateOf<Entry?>(null) }
    val scope = rememberCoroutineScope()
    fun task(block: suspend () -> Unit) { scope.launch {
        try { error = ""; withContext(Dispatchers.IO) { block() } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message?.take(300) ?: "That did not finish. Please retry." }
    } }
    DisposableEffect(Unit) { onDispose { pendingPassword?.fill('\u0000') } }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val pass = pendingPassword; pendingPassword = null
        if (uri == null) pass?.fill('\u0000')
        else if (pass != null) task { graph.backupJobs.configure(uri, pass); message = "Automatic backups enabled. The first backup is queued." }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val lightBars = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Text("Back to journal") }
                when (page) {
                    "backup" -> {
                        PageTitle("Keep a safe copy", "Backups & recovery")
                        SectionCard {
                            Text("Automatic encrypted backups", style = MaterialTheme.typography.titleLarge)
                            Text(if (backup.enabled) "Daily · keeps your 7 latest successful backups" else "Choose a folder and passphrase to get started.")
                            Text("Includes notes, Recently Deleted, original audio, photos, and weekly reflections. Keys, model files, and the scheduled-backup passphrase are excluded.")
                            Text("A folder from a cloud storage app may sync the encrypted backup. Keep a copy of your passphrase outside this phone; it cannot be recovered.", style = MaterialTheme.typography.bodySmall)
                            Text("Last successful backup: " + if (backup.lastSuccess == 0L) "Not yet" else Instant.ofEpochMilli(backup.lastSuccess).atZone(ZoneId.systemDefault()).toLocalDateTime().toString())
                            if (backup.lastError.isNotBlank()) Text(backup.lastError, color = MaterialTheme.colorScheme.error)
                            val work by remember { WorkManager.getInstance(activity).getWorkInfosForUniqueWorkFlow(BackupJobs.NOW) }.collectAsState(emptyList())
                            if (work.any { !it.state.isFinished }) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Backup queued or running. Scheduling can be delayed by Android.") }
                            Button(onClick = { askPassword = true }, modifier = Modifier.fillMaxWidth()) { Text(if (backup.enabled) "Change backup folder or passphrase" else "Set up automatic backups") }
                            if (backup.enabled) {
                                OutlinedButton(onClick = { graph.backupJobs.now() }) { Text("Back up now") }
                                TextButton(onClick = { task { graph.backupJobs.disable(); message = "Automatic backups disabled. Existing backup files remain in your folder." } }) { Text("Disable automatic backups") }
                            }
                        }
                        SectionCard {
                            Text("Recently Deleted", style = MaterialTheme.typography.titleLarge)
                            Text("Notes and their media stay recoverable for 30 days. Confirmed goal activity remains. Older backup copies are independent.")
                            OutlinedButton(onClick = { page = "trash" }) { Text("Recently Deleted (${raw.entries.count { it.deletedAt > 0 }})") }
                        }
                    }
                    "trash" -> {
                        PageTitle("A second chance", "Recently Deleted")
                        Text("Restore a note or remove it permanently. Expired notes are removed after 30 days when Android allows background work.")
                        TextButton(onClick = { page = "backup" }) { Text("Backups & recovery") }
                        val deleted = raw.entries.filter { it.deletedAt > 0 }.sortedByDescending { it.deletedAt }
                        if (deleted.isEmpty()) Text("No deleted moments.")
                        deleted.forEach { entry -> SectionCard {
                            Text(entry.day + " · " + entry.kind, style = MaterialTheme.typography.labelMedium)
                            Text(entry.text.take(200).ifBlank { "Original ${entry.kind} attachment" })
                            Text("Deleted " + Instant.ofEpochMilli(entry.deletedAt).atZone(ZoneId.systemDefault()).toLocalDate(), style = MaterialTheme.typography.bodySmall)
                            Row {
                                Button(onClick = { task { graph.repository.restoreEntry(entry.id) } }) { Text("Restore note") }
                                TextButton(onClick = { purge = entry }) { Text("Delete permanently") }
                            }
                        } }
                    }
                    "processing" -> {
                        PageTitle("On this phone", "Processing & downloads")
                        Text("Audio is saved before transcription. Cancelling a task keeps your original note and recordings.")
                        ProcessingContent(graph, activity)
                        SectionCard {
                            ToggleRow("Wi-Fi-only model downloads", "Uses an unmetered connection. Applies when starting or resuming a download.",
                                settings.wifiOnlyDownloads, { task { graph.saveSettings(settings.copy(wifiOnlyDownloads = it)) } })
                            ToggleRow("Create reflections only while charging", "Daily reviews wait for a charger, including manual requests.",
                                settings.cleanupChargingOnly, { task { graph.saveSettings(settings.copy(cleanupChargingOnly = it)) } })
                        }
                    }
                    "weekly" -> WeeklyReflectionContent(graph, state, onEntry)
                    "privacy" -> {
                        PageTitle("Your choices", "Privacy & feedback")
                        SectionCard {
                            Text("Private by choice", style = MaterialTheme.typography.titleLarge)
                            Text("Set ‘Keep this entry on-device’ inside a note to exclude it from future OpenAI requests. You can also exclude a note’s photos. Local reviews and your encrypted backups can still include it.")
                            Text("Manual OpenAI reflections show a preview before sending. Automatic nightly reviews use your saved note choices and cloud consent. Previously sent content cannot be retracted.")
                        }
                        SectionCard {
                            Text("Privacy policy", style = MaterialTheme.typography.titleLarge)
                            if (BuildConfig.DEVELOPER_NAME.isNotBlank()) Text("Developer: ${BuildConfig.DEVELOPER_NAME}")
                            Text("Read how journal data, local models, optional cloud reviews, backups, deletion, and feedback work.")
                            OutlinedButton(onClick = { showPolicy = !showPolicy }) { Text(if (showPolicy) "Hide privacy policy" else "Read privacy policy") }
                            if (showPolicy) SelectionContainer { Text(remember { activity.assets.open("privacy-policy.txt").bufferedReader().use { it.readText() } }, style = MaterialTheme.typography.bodySmall) }
                            TextButton(onClick = { showLicenses = !showLicenses }) { Text("Open-source licenses") }
                            if (showLicenses) listOf("third-party-notices.txt", "whisper.cpp-LICENSE.txt", "llama.cpp-LICENSE.txt", "Apache-2.0.txt", "speech-model-licenses.txt").forEach { name ->
                                SelectionContainer { Text(remember(name) { activity.assets.open(name).bufferedReader().use { it.readText() } }, style = MaterialTheme.typography.bodySmall) }
                            }
                            if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) OutlinedButton(onClick = { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL))) }) { Text("Open public privacy policy") }
                            Text(if (BuildConfig.SUPPORT_EMAIL.isNotBlank()) "Contact: ${BuildConfig.SUPPORT_EMAIL}" else "This beta has no configured public support address. Use the feedback export below.")
                        }
                        FeedbackContent(activity, graph)
                    }
                }
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (askPassword) AutomaticBackupDialog(onDismiss = { askPassword = false }) { pass ->
        pendingPassword?.fill('\u0000'); pendingPassword = pass; askPassword = false; folderPicker.launch(null)
    }
    purge?.let { entry -> AlertDialog(onDismissRequest = { purge = null }, title = { Text("Permanently delete this moment?") },
        text = { Text("Its note, audio, photos, and related daily reviews will be removed from this app. Existing exported backups are separate copies. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { purge = null; task { graph.purgeEntry(entry.id) } }) { Text("Delete permanently") } },
        dismissButton = { TextButton(onClick = { purge = null }) { Text("Keep in Recently Deleted") } }) }
}

@Composable private fun AutomaticBackupDialog(onDismiss: () -> Unit, onContinue: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }; var again by remember { mutableStateOf("") }; var rememberSecret by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Protect automatic backups") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Use a passphrase of at least 10 characters. Write it down somewhere safe. Choose the backup folder next.")
            OutlinedTextField(pass, { pass = it }, label = { Text("Backup passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(again, { again = it }, label = { Text("Repeat passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            ToggleRow("Remember for background backups", "Encrypt this passphrase with Android Keystore on this phone. It is excluded from backups and is removed when automatic backups are disabled.",
                rememberSecret, { rememberSecret = it })
        }
    }, confirmButton = { Button(enabled = pass.length >= 10 && pass == again && rememberSecret, onClick = {
        val chars = pass.toCharArray(); pass = ""; again = ""; onContinue(chars)
    }) { Text("Choose backup folder") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable private fun ProcessingContent(graph: AppGraph, activity: MainActivity) {
    val state by graph.state.collectAsState()
    val settings by graph.settings.collectAsState()
    val active by RecordingService.active.collectAsState()
    val scope = rememberCoroutineScope()
    if (active) SectionCard {
        Text("Recording · original audio is being saved")
        OutlinedButton(onClick = { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.STOP)) }) { Text("Stop and save recording") }
    }
    val entries = state.entries.filter { it.kind == "voice" && it.status != "ready" && it.status != "recording" }.sortedByDescending { it.created }
    if (entries.isEmpty() && !active) Text("Your saved voice notes are up to date.")
    entries.take(50).forEach { entry ->
        val infos by remember(entry.id) { WorkManager.getInstance(activity).getWorkInfosForUniqueWorkFlow("transcribe-${entry.id}") }.collectAsState(emptyList())
        val work = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
        SectionCard {
            Text(entry.day + " · Voice note", style = MaterialTheme.typography.titleSmall)
            Text(when (work?.state) { WorkInfo.State.RUNNING -> "Transcribing on this phone"; WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Saved · waiting to transcribe"; else -> "Saved · needs attention" })
            if (entry.error.isNotBlank()) Text(entry.error, style = MaterialTheme.typography.bodySmall)
            if (work?.state?.isFinished == false) TextButton(onClick = { scope.launch { graph.cancelTranscription(entry.id) } }) { Text("Cancel transcription") }
            else OutlinedButton(onClick = { scope.launch { graph.retryTranscription(entry.id) } }) { Text("Retry transcription") }
        }
    }
    val reviews by remember { WorkManager.getInstance(activity).getWorkInfosByTagFlow("cleanup-review") }.collectAsState(emptyList())
    reviews.filter { it.state !in setOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED) }.take(30).forEach { work ->
        val day = work.tags.firstOrNull { it.startsWith("cleanup-day:") }?.substringAfter(':') ?: return@forEach
        SectionCard {
            Text("Daily review · $day", style = MaterialTheme.typography.titleSmall)
            Text(if (work.state == WorkInfo.State.RUNNING) work.progress.getString("message") ?: "Creating review…" else if (work.state == WorkInfo.State.FAILED) work.outputData.getString("error") ?: "Review needs attention"
                else if (settings.cleanupChargingOnly) "Queued · connect a charger" else "Queued · waiting for Android or a connection")
            if (!work.state.isFinished) TextButton(onClick = { WorkManager.getInstance(activity).cancelUniqueWork("cleanup-$day") }) { Text("Cancel reflection") }
            else OutlinedButton(onClick = { graph.cleanup.enqueue(day, force = true, provider = settings.reviewProvider, replace = true, charging = settings.cleanupChargingOnly) }) { Text("Retry reflection") }
        }
    }
    (SpeechCatalog.choices.map { SpeechCatalog.workName(it.id) to (it.title + " · " + byteText(it.bytes)) } + (dev.daybook.app.ai.LocalModel.WORK to "Local reviews · 1.1 GB")).forEach { (name, title) ->
        val works by remember(name) { WorkManager.getInstance(activity).getWorkInfosForUniqueWorkFlow(name) }.collectAsState(emptyList())
        val work = works.firstOrNull { !it.state.isFinished } ?: works.firstOrNull()
        val choice = SpeechCatalog.choices.find { SpeechCatalog.workName(it.id) == name }
        val ready = if (choice != null) graph.model.ready(choice.id) else graph.localModel.ready()
        SectionCard {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (ready) "Ready offline" else when (work?.state) {
                WorkInfo.State.RUNNING -> "Downloading · ${work.progress.getInt("percent", 0)}%"
                WorkInfo.State.ENQUEUED -> if (settings.wifiOnlyDownloads) "Queued · waiting for Wi-Fi or retry" else "Queued · waiting for connection or retry"
                WorkInfo.State.FAILED -> work.outputData.getString("error") ?: "Download needs attention"
                else -> "Not downloaded"
            })
            if (!ready) {
                if (work?.state?.isFinished == false) OutlinedButton(onClick = { if (choice != null) graph.model.cancel(choice.id) else graph.localModel.pause() }) { Text("Pause download") }
                else OutlinedButton(onClick = { if (choice != null) graph.model.download(choice.id, settings.wifiOnlyDownloads) else graph.localModel.download(settings.wifiOnlyDownloads) }) { Text("Download or resume") }
            }
        }
    }
}

private fun countLabel(count: Int, singular: String) = "$count $singular" + if (count == 1) "" else "s"

@Composable private fun WeeklyReflectionContent(graph: AppGraph, state: Snapshot, onEntry: (String) -> Unit) {
    var week by remember { mutableStateOf(WeeklyRules.start(LocalDate.now())) }
    val digest = remember(state, week) { WeeklyRules.digest(state, week) }
    val saved = state.reflections.find { it.week == week.toString() }
    var text by remember(week, saved?.text) { mutableStateOf(saved?.text.orEmpty()) }
    var message by remember(week) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    PageTitle("A little perspective", "Your week")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { week = week.minusWeeks(1) }) { Icon(Icons.Outlined.ChevronLeft, "Previous week") }
        Text("$week – ${week.plusDays(6)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { week = week.plusWeeks(1) }, enabled = week < WeeklyRules.start(LocalDate.now())) { Icon(Icons.Outlined.ChevronRight, "Next week") }
    }
    SectionCard {
        Text("${countLabel(digest.journalDays, "journal day")} · ${countLabel(digest.entries.size, "note")} · ${countLabel(digest.photos, "photo")}")
        Text("${countLabel(digest.goals.size, "distinct goal")} worked on · ${countLabel(digest.checkins, "goal/day check-in")}")
        Text("Only confirmed activity counts. Plans and AI suggestions do not add progress.", style = MaterialTheme.typography.bodySmall)
        digest.goals.forEach { goal -> Text("${goal.goal.title} · ${countLabel(goal.workedDays, "day")}" + if (goal.amount > 0) " · ${amountText(goal.amount)} ${goal.goal.unit} logged" else " · partial effort") }
    }
    SectionCard {
        Text("From your week", style = MaterialTheme.typography.titleLarge)
        digest.entries.groupBy { it.day }.forEach { (day, entries) ->
            Text(day, style = MaterialTheme.typography.labelLarge)
            val review = state.reviews.filter { it.day == day && it.fingerprint == JournalRules.fingerprint(state, day) }.maxByOrNull { it.created }
            if (review != null) Text(review.summary)
            entries.take(3).forEach { entry -> TextButton(onClick = { onEntry(entry.id) }) {
                Text(entry.text.take(180).ifBlank { "Open ${entry.kind} moment" })
            } }
        }
        if (digest.entries.isEmpty()) Text("No journal entries for this week.")
    }
    SectionCard {
        Text("What felt good? What comes next?", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(text, { if (it.length <= 10000) text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), label = { Text("Your weekly reflection") })
        Button(onClick = { scope.launch {
            try {
                val item = WeekReflection(id = saved?.id ?: newId(), week = week.toString(), text = text.trim())
                graph.repository.change { it.copy(reflections = it.reflections.filterNot { r -> r.week == item.week } + item) }
                message = "Reflection saved on this phone."
            } catch (_: Exception) { message = "Could not save. Please retry." }
        } }) { Text("Save weekly reflection") }
        if (message.isNotBlank()) Text(message)
    }
    SectionCard {
        Text("Still open", style = MaterialTheme.typography.titleLarge)
        state.items.filter { !it.archived && !it.done }.take(15).forEach { Text((if (it.kind == "todo") "To-do · " else "Idea · ") + it.title) }
        Text("These are your current open items, including ideas saved outside this week.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun FeedbackContent(activity: MainActivity, graph: AppGraph) {
    var message by remember { mutableStateOf("") }; var include by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { diagnostics = withContext(Dispatchers.IO) { SupportDiagnostics.text(activity, graph) } }
    SectionCard {
        Text("Send feedback", style = MaterialTheme.typography.titleLarge)
        Text("Describe a problem or suggest an improvement. Nothing is sent automatically. Review the draft before choosing where to share it.")
        OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Feedback draft") }, minLines = 3)
        ToggleRow("Include technical details", "App version, Android/device model, free space, and whether models are ready. No notes, recordings, photos, keys, file paths, or identifiers.", include, { include = it })
        if (include) SelectionContainer { Text(diagnostics, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(enabled = message.isNotBlank(), onClick = {
            val body = message + if (include) "\n\n" + diagnostics else ""
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Daybook feedback")
                .putExtra(Intent.EXTRA_TEXT, body)
            if (BuildConfig.SUPPORT_EMAIL.isNotBlank()) send.putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.SUPPORT_EMAIL))
            activity.startActivity(Intent.createChooser(send, "Review and share feedback"))
        }) { Text("Review & share feedback") }
    }
}

@Composable fun CloudPreviewDialog(state: Snapshot, day: String, allowed: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit, onSettings: () -> Unit, graph: AppGraph) {
    val entries = ReviewPrivacy.entries(state, day, "openai")
    val photos = ReviewPrivacy.photos(state, day)
    val context = ReviewPrivacy.cloudContext(state)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Create a reflection with OpenAI?") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This sends the content below directly to OpenAI using your API key. Audio stays on this phone. Provider retention rules can apply.")
            Text("${entries.size} notes · ${photos.size} photos · $day", style = MaterialTheme.typography.titleSmall)
            Text("Entry times, time zones, and record references are included to link results back to sources.", style = MaterialTheme.typography.bodySmall)
            entries.forEach { Text(Instant.ofEpochMilli(it.created).atZone(ZoneId.of(it.zone)).toString(), style = MaterialTheme.typography.labelSmall); Text(it.text.ifBlank { "(Photo note)" }) }
            photos.forEach { coil.compose.AsyncImage(graph.media.file(it.path), "Photo selected for cloud review", Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
            Text("Goal context", style = MaterialTheme.typography.titleSmall)
            context.goals.filter { !it.archived }.forEach { Text("${it.title} · ${it.target} ${it.unit} · ${it.period}", style = MaterialTheme.typography.bodySmall) }
            Text("Confirmed activity context", style = MaterialTheme.typography.titleSmall)
            context.activities.filter { it.day == day && !it.undone }.forEach { Text("${it.note} · ${amountText(it.amount)}", style = MaterialTheme.typography.bodySmall) }
            Text("Open list titles", style = MaterialTheme.typography.titleSmall)
            context.items.filter { !it.archived && !it.done }.forEach { Text(it.kind + " · " + it.title, style = MaterialTheme.typography.bodySmall) }
            Text("Edit a note to keep it on-device or exclude its photos. Nightly reviews follow those saved choices.", style = MaterialTheme.typography.bodySmall)
            if (!allowed) Text("Enable Allow OpenAI reflections in Settings before continuing.")
            if (entries.isEmpty()) Text("No eligible notes. Choose on-device reflections or change note privacy.")
        }
    }, confirmButton = { if (allowed) Button(onClick = onConfirm, enabled = entries.isNotEmpty()) { Text("Create reflection") }
        else TextButton(onClick = onSettings) { Text("Open Settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
