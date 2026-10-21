package dev.daybook.app.ui
import android.media.MediaPlayer
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import dev.daybook.app.AppGraph
import dev.daybook.core.*
import java.time.*
import kotlinx.coroutines.launch

@Composable fun WriteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Keep a moment") },
        text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp), label = { Text("What is on your mind?") }) },
        confirmButton = { Button(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save note") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
@Composable
fun EntryDialog(entry: Entry, state: Snapshot, graph: AppGraph, onDismiss: () -> Unit, onSave: (String) -> Unit,
    onDelete: () -> Unit, onRetry: () -> Unit) {
    val settings by graph.settings.collectAsState()
    var text by remember(entry.id, entry.text) { mutableStateOf(entry.text) }
    var delete by remember { mutableStateOf(false) }
    var retranscribe by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playbackError by remember { mutableStateOf("") }
    var showTimeline by remember { mutableStateOf(false) }
    var privacyError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun privacy(local: Boolean, photos: Boolean) {
        scope.launch {
            try { graph.repository.change { state -> state.copy(entries = state.entries.map { if (it.id == entry.id) it.copy(keepLocal = local, includePhotosInCloud = photos) else it }) } }
            catch (_: Exception) { privacyError = "Could not save this choice. Please retry." }
        }
    }
    fun playAt(ms: Long) {
        player?.release(); player = null; playbackError = ""
        try {
            val attachment = state.attachments.first { it.entryId == entry.id && it.kind == "audio" }
            val file = graph.media.file(attachment.path)
            dev.daybook.app.capture.Wav.repair(file)
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnPreparedListener { it.seekTo(ms, MediaPlayer.SEEK_CLOSEST); it.start() }
                setOnCompletionListener { it.release(); player = null }
                setOnErrorListener { p, _, _ -> p.release(); player = null; playbackError = "This recording cannot be played yet."; true }
                prepareAsync()
            }
        } catch (_: Exception) { player?.release(); player = null; playbackError = "The saved recording could not be opened." }
    }
    DisposableEffect(entry.id) { onDispose { player?.release() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Your original moment") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Instant.ofEpochMilli(entry.created).atZone(ZoneId.of(entry.zone)).toString(), style = MaterialTheme.typography.labelSmall)
            state.attachments.filter { it.entryId == entry.id && it.kind == "photo" }.forEach {
                coil.compose.AsyncImage(graph.media.file(it.path), "Original journal photo", Modifier.fillMaxWidth().heightIn(max = 280.dp))
            }
            state.attachments.firstOrNull { it.entryId == entry.id && it.kind == "audio" }?.let { attachment ->
                OutlinedButton(onClick = {
                    if (player != null) { player?.release(); player = null } else playAt(0)
                }, enabled = entry.status != "recording") { Text(if (player == null) "Play original audio" else "Stop playback") }
            }
            if (entry.bookmarks.isNotEmpty()) {
                Text("Audio bookmarks", style = MaterialTheme.typography.titleSmall)
                entry.bookmarks.forEach { mark ->
                    TextButton(onClick = { playAt(mark.ms) }, enabled = entry.status != "recording") {
                        Text("%02d:%02d · %s".format(mark.ms / 60000, mark.ms / 1000 % 60, mark.label))
                    }
                }
            }
            if (entry.segments.isNotEmpty()) {
                TextButton(onClick = { showTimeline = !showTimeline }) { Text(if (showTimeline) "Hide transcript timeline" else "Transcript timeline") }
                if (showTimeline) {
                    Text("Tap an original transcript segment to play its audio. Segment times are approximate; edited note text stays separate.", style = MaterialTheme.typography.bodySmall)
                    entry.segments.forEach { segment ->
                        TextButton(onClick = { playAt(segment.ms) }, modifier = Modifier.fillMaxWidth()) {
                            Text("%02d:%02d · %s".format(segment.ms / 60000, segment.ms / 1000 % 60, segment.text))
                        }
                    }
                }
            }
            HorizontalDivider()
            ToggleRow("Keep this entry on-device", "Excluded from OpenAI reviews, including its photos. Local reviews and encrypted backups can still include it.",
                entry.keepLocal, { privacy(it, entry.includePhotosInCloud) })
            if (!entry.keepLocal && state.attachments.any { it.entryId == entry.id && it.kind == "photo" })
                ToggleRow("Include photos in cloud reviews", "Your caption can still be used when photos are excluded.", entry.includePhotosInCloud, { privacy(false, it) })
            if (privacyError.isNotBlank()) Text(privacyError, color = MaterialTheme.colorScheme.error)
            if (playbackError.isNotBlank()) Text(playbackError, color = MaterialTheme.colorScheme.error)
            if (entry.error.isNotBlank()) Text(entry.error, color = MaterialTheme.colorScheme.error)
            if (entry.kind == "voice") {
                Text("Speech model: " + SpeechCatalog.get(entry.speechModel.ifBlank { SpeechCatalog.WHISPER }).title, style = MaterialTheme.typography.bodySmall)
                if (entry.status in setOf("error", "interrupted", "pending")) OutlinedButton(onClick = onRetry) { Text("Retry offline transcription") }
                if (entry.status == "ready") OutlinedButton(onClick = { retranscribe = true }) { Text("Transcribe again") }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp),
                label = { Text(if (entry.kind == "photo") "Photo note" else "Note text") }, enabled = entry.status != "recording")
            TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "Hide original and revisions" else "Original and revision history") }
            if (showOriginal) {
                Text("Original", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(entry.originalText.ifBlank { "(The original is a photo or recording.)" }) }
                state.revisions.filter { it.entryId == entry.id }.sortedByDescending { it.created }.forEach {
                    HorizontalDivider()
                    Text(Instant.ofEpochMilli(it.created).atZone(ZoneId.systemDefault()).toLocalDateTime().toString(), style = MaterialTheme.typography.labelSmall)
                    SelectionContainer { Text(it.text.ifBlank { "(Empty caption)" }) }
                }
            }
            TextButton(onClick = { delete = true }, enabled = entry.status != "recording", colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Move note to Recently Deleted") }
        }
    }, confirmButton = { Button(onClick = { onSave(text) }, enabled = text != entry.text && entry.status != "recording") { Text("Save changes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    if (retranscribe) AlertDialog(onDismissRequest = { retranscribe = false }, title = { Text("Transcribe with ${SpeechCatalog.get(settings.speechModel).title}?") },
        text = { Text("Your saved text is kept in revision history. Original audio stays saved. Save any unsaved edits before continuing.") },
        confirmButton = { TextButton(onClick = { retranscribe = false; onRetry() }) { Text("Transcribe again") } },
        dismissButton = { TextButton(onClick = { retranscribe = false }) { Text("Cancel") } })
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Move this moment?") },
        text = { Text("This hides the note and its media from your journal and reviews. Restore it from Settings → Backups & storage → Recently Deleted for 30 days, or delete it permanently there. Confirmed goal activity remains.") },
        confirmButton = { Button(onClick = { player?.release(); player = null; onDelete() }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Move to Recently Deleted") } },
        dismissButton = { TextButton(onClick = { delete = false }) { Text("Keep note") } })
}

@Composable fun GoalDialog(onDismiss: () -> Unit, initial: Goal? = null, evidence: String = "", onSave: (Goal) -> Unit) {
    var title by remember { mutableStateOf(initial?.title.orEmpty()) }
    var period by remember { mutableStateOf(initial?.period ?: "daily") }
    var checklist by remember { mutableStateOf(initial?.checklist ?: true) }
    var target by remember { mutableStateOf(initial?.target?.let(::amountText) ?: "1") }
    var unit by remember { mutableStateOf(initial?.unit ?: "times") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("A goal for your life") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (evidence.isNotBlank()) { Text("Suggested from your notes", style = MaterialTheme.typography.labelLarge); Text(evidence, style = MaterialTheme.typography.bodySmall); Text("Creating this goal does not log progress or completion.", style = MaterialTheme.typography.bodySmall) }
            Text("Start from a template", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Gym", "Cooking", "Laundry", "Blog", "Learning", "Calories").forEach { template ->
                    SuggestionChip(onClick = {
                        title = when (template) { "Blog" -> "Write a new blog"; "Learning" -> "Learn a new concept"; "Calories" -> "Eat 3000 calories"; else -> template }
                        period = when (template) { "Gym", "Laundry" -> "weekly"; "Blog" -> "once"; else -> "daily" }
                        checklist = template != "Calories"; target = when (template) { "Gym" -> "3"; "Calories" -> "3000"; else -> "1" }
                        unit = if (template == "Calories") "kcal" else "times"
                    }, label = { Text(template) })
                }
            }
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Goal title") }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("daily", "weekly", "once").forEach { value -> FilterChip(selected = period == value, onClick = { period = value }, label = { Text(value.replaceFirstChar { it.uppercase() }) }) }
            }
            Row {
                FilterChip(checklist, { checklist = true; target = "1"; unit = "times" }, label = { Text("Checklist") })
                Spacer(Modifier.width(8.dp)); FilterChip(!checklist, { checklist = false }, label = { Text("Numeric") })
            }
            OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text("Target per period") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
            OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), label = { Text("Unit · times, minutes, kcal…") }, singleLine = true)
            Text("You can log partial effort without marking a goal complete.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = {
        try {
            val g = Goal(title = title.trim(), period = period, target = target.toDoubleOrNull() ?: Double.NaN, unit = unit.trim(), checklist = checklist)
            JournalRules.validateGoal(g); onSave(g)
        } catch (e: Exception) { error = e.message ?: "Check the goal details." }
    }) { Text("Create goal") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun ActivityDialog(goal: Goal, day: String, onDismiss: () -> Unit, onSave: (Double, String) -> Unit) {
    var completed by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Log ${goal.title}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(day, style = MaterialTheme.typography.labelMedium)
            if (goal.checklist) Row {
                Checkbox(completed, { completed = it }); Text("Completed one ${goal.unit}", Modifier.padding(top = 12.dp))
            } else OutlinedTextField(amount, { amount = it }, label = { Text("Amount (${goal.unit})") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(note, { note = it }, label = { Text("What did you work on?") }, modifier = Modifier.fillMaxWidth())
            Text("Partial effort counts as working on this goal, even before completion.", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(onClick = {
        val value = if (goal.checklist) { if (completed) 1.0 else 0.0 } else amount.toDoubleOrNull() ?: if (amount.isBlank()) 0.0 else Double.NaN
        if (!value.isFinite() || value < 0 || value > 1e9) error = "Enter a valid amount."
        else if (note.isBlank() && value == 0.0) error = "Describe your effort or enter an amount."
        else onSave(value, note.trim())
    }) { Text("Save activity") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun SuggestionDialog(s: Suggestion, goal: Goal, state: Snapshot, onDismiss: () -> Unit, onSave: (Double, String) -> Unit) {
    var amount by remember(s.id) { mutableStateOf(amountText(s.amount)) }
    var note by remember(s.id) { mutableStateOf(s.note) }
    var error by remember { mutableStateOf("") }
    val review = state.reviews.find { it.id == s.reviewId }
    val stale = review == null || review.fingerprint != JournalRules.fingerprint(state, s.day)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Confirm ${goal.title}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (stale) Text("This review is outdated. Refresh the daily review before confirming.", color = MaterialTheme.colorScheme.error)
            if (s.estimated) Text("This is an estimate. Check the portion and amount before saving.", color = MaterialTheme.colorScheme.tertiary)
            OutlinedTextField(amount, { amount = it }, label = { Text("Amount (${goal.unit}); 0 = partial effort") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(note, { note = it }, label = { Text("Activity note") })
            Text("Based on", style = MaterialTheme.typography.titleSmall)
            s.sources.forEach { id -> Text(state.entries.find { it.id == id }?.text?.ifBlank { "(Photo entry)" } ?: "(Source removed)", style = MaterialTheme.typography.bodySmall) }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { Button(enabled = !stale, onClick = {
        val value = amount.toDoubleOrNull()
        if (value == null || !value.isFinite() || value < 0 || value > 1e9 || (goal.checklist && value != 0.0 && value != 1.0)) error = "Enter a valid amount${if (goal.checklist) " (0 or 1)" else ""}."
        else onSave(value, note)
    }) { Text("Confirm activity") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable fun BackupDialog(mode: String, onDismiss: () -> Unit, onContinue: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var repeated by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (mode == "export") "Encrypt your backup" else "Restore your journal") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (mode == "export") "Includes all notes, original recordings, photos, goals, and reviews. Keep the passphrase safe; it cannot be recovered. API keys and speech models are excluded."
                else "Choose a Daybook backup after entering its passphrase. The file is authenticated and checked before replacing your current journal. App lock is reset after restore.")
            OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase (10+ characters)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (mode == "export") OutlinedTextField(repeated, { repeated = it }, label = { Text("Repeat passphrase") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (mode == "restore") Row { Checkbox(acknowledged, { acknowledged = it }); Text("Replace the current journal. I have backed up anything I want to keep.", Modifier.padding(top = 10.dp)) }
        }
    }, confirmButton = { Button(enabled = pass.length >= 10 && if (mode == "export") pass == repeated else acknowledged,
        onClick = { val chars = pass.toCharArray(); pass = ""; repeated = ""; onContinue(chars) }) { Text(if (mode == "export") "Choose destination" else "Choose backup") } },
        dismissButton = { TextButton(onClick = { pass = ""; repeated = ""; onDismiss() }) { Text("Cancel") } })
}
