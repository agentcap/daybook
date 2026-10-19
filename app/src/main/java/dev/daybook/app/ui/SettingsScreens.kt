package dev.daybook.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import dev.daybook.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun clockText(value: String) = LocalTime.parse(value).format(DateTimeFormatter.ofPattern("h:mm a"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TimeSetting(title: String, value: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = { open = true }) { Text(clockText(value)); Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp)) }
    }
    if (open) {
        val time = LocalTime.parse(value)
        val picker = rememberTimePickerState(time.hour, time.minute, is24Hour = false)
        AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
            text = { TimeInput(picker) },
            confirmButton = { TextButton(onClick = { onChange("%02d:%02d".format(picker.hour, picker.minute)); open = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } })
    }
}
@Composable fun ScheduleFields(s: Settings, onChange: (Settings) -> Unit) {
    TimeSetting("Start of day", s.wake) { onChange(s.copy(wake = it)) }
    TimeSetting("Quiet hours begin", s.sleep) { onChange(s.copy(sleep = it)) }
    var intervals by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Remind me every", Modifier.weight(1f))
        Box {
            TextButton(onClick = { intervals = true }) { Text("${s.intervalHours} ${if (s.intervalHours == 1) "hour" else "hours"}"); Icon(Icons.Outlined.ExpandMore, null) }
            DropdownMenu(intervals, onDismissRequest = { intervals = false }) {
                (1..12).forEach { hours -> DropdownMenuItem(text = { Text("$hours ${if (hours == 1) "hour" else "hours"}") },
                    onClick = { onChange(s.copy(intervalHours = hours)); intervals = false }) }
            }
        }
    }
    TimeSetting("Nightly cleanup", s.cleanupTime) { onChange(s.copy(cleanupTime = it)) }
    Text("Local time · no reminders during quiet hours.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
@Composable fun Onboarding(initial: Settings, modelReady: Boolean, onDownload: () -> Unit, onSave: (Settings) -> Unit) {
    var s by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(12.dp))
        Icon(Icons.Outlined.AutoStories, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        PageTitle("Welcome to Daybook", "Make room for your day.", "A private place for thoughts, everyday photos, and the goals that matter to you.")
        SectionCard {
            Text("Choose your rhythm", style = MaterialTheme.typography.titleLarge)
            ScheduleFields(s) { s = it }
            ToggleRow("Journal reminders", "Gentle check-ins during waking hours.", s.reminders, onChange = { s = s.copy(reminders = it) })
            ToggleRow("Automatic daily cleanup", "Runs around your chosen nightly time.", s.autoCleanup, onChange = { s = s.copy(autoCleanup = it) })
        }
        SectionCard {
            Text("Your voice stays here", style = MaterialTheme.typography.titleLarge)
            Text("Voice is transcribed entirely on this phone after downloading the English model once (141 MB). Original recordings are kept for playback.")
            OutlinedButton(onClick = onDownload, enabled = !modelReady) { Text(if (modelReady) "Speech model ready" else "Download English speech model") }
            Text("You can start journaling now and download it later in Settings.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard {
            Text("A little help at day’s end", style = MaterialTheme.typography.titleLarge)
            Text("Choose OpenAI or on-device text cleanup in Settings. OpenAI cleanup sends your selected day’s note text and all journal-attached photos to OpenAI using your own API key. Audio is never uploaded. AI suggestions only affect goals after you confirm.")
            ToggleRow("Allow OpenAI daily reviews", "You can change this and enter your key in Settings.", s.cloudConsent, onChange = { s = s.copy(cloudConsent = it) })
            Text("API requests disable response storage, but provider retention rules can still apply.", style = MaterialTheme.typography.bodySmall)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { try { JournalRules.validateSettings(s); onSave(s) } catch (e: Exception) { error = e.message ?: "Check the schedule." } },
            modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start my journal") }
        Text("No account required. Use encrypted backups to protect your journal before uninstalling or changing phones.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
    }
}

@Composable private fun SettingsLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(12.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable
fun SettingsScreen(settings: Settings, hasKey: Boolean, modelReady: Boolean, modelWork: WorkInfo?, mediaBytes: Long, modelBytes: Long,
    day: String, modifier: Modifier, onSave: suspend (Settings) -> Unit, onKey: suspend (String) -> Unit, onDownload: () -> Unit,
    onDeleteModel: () -> Unit, onPauseDownload: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit,
    onMarkdown: () -> Unit, onPermissions: () -> Unit, onBattery: () -> Unit, onAddWidget: () -> Unit,
    localReady: Boolean, localWork: WorkInfo?, localBytes: Long, onLocalDownload: () -> Unit,
    onLocalPause: () -> Unit, onLocalDelete: suspend () -> Unit) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember(settings) { mutableStateOf(settings) }
    var apiKey by remember { mutableStateOf("") }
    var modelDraft by rememberSaveable(settings.model) { mutableStateOf(settings.model) }
    var error by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var deleteModel by remember { mutableStateOf(false) }
    var deleteLocal by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun task(message: String, action: suspend () -> Unit) {
        scope.launch {
            saving = true; error = ""; saved = ""
            try { action(); saved = message }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not save. Please try again." }
            finally { saving = false }
        }
    }
    fun save(value: Settings) = task("Changes saved") { JournalRules.validateSettings(value); onSave(value) }
    BackHandler(page != null) { page = null; error = ""; saved = "" }
    val scroll = key(page) { rememberScrollState() }
    LaunchedEffect(page) { draft = settings; saved = ""; error = "" }
    Column(modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (page == null) {
            PageTitle("Your space", "Make it yours.", "A little rhythm. A little peace of mind.")
            SectionCard {
                Text("DAILY ROUTINE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                SettingsLink(Icons.Outlined.NotificationsNone, "Reminders & rhythm",
                    if (settings.reminders) "Every ${settings.intervalHours} hours · ${clockText(settings.wake)}–${clockText(settings.sleep)}" else "Reminders are off") { page = "rhythm" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsLink(Icons.Outlined.MicNone, "Voice & offline speech", if (modelReady) "English · ready offline" else "Download speech model · 141 MB") { page = "voice" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsLink(Icons.Outlined.AutoAwesome, "AI daily reviews", if (settings.reviewProvider == "local") (if (localReady) "On-device · ready offline" else "On-device · download model to start") else if (!hasKey) "Add your OpenAI key to get started" else if (settings.cloudConsent) "Ready · ${if (settings.autoCleanup) "nightly & on demand" else "on demand"}" else "Key saved · reviews paused") { page = "ai" }
            }
            SectionCard {
                Text("YOUR JOURNAL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                SettingsLink(Icons.Outlined.Fingerprint, "Privacy & lock", if (settings.biometric) "Fingerprint or screen lock · on" else "App lock is off") { page = "privacy" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsLink(Icons.Outlined.Palette, "Appearance", "${settings.theme.replaceFirstChar { it.uppercase() }} theme") { page = "appearance" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsLink(Icons.Outlined.CloudDownload, "Backups & storage", "${byteText(mediaBytes)} of memories · keep a safe copy") { page = "storage" }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsLink(Icons.Outlined.Widgets, "Home-screen recording", "One tap to capture a thought") { page = "widget" }
            }
            Text("Daybook ${dev.daybook.app.BuildConfig.VERSION_NAME} · Made for the everyday.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            TextButton(onClick = { page = null; error = ""; saved = "" }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("All settings")
            }
            val title = when (page) { "rhythm" -> "Your daily rhythm"; "voice" -> "Speak your mind."; "ai" -> "A little clarity."; "privacy" -> "Just for you."; "appearance" -> "Set the mood."; "widget" -> "A thought, one tap away."; else -> "Keep every memory." }
            PageTitle("Make it yours", title)
            when (page) {
                "rhythm" -> {
                    SectionCard {
                        ToggleRow("Journal reminders", "A gentle nudge to capture your day.", draft.reminders, { draft = draft.copy(reminders = it) })
                        ScheduleFields(draft) { draft = it }
                        Button(onClick = { save(draft) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text("Save rhythm") }
                    }
                    SectionCard {
                        Text("Keep check-ins arriving", style = MaterialTheme.typography.titleMedium)
                        Text("Allow notifications for reminders and recording controls. Android and Samsung battery settings can delay scheduled check-ins.", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onPermissions, modifier = Modifier.fillMaxWidth()) { Text("Manage notifications") }
                        TextButton(onClick = onBattery) { Text("Open battery & app settings") }
                        Text("On Samsung, keep Daybook out of Sleeping apps if reminders arrive late. Snooze a reminder for 30 minutes from its notification.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "voice" -> {
                    SectionCard {
                        Icon(Icons.Outlined.GraphicEq, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (modelReady) "Ready for airplane mode" else "Take your voice offline", style = MaterialTheme.typography.titleLarge)
                        Text("Download English speech once (141 MB). Voice notes are then transcribed on your phone, even without internet. Your original audio is always kept. Live draft text appears in short batches during recording and is refined after you stop.")
                        when (modelWork?.state) {
                            WorkInfo.State.RUNNING -> {
                                LinearProgressIndicator(progress = { modelWork.progress.getInt("percent", 0) / 100f }, modifier = Modifier.fillMaxWidth())
                                Text("Downloading · ${modelWork.progress.getInt("percent", 0)}%")
                            }
                            WorkInfo.State.ENQUEUED -> Text("Download queued or waiting to retry.")
                            WorkInfo.State.FAILED -> Text(modelWork.outputData.getString("error") ?: "Download failed. Please retry.", color = MaterialTheme.colorScheme.error)
                            WorkInfo.State.CANCELLED -> Text("Download paused. Continue whenever you are ready.")
                            else -> Unit
                        }
                        if (modelWork?.state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)) OutlinedButton(onClick = onPauseDownload) { Text("Pause download") }
                        else Button(onClick = onDownload, enabled = !modelReady) { Text(if (modelReady) "Speech model ready" else "Download English model") }
                        Text("Recording keeps going when the screen locks. Stop from the recording notification or Today. Each note can be up to 30 minutes.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "ai" -> {
                    SectionCard {
                        Text("Where should reviews run?", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(selected = settings.reviewProvider == "openai", enabled = !saving,
                                onClick = { save(settings.copy(reviewProvider = "openai")) }, label = { Text("OpenAI") })
                            FilterChip(selected = settings.reviewProvider == "local", enabled = !saving,
                                onClick = { save(settings.copy(reviewProvider = "local")) }, label = { Text("On-device") })
                        }
                        Text(if (settings.reviewProvider == "local") "Text cleanup and suggestions stay on this phone. No API key is needed. Photos remain attached but are not analyzed."
                            else "Review text and attached photos with your own OpenAI key. Audio stays on this phone.", style = MaterialTheme.typography.bodyMedium)
                    }
                    SectionCard {
                        if (settings.reviewProvider == "openai") ToggleRow("Allow OpenAI reviews", "Sends this day’s text and attached photos, with existing goals and list titles for context. Audio stays on your phone.", settings.cloudConsent, { save(settings.copy(cloudConsent = it)) }, !saving)
                        ToggleRow("Automatic nightly cleanup", if (settings.reviewProvider == "local") "Runs on this phone after downloading the review model." else "Uses your saved key and internet connection.", settings.autoCleanup, { save(settings.copy(autoCleanup = it)) }, !saving)
                        TimeSetting("Nightly review time", settings.cleanupTime) { save(settings.copy(cleanupTime = it)) }
                        Text("Originals are preserved. Review new goals, list items, progress, and calorie estimates before applying them.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (settings.reviewProvider == "local") {
                        SectionCard {
                            Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("Qwen 2.5 · 1.5B", style = MaterialTheme.typography.titleLarge)
                            Text(if (localReady) "Review model ready · works in airplane mode" else "One-time download · 1.1 GB", style = MaterialTheme.typography.titleSmall)
                            Text("Creates a cleaned journal, summary, and reviewable suggestions from note text. Best for shorter days; larger days can take a few minutes and use more battery.", style = MaterialTheme.typography.bodyMedium)
                            when (localWork?.state) {
                                WorkInfo.State.RUNNING -> {
                                    LinearProgressIndicator(progress = { localWork.progress.getInt("percent", 0) / 100f }, modifier = Modifier.fillMaxWidth())
                                    Text(if (localWork.progress.getBoolean("verifying", false)) "Verifying the model…" else "Downloading · ${localWork.progress.getInt("percent", 0)}%")
                                }
                                WorkInfo.State.ENQUEUED -> Text("Download queued · waiting for a connection or retry.")
                                WorkInfo.State.FAILED -> Text(localWork.outputData.getString("error") ?: "Download failed. Try again.", color = MaterialTheme.colorScheme.error)
                                WorkInfo.State.CANCELLED -> if (!localReady) Text("Download paused. Continue when you are ready.")
                                else -> Unit
                            }
                            if (localWork?.state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)) OutlinedButton(onClick = onLocalPause) { Text("Pause review model download") }
                            else Button(onClick = onLocalDownload, enabled = !localReady && !saving) { Text(if (localReady) "Review model ready" else "Download review model") }
                            if (localBytes > 0) TextButton(onClick = { deleteLocal = true }, enabled = !saving) { Text("Remove review model") }
                            Text("Download is checked before installation. The model is excluded from backups. Daybook never switches to OpenAI automatically.", style = MaterialTheme.typography.bodySmall)
                        }
                    } else SectionCard {
                        Text(if (hasKey) "Your API key is saved" else "Connect your OpenAI key", style = MaterialTheme.typography.titleMedium)
                        Text("Your key is encrypted on this phone and excluded from backups. OpenAI API usage is billed to your account.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "Replace API key" else "OpenAI API key") },
                            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { val key = apiKey.trim(); task("API key saved") { onKey(key); apiKey = "" } }, enabled = apiKey.isNotBlank() && !saving) { Text("Save key") }
                            if (hasKey) TextButton(onClick = { task("API key removed") { onKey(""); apiKey = "" } }, enabled = !saving) { Text("Remove key") }
                        }
                        OutlinedTextField(modelDraft, { modelDraft = it }, Modifier.fillMaxWidth(), label = { Text("OpenAI model") }, singleLine = true)
                        OutlinedButton(onClick = { save(settings.copy(model = modelDraft.trim())) }, enabled = !saving) { Text("Save model") }
                        Text("Requests disable response storage (store: false). Provider retention rules may still apply.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "privacy" -> SectionCard {
                    Icon(Icons.Outlined.Fingerprint, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                    ToggleRow("Fingerprint & screen lock", "Ask automatically whenever you open Daybook.", settings.biometric, { save(settings.copy(biometric = it)) }, !saving)
                    Text("Use an enrolled fingerprint or your device PIN, pattern, or password. If you dismiss the prompt, your journal stays locked until you retry.")
                    Text("Scheduled cleanup and an ongoing recording can continue while your journal is locked.", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                    Text("Your memories stay with you", style = MaterialTheme.typography.titleMedium)
                    Text("Original audio and photos stay until you delete their note. Automatic Android cloud backup and device transfer are disabled. Use an encrypted backup before uninstalling.", style = MaterialTheme.typography.bodySmall)
                }
                "appearance" -> SectionCard {
                    Text("Choose a theme", style = MaterialTheme.typography.titleLarge)
                    listOf("system" to "Follow your phone", "light" to "Light", "dark" to "Dark").forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { save(settings.copy(theme = value)) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(when(value) { "light" -> Icons.Outlined.LightMode; "dark" -> Icons.Outlined.DarkMode; else -> Icons.Outlined.BrightnessAuto }, null)
                            Text(label, Modifier.weight(1f).padding(start = 14.dp))
                            RadioButton(settings.theme == value, { save(settings.copy(theme = value)) }, enabled = !saving)
                        }
                    }
                    Text("Soft lavender, room to breathe, and a place for every moment.", style = MaterialTheme.typography.bodySmall)
                }
                "storage" -> {
                    SectionCard {
                        Text("Keep a safe copy", style = MaterialTheme.typography.titleLarge)
                        Text("A full backup includes your notes, goals, original audio, and photos. Your API key and downloaded model are excluded.")
                        Button(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Lock, null); Spacer(Modifier.width(8.dp)); Text("Create encrypted backup") }
                        OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Restore a backup") }
                        Text("Choose a passphrase you can remember. A lost passphrase cannot be recovered.", style = MaterialTheme.typography.bodySmall)
                    }
                    SectionCard {
                        Text("Share a day", style = MaterialTheme.typography.titleMedium)
                        Text("Export $day as readable text. Audio and photo files are included in full backups only.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onMarkdown) { Text("Export day as Markdown") }
                    }
                    SectionCard {
                        Text("On this phone", style = MaterialTheme.typography.titleMedium)
                        Text("Journal media · ${byteText(mediaBytes)}")
                        Text("Speech model · ${byteText(modelBytes)}")
                        Text("Review model · ${byteText(localBytes)}")
                        if (localBytes > 0) TextButton(onClick = { deleteLocal = true }) { Text("Remove review model") }
                        if (modelReady) TextButton(onClick = { deleteModel = true }) { Text("Remove speech model") }
                        Text("Remove individual recordings and photos by deleting their note. Removing the model keeps your recordings; download it again to transcribe.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "widget" -> SectionCard {
                    Icon(Icons.Outlined.Mic, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Record from your home screen", style = MaterialTheme.typography.titleLarge)
                    Text("Add the compact 1 × 1 Daybook Record widget. Its dark theme stays easy to spot on your home screen. Tap the microphone to start recording; stop & save from the notification.")
                    Button(onClick = onAddWidget, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("Add Record widget") }
                    Text("Touch and hold your home screen → Widgets → Daybook → Daybook Record. Already added an older widget? Touch and hold it to shrink it, or remove it and add it again.", style = MaterialTheme.typography.bodyMedium)
                    Text("The first recording needs microphone permission. If app lock is on, fingerprint authentication appears first. Finish onboarding once before using the widget.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (saved.isNotBlank()) Text(saved, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
    }
    if (deleteLocal) AlertDialog(onDismissRequest = { deleteLocal = false }, title = { Text("Remove review model?") },
        text = { Text("Your notes and reviews stay saved. Download it again before using on-device cleanup.") },
        confirmButton = { TextButton(onClick = { deleteLocal = false; task("Review model removed") { onLocalDelete() } }) { Text("Remove model") } },
        dismissButton = { TextButton(onClick = { deleteLocal = false }) { Text("Keep model") } })
    if (deleteModel) AlertDialog(onDismissRequest = { deleteModel = false }, title = { Text("Remove speech model?") },
        text = { Text("Your voice notes stay saved. Download the model again before transcribing.") },
        confirmButton = { TextButton(onClick = { onDeleteModel(); deleteModel = false }) { Text("Remove model") } },
        dismissButton = { TextButton(onClick = { deleteModel = false }) { Text("Keep model") } })
}
