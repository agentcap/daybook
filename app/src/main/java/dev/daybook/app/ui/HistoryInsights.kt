package dev.daybook.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.daybook.core.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun StreakSummary(state: Snapshot, today: LocalDate, onStats: () -> Unit) {
    val stats = remember(state, today) { StatsRules.calculate(state, today) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("${stats.currentStreak} day streak", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onStats) { Text("View stats") }
    }
}
@Composable private fun StatTile(value: String, title: String, modifier: Modifier) {
    SectionCard(modifier) { Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.bodySmall) }
}
@Composable fun JournalDashboard(state: Snapshot, today: LocalDate) {
    val stats = remember(state, today) { StatsRules.calculate(state, today) }
    val efforts = state.activities.filter { !it.undone && LocalDate.parse(it.day) <= today }.map { it.goalId to it.day }.distinct().size
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stats.currentStreak.toString(), "Current streak", Modifier.weight(1f))
            StatTile(stats.longestStreak.toString(), "Best streak", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stats.notes.toString(), "Saved notes", Modifier.weight(1f))
            StatTile(stats.journalDays.toString(), "Days journaled", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(stats.photos.toString(), "Photos kept", Modifier.weight(1f))
            StatTile(efforts.toString(), "Goal check-ins", Modifier.weight(1f))
        }
        SectionCard {
            Text("Your last seven days", style = MaterialTheme.typography.titleMedium)
            val dates = (6L downTo 0L).map { today.minusDays(it) }
            val counts = dates.map { date -> state.entries.count { it.day == date.toString() && it.status != "recording" } }
            val max = counts.maxOrNull()?.coerceAtLeast(1) ?: 1
            Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                dates.forEachIndexed { i, date ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(counts[i].toString(), style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.fillMaxWidth().height((8 + 60 * counts[i] / max).dp).background(MaterialTheme.colorScheme.primary.copy(alpha = if (counts[i] > 0) 0.75f else 0.15f), CircleShape))
                        Text(date.format(DateTimeFormatter.ofPattern("EEEEE")), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text("Streaks count days with a saved note, photo, or voice recording. Yesterday’s streak stays active until today ends. Goal check-ins count each goal once per day.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun JournalCalendar(state: Snapshot, today: LocalDate, onDay: (String) -> Unit, onEntry: (String) -> Unit) {
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var selected by remember { mutableStateOf(today) }
    val notes = state.entries.groupBy { it.day }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Outlined.ChevronLeft, "Previous month") }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) { Icon(Icons.Outlined.ChevronRight, "Next month") }
        }
        Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        val start = month.atDay(1).dayOfWeek.value - 1
        val rows = (start + month.lengthOfMonth() + 6) / 7
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val day = row * 7 + col - start + 1
                    if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(48.dp))
                    else {
                        val date = month.atDay(day)
                        val count = notes[date.toString()].orEmpty().count { it.status != "recording" }
                        val isSelected = selected == date
                        Column(Modifier.weight(1f).height(48.dp).semantics { contentDescription = "$date · $count notes" }
                            .clickable(enabled = date <= today) { selected = date },
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Surface(shape = CircleShape, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface) {
                                Text(day.toString(), Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else if (date > today) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                            }
                            Box(Modifier.size(4.dp).background(if (count > 0) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent, CircleShape))
                        }
                    }
                }
            }
        }
        Text("A dot marks days with saved moments.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(selected.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onDay(selected.toString()) }) { Text("Open day") }
    }
    val entries = notes[selected.toString()].orEmpty().sortedByDescending { it.created }
    if (entries.isEmpty()) Text("No notes on this day.", style = MaterialTheme.typography.bodyMedium)
    entries.forEach { entry ->
        SectionCard(Modifier.padding(bottom = 10.dp).clickable { onEntry(entry.id) }.animateContentSize()) {
            Text(Instant.ofEpochMilli(entry.created).atZone(ZoneId.of(entry.zone)).format(DateTimeFormatter.ofPattern("h:mm a")), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(entry.text.ifBlank { if (entry.kind == "photo") "Photo moment" else "Voice recording" }, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
