package dev.daybook.app.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.*
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.daybook.app.capture.RecordingSession
import dev.daybook.core.*
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun DaybookRoot(graph: AppGraph, activity: MainActivity) {
    val state by graph.state.collectAsState()
    val rawState by graph.rawState.collectAsState()
    val preferences by graph.preferences.changes.collectAsState(initial = null)
    var initialized by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { graph.initialized.await(); initialized = true }
    val settings = preferences ?: Settings()
    val dark = settings.theme == "dark" || (settings.theme == "system" && isSystemInDarkTheme())
    val reducedMotion = settings.reduceMotion || android.provider.Settings.Global.getFloat(activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    DaybookTheme(dark, reducedMotion) {
        val colors = MaterialTheme.colorScheme
        val backdrop = androidx.compose.ui.graphics.Brush.linearGradient(if (dark)
            listOf(Color(0xFF1B1826), Color(0xFF211B2D), Color(0xFF191622))
            else listOf(Color(0xFFF0F7FF), Color(0xFFF5F0FF), Color(0xFFFCF6F6)))
        Surface(Modifier.fillMaxSize().background(backdrop), color = Color.Transparent, contentColor = colors.onSurface) {
            if (!initialized || preferences == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return@Surface }
            val scope = rememberCoroutineScope()
            val snacks = remember { SnackbarHostState() }
            val unlocked by activity.journalLock.unlocked.collectAsState()
            LaunchedEffect(settings.biometric) { activity.configureJournalLock(settings.biometric) }
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
                if (settings.biometric) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
            var toolsPage by remember { mutableStateOf<String?>(null) }
            var cloudPreviewDay by remember { mutableStateOf<String?>(null) }
            var write by remember { mutableStateOf(false) }
            var recordingSheet by rememberSaveable { mutableStateOf(false) }
            var listKind by remember { mutableStateOf<String?>(null) }
            var editingItem by remember { mutableStateOf<ListItem?>(null) }
            var selectedProposal by remember { mutableStateOf<Proposal?>(null) }
            var initiallySuggestions by remember { mutableStateOf(false) }
            var photo by remember { mutableStateOf(false) }
            var selectedEntry by remember { mutableStateOf<String?>(null) }
            var newGoal by remember { mutableStateOf(false) }
            var logGoal by remember { mutableStateOf<String?>(null) }
            var selectedSuggestion by remember { mutableStateOf<Suggestion?>(null) }
            var backupMode by remember { mutableStateOf<String?>(null) }
            var password by remember { mutableStateOf<CharArray?>(null) }
            var busy by remember { mutableStateOf<String?>(null) }
            val active by RecordingService.active.collectAsState()
            val recordingSession by RecordingService.session.collectAsState()
            LaunchedEffect(active) { if (active) recordingSheet = true }
            val recordingError by RecordingService.lastError.collectAsState()
            LaunchedEffect(recordingError) { recordingError?.let { snacks.showSnackbar(it); RecordingService.lastError.value = null } }
            val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            var recordingDay by rememberSaveable { mutableStateOf(day) }
            val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                if (allowed) {
                    try { ContextCompat.startForegroundService(activity, Intent(activity, RecordingService::class.java).putExtra("day", recordingDay)) }
                    catch (_: Exception) { RecordingService.session.value = RecordingSession(phase = "error", previewStatus = "Recording could not start. Keep Daybook open and try again.") }
                } else RecordingService.session.value = RecordingSession(phase = "error", previewStatus = "Microphone permission is needed. Allow it in Android app settings, then try again.")
            }
            fun startRecording() {
                recordingSheet = true
                if (!active) {
                    recordingDay = day
                    RecordingService.session.value = RecordingSession(phase = "starting")
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
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
                        withContext(Dispatchers.IO) { graph.backup.restore(uri, pass) }
                        graph.cleanup.reconfigure(graph.preferences.read().reviewProvider)
                        graph.scheduler.reschedule()
                        graph.repository.read().entries.filter { it.status == "pending" && it.deletedAt == 0L }.forEach { graph.transcriptions.enqueue(it.id) }
                        snacks.showSnackbar("Journal restored. Enter your API key separately if needed.")
                    } finally { busy = null }
                }
            }
            val exportMarkdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
                if (uri != null) run { withContext(Dispatchers.IO) { graph.backup.markdown(uri, day) }; snacks.showSnackbar("Day exported.") }
            }
            val capture by activity.capture.collectAsState()
            val lifecycleState by activity.lifecycle.currentStateFlow.collectAsState()
            LaunchedEffect(capture, settings.onboarded, settings.biometric, unlocked, lifecycleState) {
                if (settings.onboarded && (!settings.biometric || unlocked) && capture.isNotEmpty() && lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    tab = 0; day = LocalDate.now().toString()
                    when (capture) {
                        "record", "voice" -> startRecording()
                        "write" -> write = true
                        "photo" -> photo = true
                    }
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
            val localInfos by remember { WorkManager.getInstance(activity).getWorkInfosForUniqueWorkFlow(dev.daybook.app.ai.LocalModel.WORK) }.collectAsState(emptyList())
            var localRevision by remember { mutableIntStateOf(0) }
            val localReady = remember(localInfos, localRevision) { graph.localModel.ready() }
            val localInfo = localInfos.firstOrNull { !it.state.isFinished } ?: localInfos.firstOrNull()
            val cleanupInfo = cleanupInfos.firstOrNull { !it.state.isFinished } ?: cleanupInfos.firstOrNull()
            var modelRevision by remember { mutableIntStateOf(0) }
            val modelReady = remember(modelInfos, modelRevision) { graph.model.ready() }
            val modelInfo = modelInfos.firstOrNull { !it.state.isFinished } ?: modelInfos.firstOrNull()
            Scaffold(containerColor = Color.Transparent, contentColor = colors.onSurface, snackbarHost = { SnackbarHost(snacks) }, bottomBar = {
                Surface(Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(36.dp), color = colors.surface.copy(alpha = 0.97f),
                    shadowElevation = 8.dp, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f))) {
                NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0), modifier = Modifier.heightIn(min = 80.dp)) {
                    val destinations = listOf(Triple(0, "Today", Icons.Outlined.Today), Triple(1, "Goals", Icons.Outlined.Flag),
                        Triple(4, "Lists", Icons.Outlined.Checklist), Triple(2, "History", Icons.Outlined.History), Triple(3, "Settings", Icons.Outlined.Settings))
                    destinations.forEach { (i, title, icon) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 0) day = LocalDate.now().toString() },
                            icon = { Icon(icon, null, Modifier.size(22.dp)) }, label = { Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
                    }
                }
                }
            }) { padding ->
                AnimatedContent(targetState = tab, modifier = Modifier.fillMaxSize(), transitionSpec = {
                    if (reducedMotion) EnterTransition.None togetherWith ExitTransition.None
                    else (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 24 }) togetherWith fadeOut(tween(120))
                }, label = "navigation transition") { destination ->
                when (destination) {
                    0 -> TodayScreen(state, day, active, cleanupInfo, Modifier.padding(padding),
                        onDate = { day = it }, onWrite = { write = true }, onVoice = ::startRecording, onPhoto = { photo = true },
                        onStop = { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.STOP)) },
                        onEntry = { selectedEntry = it }, onCleanup = { if (settings.reviewProvider == "openai") cloudPreviewDay = day else graph.cleanup.enqueue(day, force = true, provider = settings.reviewProvider, charging = settings.cleanupChargingOnly) },
                        onSuggestion = { selectedSuggestion = it }, onReject = { run { graph.repository.reject(it) } },
                        onUndo = { run { graph.repository.undo(it) } }, onLog = { logGoal = it }, onGoals = { tab = 1 }, onLists = { initiallySuggestions = true; tab = 4 })
                    1 -> GoalsScreen(state, day, Modifier.padding(padding), onNew = { newGoal = true }, onLog = { logGoal = it },
                        onArchive = { id, archived -> run { graph.repository.change { s -> s.copy(goals = s.goals.map { if (it.id == id) it.copy(archived = archived) else it }) } } })
                    2 -> HistoryScreen(state, Modifier.padding(padding), onEntry = { selectedEntry = it }, today = today, onWeekly = { toolsPage = "weekly" }) { selected -> day = selected; tab = 0 }
                    4 -> ListsScreen(state, Modifier.padding(padding), initiallySuggestions,
                        onNew = { listKind = it; editingItem = null }, onEdit = { editingItem = it; listKind = it.kind },
                        onUpdate = { item -> run { graph.repository.saveItem(item) } },
                        onReview = { selectedProposal = it }, onReject = { run { graph.repository.rejectProposal(it) } },
                        onUndo = { run { graph.repository.undoProposal(it) } }, onEntry = { selectedEntry = it })
                    3 -> {
                        var hasKey by remember { mutableStateOf(graph.keys.hasKey()) }
                        SettingsScreen(settings, hasKey, modelReady, modelInfo, graph.media.usage(), graph.model.file.length(),
                            day, Modifier.padding(padding), onSave = { s ->
                                if (s.biometric) require(BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) { "Set up a screen lock or strong biometric on this phone first." }
                                graph.saveSettings(s)
                            }, onKey = { key -> withContext(Dispatchers.IO) { graph.keys.save(key) }; hasKey = graph.keys.hasKey() },
                            onDownload = { graph.model.download() }, onDeleteModel = { graph.model.delete(); modelRevision++ }, onPauseDownload = { graph.model.cancel() },
                            onBackup = { backupMode = "export" }, onRestore = { backupMode = "restore" },
                            onMarkdown = { exportMarkdown.launch("daybook-$day.md") }, onPermissions = {
                                activity.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
                            }, onBattery = { activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${activity.packageName}"))) },
                            onAddWidget = {
                                val manager = android.appwidget.AppWidgetManager.getInstance(activity)
                                if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(android.content.ComponentName(activity, dev.daybook.app.widget.RecordWidget::class.java), null, null)
                                else scope.launch { snacks.showSnackbar("Touch and hold your home screen, then choose Widgets → Daybook → Daybook Record.") }
                            }, localReady = localReady, localWork = localInfo, localBytes = graph.localModel.usage(),
                        onLocalDownload = { graph.localModel.download() }, onLocalPause = { graph.localModel.pause() },
                        onLocalDelete = { graph.localModel.delete(); localRevision++ }, onTools = { toolsPage = it })
                    }
                }
            }
            }
            if (write) WriteDialog(onDismiss = { write = false }) { text -> run { graph.repository.addText(day, text); write = false } }
            if (recordingSheet) {
                val finalEntry = state.entries.find { it.id == recordingSession.entryId }
                val display = when {
                    finalEntry?.status == "ready" -> recordingSession.copy(transcript = finalEntry.text, previewStatus = "Final transcript ready. Original audio is preserved.")
                    !active && finalEntry?.status == "error" -> recordingSession.copy(previewStatus = "Audio saved. ${finalEntry.error}")
                    else -> recordingSession
                }
                RecordingScreen(display, active, modelReady, onStart = ::startRecording,
                    onStop = { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.STOP)) },
                    onDismiss = { recordingSheet = false },
                    onPause = { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.PAUSE)) },
                    onBookmark = { activity.startService(Intent(activity, RecordingService::class.java).setAction(RecordingService.BOOKMARK)) }, reduceMotion = reducedMotion)
            }
            if (photo) AlertDialog(onDismissRequest = { photo = false }, title = { Text("Add a photo") },
                text = { Text("Attach a meal, a place, or something you want to remember. Photos can be included in OpenAI reviews. Open a saved note to keep it on-device or exclude its photos.") },
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
                    onRetry = { run { graph.retryTranscription(id) } })
            } }
            listKind?.let { kind ->
                ListItemDialog(kind, initial = editingItem, onDismiss = { listKind = null; editingItem = null }) { title, details ->
                    val item = editingItem?.copy(title = title, details = details) ?: ListItem(kind = kind, title = title, details = details)
                    run { graph.repository.saveItem(item); listKind = null; editingItem = null }
                }
            }
            selectedProposal?.let { p ->
                val evidence = p.item.sources.mapNotNull { id -> state.entries.find { it.id == id }?.text }.joinToString("\n\n")
                if (p.item.kind == "goal") {
                    val initial = remember(p.id) { Goal(title = p.item.title, period = p.item.period, target = p.item.target, unit = p.item.unit, checklist = p.item.checklist) }
                    GoalDialog(onDismiss = { selectedProposal = null }, initial = initial, evidence = evidence) { goal ->
                        run { graph.repository.approveProposal(p.id, goal.title, p.item.details, goal); selectedProposal = null }
                    }
                } else ListItemDialog(p.item.kind, proposal = p, evidence = evidence, onDismiss = { selectedProposal = null }) { title, details ->
                    run { graph.repository.approveProposal(p.id, title, details); selectedProposal = null }
                }
            }
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
            cloudPreviewDay?.let { previewDay ->
                CloudPreviewDialog(rawState, previewDay, settings.cloudConsent, onDismiss = { cloudPreviewDay = null },
                    onConfirm = { cloudPreviewDay = null; graph.cleanup.enqueue(previewDay, force = true, provider = "openai", charging = settings.cleanupChargingOnly) },
                    onSettings = { cloudPreviewDay = null; tab = 3 }, graph = graph)
            }
            toolsPage?.let { page ->
                ReleaseHub(graph, activity, page, onDismiss = { toolsPage = null },
                    onEntry = { toolsPage = null; selectedEntry = it })
            }
            if (busy != null) AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text(busy!!) },
                text = { Column { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(12.dp)); Text("Keep Daybook open until this finishes.") } })
            if (settings.biometric && !unlocked) androidx.compose.ui.window.Dialog(
                onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
                    securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
                Surface(Modifier.fillMaxSize(), color = colors.background) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Fingerprint, null, Modifier.size(64.dp), tint = colors.primary)
                        Spacer(Modifier.height(24.dp))
                        Text("A space just for you", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(12.dp))
                        Text("Unlock with your fingerprint or device screen lock.", color = colors.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(24.dp)); Button(onClick = { activity.authenticateJournal(manual = true) }) { Text("Unlock journal") }
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
    Card(modifier.fillMaxWidth().animateContentSize(animationSpec = if (LocalReduceMotion.current) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring()), shape = RoundedCornerShape(24.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
fun amountText(value: Double): String = if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(java.util.Locale.US, value)
fun byteText(value: Long): String = "%.1f MB".format(java.util.Locale.US, value / 1048576.0)
