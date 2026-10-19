package dev.daybook.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.*
import dev.daybook.app.*
import dev.daybook.app.capture.RecordingService
import dev.daybook.core.*
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DaybookRoot(graph: AppGraph, activity: MainActivity) {
    val state by graph.state.collectAsState()
    val preferences by graph.preferences.changes.collectAsState(initial = null)
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.initialized.await(); initialized = true }
    val settings = preferences ?: Settings()
    val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
    val colors = if (dark) darkColorScheme(primary = Color(0xFF9AD5BD), onPrimary = Color(0xFF123C2D), primaryContainer = Color(0xFF214F3D), onPrimaryContainer = Color(0xFFBAEAD0),
        secondary = Color(0xFFD6C9A0), secondaryContainer = Color(0xFF315746), onSecondaryContainer = Color(0xFFDDEADF), surfaceTint = Color(0xFF9AD5BD),
        background = Color(0xFF121A17), surface = Color(0xFF19231F), surfaceContainer = Color(0xFF24302A))
        else lightColorScheme(primary = Color(0xFF236958), onPrimary = Color.White, primaryContainer = Color(0xFFCEEBDA), onPrimaryContainer = Color(0xFF123C2D),
            secondary = Color(0xFF526654), secondaryContainer = Color(0xFFDDEADF), onSecondaryContainer = Color(0xFF173C30), tertiary = Color(0xFFAF6839), surfaceTint = Color(0xFF236958),
            background = Color(0xFFF8F5EF), surface = Color(0xFFFFFCF6), surfaceContainer = Color(0xFFEFEBE1))
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            if (!initialized || preferences == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Surface }
            val scope = rememberCoroutineScope()
            val snacks = remember { SnackbarHostState() }
            val lockEpoch by activity.lockEpoch.collectAsState()
            var unlocked by remember(lockEpoch) { mutableStateOf(false) }
            SideEffect {
                if (settings.biometric) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            fun authenticate() {
                val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
                })
                prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Daybook")
                    .setSubtitle("Your private journal")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build())
            }
            fun run(action: suspend () -> Unit) {
                scope.launch { try { graph.initialized.await(); action() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { snacks.showSnackbar(e.message?.take(500) ?: "That could not be completed. Please try again.") } }
            }
            var tab by remember { mutableIntStateOf(0) }
            var today by remember { mutableStateOf(LocalDate.now()) }
            var day by remember { mutableStateOf(today.toString()) }
            LaunchedEffect(Unit) {
                while (true) { delay(60_000); val next = LocalDate.now(); if (day == today.toString()) day = next.toString(); today = next }
            }
            var write by remember { mutableStateOf(false) }
            var voice by remember { mutableStateOf(false) }
            var photo by remember { mutableStateOf(false) }
            var selectedEntry by remember { mutableStateOf<String?>(null) }
            var newGoal by remember { mutableStateOf(false) }
            var logGoal by remember { mutableStateOf<String?>(null) }
            var selectedSuggestion by remember { mutableStateOf<Suggestion?>(null) }
            var backupMode by remember { mutableStateOf<String?>(null) }
            var password by remember { mutableStateOf<CharArray?>(null) }
            var busy by remember { mutableStateOf<String?>(null) }
            val active by RecordingService.active.collectAsState()
            val recordingError by RecordingService.lastError.collectAsState()
            LaunchedEffect(recordingError) { recordingError?.let { snacks.showSnackbar(it); RecordingService.lastError.value = null } }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                if (allowed) {
                    try { ContextCompat.startForegroundService(activity, Intent(activity, RecordingService::class.java).putExtra("day", day)); voice = false }
                    catch (_: Exception) { scope.launch { snacks.showSnackbar("Recording could not start. Keep Daybook open and try again.") } }
                } else scope.launch { snacks.showSnackbar("Microphone permission is needed for voice notes. You can still write and add photos.") }
            }
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
                if (uris.isNotEmpty()) run { busy = "Saving your photos…"; try { uris.forEach { withContext(Dispatchers.IO) { graph.media.importPhoto(it, day, graph.repository) } } } finally { busy = null } }
            }
            var cameraPath by remember { mutableStateOf<String?>(null) }
            val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
                val path = cameraPath
                if (path != null) run {
                    try { if (ok) withContext(Dispatchers.IO) { graph.media.importPhoto(android.net.Uri.fromFile(File(path)), day, graph.repository) } }
                    finally { File(path).delete(); cameraPath = null }
                }
            }
            val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                val pass = password; password = null
                if (uri == null) { pass?.fill('\u0000') } else if (pass != null) run {
                    busy = "Encrypting your backup…"
                    try { withContext(Dispatchers.IO) { graph.backup.export(uri, pass) }; snacks.showSnackbar("Encrypted backup saved.") } finally { busy = null }
                }
            }
            val restoreBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                val pass = password; password = null
                if (uri == null) { pass?.fill('\u0000') } else if (pass != null) run {
                    busy = "Validating and restoring backup…"
                    try {
                        withContext(Dispatchers.IO) { graph.backup.restore(uri, pass) }; graph.scheduler.reschedule()
                        graph.repository.read().entries.filter { it.status == "pending" }.forEach { graph.transcriptions.enqueue(it.id) }
                        snacks.showSnackbar("Journal restored. Enter your API key separately if needed.")
                    } finally { busy = null }
                }
            }
            val exportMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
                if (uri != null) run { withContext(Dispatchers.IO) { graph.backup.markdown(uri, day) }; snacks.showSnackbar("Day exported.") }
            }
            val capture by activity.capture.collectAsState()
            LaunchedEffect(capture, settings.onboarded, unlocked) {
                if (settings.onboarded && (!settings.biometric || unlocked) && capture.isNotEmpty()) {
                    tab = 0; day = LocalDate.now().toString()
                    when (capture) { "voice" -> voice = true; "write" -> write = true; "photo" -> photo = true }
                    activity.capture.value = ""
                }
            }
            if (!settings.onboarded) {
                Onboarding(settings, graph.model.ready(), onDownload = { graph.model.download() }) { chosen ->
                    run { graph.saveSettings(chosen.copy(onboarded = true))
                        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                }
                return@Surface
            }
            val cleanupInfos by remember(day) { WorkManager.getInstance(activity).getWorkInfosForUniqueWorkFlow("cleanup-$day") }.collectAsState(emptyList())
            val modelInfos by remember { WorkManager.getInstance(activity).getWorkInfosForUniqueWorkFlow("speech-model") }.collectAsState(emptyList())
            val cleanupInfo = cleanupInfos.firstOrNull { !it.state.isFinished } ?: cleanupInfos.firstOrNull()
            val modelInfo = modelInfos.firstOrNull { !it.state.isFinished } ?: modelInfos.firstOrNull()
            Scaffold(containerColor = colors.background, snackbarHost = { SnackbarHost(snacks) }, bottomBar = {
                NavigationBar(containerColor = colors.surface) {
                    val icons = listOf(Icons.Outlined.Today, Icons.Outlined.Flag, Icons.Outlined.History, Icons.Outlined.Settings)
                    listOf("Today", "Goals", "History", "Settings").forEachIndexed { i, title ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 0) day = LocalDate.now().toString() },
                            icon = { Icon(icons[i], title) }, label = { Text(title) })
                    }
                }
            }) { padding ->
                when (tab) {
                    0 -> TodayScreen(state, day, active, cleanupInfo, Modifier.padding(padding),
                        onDate = { day = it }, onWrite = { write = true }, onVoice = { voice = true }, onPhoto = { photo = true },
                        onStop = { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.STOP)) },
                        onEntry = { selectedEntry = it }, onCleanup = { graph.cleanup.enqueue(day, force = true) },
                        onSuggestion = { selectedSuggestion = it }, onReject = { run { graph.repository.reject(it) } },
                        onUndo = { run { graph.repository.undo(it) } }, onLog = { logGoal = it }, onGoals = { tab = 1 })
                    1 -> GoalsScreen(state, day, Modifier.padding(padding), onNew = { newGoal = true }, onLog = { logGoal = it },
                        onArchive = { id, archived -> run { graph.repository.change { s -> s.copy(goals = s.goals.map { if (it.id == id) it.copy(archived = archived) else it }) } } })
                    2 -> HistoryScreen(state, Modifier.padding(padding)) { selected -> day = selected; tab = 0 }
                    3 -> SettingsScreen(settings, graph.keys.hasKey(), graph.model.ready(), modelInfo, graph.media.usage(), graph.model.file.length(),
                        day, Modifier.padding(padding), onSave = { s -> run {
                            if (s.biometric) require(BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) { "Set up a screen lock or strong biometric on this phone first." }
                            graph.saveSettings(s); snacks.showSnackbar("Settings saved.")
                        } }, onKey = { key -> run { withContext(Dispatchers.IO) { graph.keys.save(key) }; snacks.showSnackbar(if (key.isBlank()) "API key removed." else "API key saved.") } },
                        onDownload = { graph.model.download() }, onDeleteModel = { graph.model.delete() },
                        onPauseDownload = { graph.model.cancel() },
                        onBackup = { backupMode = "export" }, onRestore = { backupMode = "restore" },
                        onMarkdown = { exportMarkdown.launch("daybook-$day.md") }, onPermissions = {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }, onBattery = { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}"))) })
                }
            }
            if (write) WriteDialog(onDismiss = { write = false }) { text -> run { graph.repository.addText(day, text); write = false } }
            if (voice) AlertDialog(onDismissRequest = { voice = false }, title = { Text("Capture a thought") },
                text = { Text(if (active) "You are already recording. Stop from Today or the recording notification."
                    else "Speak naturally. Your original audio is kept here and transcribed on this phone. Recording continues with the screen locked, up to 30 minutes.${if (!graph.model.ready()) "\n\nDownload the English speech model in Settings to transcribe saved recordings." else ""}") },
                confirmButton = { Button(enabled = !active, onClick = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Start recording") } },
                dismissButton = { TextButton(onClick = { voice = false }) { Text("Cancel") } })
            if (photo) AlertDialog(onDismissRequest = { photo = false }, title = { Text("Add a photo") },
                text = { Text("Attach a meal, a place, or something you want to remember. Attached photos are included when you clean up this day with OpenAI.") },
                confirmButton = { Button(onClick = {
                    photo = false
                    run {
                        val file = File(activity.cacheDir, "camera/${newId()}.jpg").apply { parentFile!!.mkdirs(); createNewFile() }
                        cameraPath = file.path
                        camera.launch(FileProvider.getUriForFile(activity, "${activity.packageName}.files", file))
                    }
                }) { Text("Take photo") } },
                dismissButton = { TextButton(onClick = { photo = false; picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Choose photos") } })
            selectedEntry?.let { id -> state.entries.firstOrNull { it.id == id }?.let { entry ->
                EntryDialog(entry, state, graph, onDismiss = { selectedEntry = null },
                    onSave = { text -> run { graph.repository.editEntry(id, text); selectedEntry = null } },
                    onDelete = { run { graph.deleteEntry(id); selectedEntry = null } },
                    onRetry = { graph.transcriptions.enqueue(id) })
            } }
            if (newGoal) GoalDialog(onDismiss = { newGoal = false }) { goal -> run { graph.repository.addGoal(goal); newGoal = false } }
            logGoal?.let { id -> state.goals.firstOrNull { it.id == id }?.let { goal ->
                ActivityDialog(goal, day, onDismiss = { logGoal = null }) { amount, note ->
                    run { graph.repository.logActivity(Activity(goalId = id, day = day, amount = amount, note = note)); logGoal = null }
                }
            } }
            selectedSuggestion?.let { suggestion ->
                val goal = state.goals.firstOrNull { it.id == suggestion.goalId }
                if (goal != null) SuggestionDialog(suggestion, goal, state, onDismiss = { selectedSuggestion = null }) { amount, note ->
                    run { graph.repository.confirm(suggestion.id, amount, note); selectedSuggestion = null }
                }
            }
            backupMode?.let { mode ->
                BackupDialog(mode, onDismiss = { backupMode = null }) { pass ->
                    password?.fill('\u0000'); password = pass; backupMode = null
                    if (mode == "export") exportBackup.launch("daybook-${LocalDate.now()}.daybook") else restoreBackup.launch(arrayOf("*/*"))
                }
            }
            if (busy != null) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(busy!!) },
                text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(12.dp)); Text("Keep Daybook open until this finishes.") } })
            if (settings.biometric && !unlocked) androidx.compose.ui.window.Dialog(
                onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
                    securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
                Surface(Modifier.fillMaxSize(), color = colors.background) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Lock, null, Modifier.size(48.dp), tint = colors.primary)
                        Text("A space just for you", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(20.dp)); Button(onClick = ::authenticate) { Text("Unlock journal") }
                    }
                }
            }
        }
    }
}

@Composable fun PageTitle(kicker: String, title: String, subtitle: String = "") {
    Text(kicker.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable fun SectionCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}
fun amountText(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(java.util.Locale.US, value)
fun byteText(value: Long): String = "%.1f MB".format(java.util.Locale.US, value / 1048576.0)
