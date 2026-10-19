package dev.daybook.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import dev.daybook.core.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(state: Snapshot, day: String, recording: Boolean, work: WorkInfo?, modifier: Modifier,
    onDate: (String) -> Unit, onWrite: () -> Unit, onVoice: () -> Unit, onPhoto: () -> Unit,
    onStop: () -> Unit, onEntry: (String) -> Unit, onCleanup: () -> Unit, onSuggestion: (Suggestion) -> Unit,
    onReject: (String) -> Unit, onUndo: (String) -> Unit, onLog: (String) -> Unit, onGoals: () -> Unit, onLists: () -> Unit) {
    val date = LocalDate.parse(day)
    val entries = state.entries.filter { it.day == day }.sortedByDescending { it.created }
    val reviews = state.reviews.filter { it.day == day }.sortedByDescending { it.created }
    val latest = reviews.firstOrNull()
    val worked = JournalRules.workedOn(state, day)
    var reviewExpanded by remember(day) { mutableStateOf(false) }
    var oldReviews by remember(day) { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PageTitle("Daybook", if (date == LocalDate.now()) "Your day, captured." else "A day to remember.",
                        date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { onDate(date.minusDays(1).toString()) }) { Icon(Icons.Outlined.ChevronLeft, "Previous day") }
                Text(if (date == LocalDate.now()) "TODAY" else day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                IconButton(enabled = date < LocalDate.now(), onClick = { onDate(date.plusDays(1).toString()) }) { Icon(Icons.Outlined.ChevronRight, "Next day") }
            }
        }
        item {
            SectionCard {
                Text("A thought. A meal. A small win.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Save it while it is fresh.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = if (recording) onStop else onVoice, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Icon(if (recording) Icons.Outlined.StopCircle else Icons.Outlined.Mic, null); Spacer(Modifier.width(10.dp))
                    Text(if (recording) "Stop & save recording" else "Record a thought", style = MaterialTheme.typography.titleMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onWrite, Modifier.weight(1f).height(48.dp)) { Icon(Icons.Outlined.EditNote, null); Spacer(Modifier.width(6.dp)); Text("Write") }
                    OutlinedButton(onClick = onPhoto, Modifier.weight(1f).height(48.dp)) { Icon(Icons.Outlined.AddAPhoto, null); Spacer(Modifier.width(6.dp)); Text("Photo") }
                }
                if (recording) {
                    TextButton(onClick = onVoice) { Text("View live transcript") }
                    Text("Recording continues with the screen locked. Stop from the notification or here.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Flag, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("$worked ${if (worked == 1) "goal" else "goals"} worked on", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Effort counts. Completion is tracked separately.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                val activeGoals = state.goals.filter { !it.archived }
                activeGoals.take(4).forEach { goal ->
                    val progress = JournalRules.progress(state, goal, date)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(goal.title, style = MaterialTheme.typography.titleSmall)
                            Text("${amountText(progress)} / ${amountText(goal.target)} ${goal.unit} · ${goal.period}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onLog(goal.id) }) { Text(if (progress >= goal.target) "Add activity" else "Log effort") }
                    }
                }
                TextButton(onClick = onGoals) { Text(if (activeGoals.isEmpty()) "Choose your first goals" else "View all goals") }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp))
                    Text("Your daily review", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                if (latest == null) Text("Turn scattered notes and photos into a readable journal. Your originals stay preserved.")
                else {
                    val stale = latest.fingerprint != JournalRules.fingerprint(state, day)
                    if (stale) Text("New notes or changes are waiting. Refresh this review.", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                    Text(latest.summary)
                    if (reviewExpanded) {
                        HorizontalDivider()
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(latest.journal) }
                        latest.captions.forEach { c ->
                            Text("Photo: ${c.description}", style = MaterialTheme.typography.bodyMedium)
                            state.attachments.find { it.id == c.attachmentId }?.let { a -> TextButton(onClick = { onEntry(a.entryId) }) { Text("Open original photo") } }
                        }
                        Text("Original notes", style = MaterialTheme.typography.labelLarge)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val sourceIds = latest.sourceIds.orEmpty().ifEmpty { entries.map { it.id } }
                            sourceIds.mapNotNull { id -> state.entries.find { it.id == id } }.forEach { source ->
                                AssistChip(onClick = { onEntry(source.id) }, label = {
                                    Text("${source.kind.replaceFirstChar { it.uppercase() }} · ${Instant.ofEpochMilli(source.created).atZone(ZoneId.of(source.zone)).toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a"))}")
                                })
                            }
                        }
                    }
                    TextButton(onClick = { reviewExpanded = !reviewExpanded }) { Text(if (reviewExpanded) "Hide journal" else "Read cleaned journal") }
                    if (latest.provider == "local") Text("On-device text review · attached photos were not analyzed.", style = MaterialTheme.typography.bodySmall)
                    Text("${latest.model} · ${Instant.ofEpochMilli(latest.created).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)}", style = MaterialTheme.typography.labelSmall)
                }
                when (work?.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> Text("Waiting to run · your notes are saved.", style = MaterialTheme.typography.bodySmall)
                    WorkInfo.State.RUNNING -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(work.progress.getString("message") ?: "Cleaning up your day…") }
                    WorkInfo.State.FAILED -> Text(work.outputData.getString("error") ?: "Cleanup failed. Try again.", color = MaterialTheme.colorScheme.error)
                    else -> Unit
                }
                Button(onClick = onCleanup, enabled = entries.isNotEmpty() && work?.state !in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)) {
                    Text(if (latest == null) "Clean up now" else "Refresh review")
                }
                val discoveries = state.proposals.count { it.day == day && it.status == "pending" }
                if (discoveries > 0) TextButton(onClick = onLists) { Text("$discoveries new goal / to-do / idea suggestions") }
                if (reviews.size > 1) {
                    TextButton(onClick = { oldReviews = !oldReviews }) { Text(if (oldReviews) "Hide previous reviews" else "${reviews.size - 1} previous reviews") }
                    if (oldReviews) reviews.drop(1).forEach { review ->
                        HorizontalDivider()
                        Text(Instant.ofEpochMilli(review.created).atZone(ZoneId.systemDefault()).toString(), style = MaterialTheme.typography.labelSmall)
                        Text(review.summary); androidx.compose.foundation.text.selection.SelectionContainer { Text(review.journal) }
                    }
                }
            }
        }
        val suggestions = state.suggestions.filter { it.day == day && it.status == "pending" }
        if (suggestions.isNotEmpty()) item { Text("Suggested progress", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        items(suggestions, key = { it.id }) { suggestion ->
            SectionCard {
                Text(state.goals.find { it.id == suggestion.goalId }?.title ?: "Goal", style = MaterialTheme.typography.titleMedium)
                Text(suggestion.note)
                if (suggestion.estimated) Text("Estimated · review the portion and amount", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                Row {
                    Button(onClick = { onSuggestion(suggestion) }) { Text("Review & confirm") }
                    TextButton(onClick = { onReject(suggestion.id) }) { Text("Dismiss") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Moments", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${entries.size} ${if (entries.size == 1) "entry" else "entries"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (entries.isEmpty()) item {
            SectionCard {
                Icon(Icons.Outlined.Spa, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Every day has something worth keeping.", style = MaterialTheme.typography.titleMedium)
                Text("Start with a sentence, a voice note, or a photo.")
            }
        }
        items(entries, key = { it.id }) { entry ->
            Card(onClick = { onEntry(entry.id) }, shape = RoundedCornerShape(28.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    MomentDateBadge(LocalDate.parse(entry.day))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(when (entry.kind) { "voice" -> Icons.Outlined.MicNone; "photo" -> Icons.Outlined.Image; else -> Icons.Outlined.EditNote }, null,
                                Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(Instant.ofEpochMilli(entry.created).atZone(ZoneId.of(entry.zone)).toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a")),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (entry.text.isNotBlank()) Text(entry.text, style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        state.attachments.firstOrNull { it.entryId == entry.id && it.kind == "photo" }?.let { attachment ->
                            coil.compose.AsyncImage(model = java.io.File(androidx.compose.ui.platform.LocalContext.current.filesDir, attachment.path), contentDescription = "Journal photo",
                                modifier = Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                        if (entry.status != "ready") Text(when (entry.status) {
                            "recording" -> "Recording…"; "pending" -> "Saved · waiting for offline transcription"; else -> entry.error.ifBlank { "Audio needs attention · tap to retry" }
                        }, color = if (entry.status in setOf("error", "interrupted")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                        Text("Open note →", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        val activities = state.activities.filter { it.day == day && !it.undone }.sortedByDescending { it.created }
        if (activities.isNotEmpty()) item { Text("Confirmed activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        items(activities, key = { it.id }) { a ->
            SectionCard {
                Text(state.goals.find { it.id == a.goalId }?.title ?: "Goal", style = MaterialTheme.typography.titleMedium)
                Text(a.note.ifBlank { "Logged activity" })
                val goal = state.goals.find { it.id == a.goalId }
                Text(if (a.amount == 0.0) "Worked on · partial effort" else "${amountText(a.amount)} ${goal?.unit.orEmpty()}", style = MaterialTheme.typography.labelMedium)
                a.sources.forEach { id -> TextButton(onClick = { onEntry(id) }) { Text("View source note") } }
                TextButton(onClick = { onUndo(a.id) }) { Text("Undo activity") }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}


@Composable
fun GoalsScreen(state: Snapshot, day: String, modifier: Modifier, onNew: () -> Unit, onLog: (String) -> Unit, onArchive: (String, Boolean) -> Unit) {
    var archived by remember { mutableStateOf(false) }
    val goals = state.goals.filter { it.archived == archived }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PageTitle("Small steps", "Make room for progress.", "Track effort as well as the finish line.")
            Spacer(Modifier.height(18.dp))
            Button(onClick = onNew) { Icon(Icons.Outlined.Add, null); Text("New goal") }
            TextButton(onClick = { archived = !archived }) { Text(if (archived) "Show active goals" else "Show archived goals") }
        }
        if (goals.isEmpty()) item { SectionCard { Text(if (archived) "No archived goals." else "What would you like to make time for?", style = MaterialTheme.typography.titleMedium); if (!archived) Text("Try gym, a home-cooked meal, or a few minutes learning something new.") } }
        items(goals, key = { it.id }) { goal ->
            val progress = JournalRules.progress(state, goal, LocalDate.parse(day))
            val animatedProgress by androidx.compose.animation.core.animateFloatAsState((progress / goal.target).toFloat().coerceIn(0f, 1f), label = "goal progress")
            SectionCard(Modifier.animateItem()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(goal.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (progress >= goal.target) Icon(Icons.Outlined.CheckCircle, "Target reached", tint = MaterialTheme.colorScheme.primary)
                }
                Text("${goal.period.replaceFirstChar { it.uppercase() }} · ${amountText(progress)} / ${amountText(goal.target)} ${goal.unit}")
                LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
                Row {
                    if (!archived) Button(onClick = { onLog(goal.id) }) { Text("Log activity") }
                    TextButton(onClick = { onArchive(goal.id, !archived) }) { Text(if (archived) "Restore goal" else "Archive") }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(state: Snapshot, modifier: Modifier, onEntry: (String) -> Unit = {}, today: LocalDate = LocalDate.now(), onWeekly: () -> Unit = {}, onDay: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var view by remember { mutableStateOf("Journal") }
    val days = (state.entries.map { it.day } + state.activities.map { it.day } + state.reviews.map { it.day }).distinct().sortedDescending().filter { day ->
        if (query.isBlank()) true else {
            val entries = state.entries.filter { it.day == day }
            val ids = entries.map { it.id }.toSet()
            day.contains(query, true) || entries.any { it.text.contains(query, true) || it.originalText.contains(query, true) } ||
                state.revisions.any { it.entryId in ids && it.text.contains(query, true) } ||
                state.reviews.any { it.day == day && (it.journal.contains(query, true) || it.summary.contains(query, true) || it.captions.any { c -> c.description.contains(query, true) }) } ||
                state.activities.any { it.day == day && it.note.contains(query, true) }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PageTitle("Your story", "Look back.", "Find a thought, a meal, or a moment.")
            OutlinedButton(onClick = onWeekly) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Weekly reflection") }
            StreakSummary(state, today) { view = "Stats" }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Journal", "Calendar", "Stats").forEach { label -> FilterChip(view == label, { view = label }, label = { Text(label) }) }
            }
            Spacer(Modifier.height(12.dp))
            if (view == "Journal") OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search your journal") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true, shape = RoundedCornerShape(16.dp))
            if (view == "Journal") Text("Search runs entirely on your phone.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        if (view == "Stats") item { JournalDashboard(state, today) }
        if (view == "Calendar") item { JournalCalendar(state, today, onDay, onEntry) }
        if (view == "Journal" && days.isEmpty()) item { Text(if (query.isEmpty()) "Your saved days will appear here." else "No matching days.") }
        if (view == "Journal") items(days, key = { it }) { day ->
            Card(onClick = { onDay(day) }, shape = RoundedCornerShape(28.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val count = state.entries.count { it.day == day }
                    Text("$count entries · ${JournalRules.workedOn(state, day)} goals worked on", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    val match = if (query.isNotBlank()) state.entries.firstOrNull { it.day == day && (it.text.contains(query, true) || it.originalText.contains(query, true)) } else null
                    Text(match?.let { if (it.text.contains(query, true)) it.text else "Original: " + it.originalText }
                        ?: state.reviews.filter { it.day == day }.maxByOrNull { it.created }?.summary
                        ?: state.entries.firstOrNull { it.day == day && it.text.isNotBlank() }?.text ?: "Photos, recordings, and progress.",
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (match != null) TextButton(onClick = { onEntry(match.id) }) { Text("Open matching note") }
                }
            }
        }
    }
}

@Composable private fun MomentDateBadge(date: LocalDate) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)) {
        Column(Modifier.width(48.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(date.format(DateTimeFormatter.ofPattern("EEE")).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
    }
}
