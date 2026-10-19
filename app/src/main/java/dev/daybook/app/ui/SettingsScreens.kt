package dev.daybook.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import dev.daybook.core.*

@Composable fun ScheduleFields(s: Settings, onChange: (Settings) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(s.wake, { onChange(s.copy(wake = it)) }, Modifier.weight(1f), label = { Text("Wake · HH:mm") }, singleLine = true)
        OutlinedTextField(s.sleep, { onChange(s.copy(sleep = it)) }, Modifier.weight(1f), label = { Text("Quiet · HH:mm") }, singleLine = true)
    }
    OutlinedTextField(s.intervalHours.toString(), { value -> value.toIntOrNull()?.let { onChange(s.copy(intervalHours = it)) } },
        Modifier.fillMaxWidth(), label = { Text("Remind every… hours (1–12)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    OutlinedTextField(s.cleanupTime, { onChange(s.copy(cleanupTime = it)) }, Modifier.fillMaxWidth(), label = { Text("Nightly cleanup · HH:mm") }, singleLine = true)
    Text("Use 24-hour local time. Android and Samsung battery controls can delay reminders and cleanup.", style = MaterialTheme.typography.bodySmall)
}
@Composable fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall); if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall) }
        Switch(checked, onChange)
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
            Text("Choose your rhythm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ScheduleFields(s) { s = it }
            ToggleRow("Journal reminders", "Gentle check-ins during waking hours.", s.reminders) { s = s.copy(reminders = it) }
            ToggleRow("Automatic daily cleanup", "Runs around your chosen nightly time.", s.autoCleanup) { s = s.copy(autoCleanup = it) }
        }
        SectionCard {
            Text("Your voice stays here", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Voice is transcribed entirely on this phone after downloading the English model once (141 MB). Original recordings are kept for playback.")
            OutlinedButton(onClick = onDownload, enabled = !modelReady) { Text(if (modelReady) "Speech model ready" else "Download English speech model") }
            Text("You can start journaling now and download it later in Settings.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard {
            Text("A little help at day’s end", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Cleanup sends your selected day’s note text and all journal-attached photos to OpenAI using your own API key. Audio is never uploaded. AI suggestions only affect goals after you confirm.")
            ToggleRow("Allow OpenAI daily reviews", "You can change this and enter your key in Settings.", s.cloudConsent) { s = s.copy(cloudConsent = it) }
            Text("API requests disable response storage, but provider retention rules can still apply.", style = MaterialTheme.typography.bodySmall)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { try { JournalRules.validateSettings(s); onSave(s) } catch (e: Exception) { error = e.message ?: "Check the schedule." } }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Start my journal") }
        Text("No account required. Use encrypted backups to protect your journal before uninstalling or changing phones.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(20.dp))
    }
}
@Composable
fun SettingsScreen(settings: Settings, hasKey: Boolean, modelReady: Boolean, modelWork: WorkInfo?, mediaBytes: Long, modelBytes: Long,
    day: String, modifier: Modifier, onSave: (Settings) -> Unit, onKey: (String) -> Unit, onDownload: () -> Unit,
    onDeleteModel: () -> Unit, onPauseDownload: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit,
    onMarkdown: () -> Unit, onPermissions: () -> Unit, onBattery: () -> Unit) {
    var s by remember(settings) { mutableStateOf(settings) }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var keySaved by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageTitle("Your space", "Make it yours.", "Privacy, your daily rhythm, and a little peace of mind.")
        SectionCard {
            Text("Daily rhythm", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ScheduleFields(s) { s = it }
            ToggleRow("Reminders", "", s.reminders) { s = s.copy(reminders = it) }
            ToggleRow("Nightly cleanup", "Requires consent, an API key, and internet.", s.autoCleanup) { s = s.copy(autoCleanup = it) }
            OutlinedButton(onClick = onPermissions) { Text("Enable notification permission") }
            TextButton(onClick = onBattery) { Text("Open app & battery settings") }
            Text("If check-ins are delayed, review Daybook’s notification permission and Samsung sleeping-app settings. Avoid force-stopping the app.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard {
            Text("OpenAI reviews", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ToggleRow("Send notes and attached photos", "All journal-attached photos for the selected day are included. Recordings stay local.", s.cloudConsent) { s = s.copy(cloudConsent = it) }
            OutlinedTextField(s.model, { s = s.copy(model = it) }, Modifier.fillMaxWidth(), label = { Text("Vision-capable model") }, singleLine = true)
            Text("Your key is encrypted with Android Keystore. It is never bundled in the app, exported, or sent anywhere except OpenAI.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(apiKey, { apiKey = it; keySaved = false }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "Replace saved API key" else "Your OpenAI API key") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            Row {
                Button(onClick = { onKey(apiKey.trim()); apiKey = ""; keySaved = true }, enabled = apiKey.isNotBlank()) { Text("Save key") }
                if (hasKey) TextButton(onClick = { onKey(""); keySaved = false }) { Text("Remove key") }
            }
            Text(if (hasKey) "API key configured." else if (keySaved) "Saving key…" else "No API key saved. Offline features remain available.", style = MaterialTheme.typography.labelMedium)
            Text("Reviews use paid OpenAI API usage. Requests set store:false; that does not guarantee zero provider retention. See the privacy guide in the repository.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard {
            Text("Offline speech", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(if (modelReady) "English base.en · ready for airplane mode" else "Download the English model once to transcribe voice notes.")
            Text("whisper.cpp · CPU inference · checksum-verified 141 MB model", style = MaterialTheme.typography.bodySmall)
            when (modelWork?.state) {
                WorkInfo.State.RUNNING -> { LinearProgressIndicator(progress = { modelWork.progress.getInt("percent", 0) / 100f }, modifier = Modifier.fillMaxWidth()); Text("Downloading · ${modelWork.progress.getInt("percent", 0)}%") }
                WorkInfo.State.ENQUEUED -> Text("Download queued or waiting to retry.")
                WorkInfo.State.FAILED -> Text(modelWork.outputData.getString("error") ?: "Download failed. Please retry.", color = MaterialTheme.colorScheme.error)
                WorkInfo.State.CANCELLED -> Text("Download paused. Tap Download to resume.")
                else -> Unit
            }
            if (modelWork?.state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED)) OutlinedButton(onClick = onPauseDownload) { Text("Pause download") }
            else OutlinedButton(onClick = onDownload, enabled = !modelReady) { Text(if (modelReady) "Model ready" else "Download / resume model") }
            if (modelReady) TextButton(onClick = onDeleteModel) { Text("Delete downloaded model") }
        }
        SectionCard {
            Text("Privacy & appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            ToggleRow("Lock journal", "Use biometrics or your device credential. Scheduled cleanup remains enabled.", s.biometric) { s = s.copy(biometric = it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system", "light", "dark").forEach { theme -> FilterChip(s.theme == theme, { s = s.copy(theme = theme) }, label = { Text(theme.replaceFirstChar { it.uppercase() }) }) }
            }
            Text("Original recordings and photos are kept until you delete their note. Android cloud backup and automatic device transfer are excluded.", style = MaterialTheme.typography.bodySmall)
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { try { JournalRules.validateSettings(s); onSave(s) } catch (e: Exception) { error = e.message ?: "Check your settings." } },
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save settings") }
        SectionCard {
            Text("Keep your memories safe", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Media: ${byteText(mediaBytes)} · Speech model: ${byteText(modelBytes)}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onBackup, modifier = Modifier.fillMaxWidth()) { Text("Create encrypted full backup") }
            OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Restore encrypted backup") }
            OutlinedButton(onClick = onMarkdown, modifier = Modifier.fillMaxWidth()) { Text("Export $day as Markdown") }
            Text("Markdown contains text and attachment identifiers. Use a full backup to preserve audio and photos. A lost backup passphrase cannot be recovered.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Daybook 0.1.0 · Made for the everyday.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
}
