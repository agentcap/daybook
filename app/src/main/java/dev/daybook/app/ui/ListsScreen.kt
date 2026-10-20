package dev.daybook.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.daybook.core.*

@Composable fun ListsScreen(state: Snapshot, modifier: Modifier, initiallySuggestions: Boolean, onNew: (String) -> Unit,
    onEdit: (ListItem) -> Unit, onUpdate: (ListItem) -> Unit, onReview: (Proposal) -> Unit,
    onReject: (String) -> Unit, onUndo: (String) -> Unit, onEntry: (String) -> Unit) {
    var section by rememberSaveable { mutableStateOf(if (initiallySuggestions) "Suggestions" else "To-dos") }
    var filter by rememberSaveable { mutableStateOf("Open") }
    val pending = state.proposals.filter { it.status == "pending" }
    val items = state.items.filter { it.kind == if (section == "Ideas") "idea" else "todo" }
        .filter { when(filter) { "Archived" -> it.archived; "Done" -> it.done && !it.archived; else -> !it.done && !it.archived } }
        .sortedByDescending { it.created }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            PageTitle("Room for later", "Little things, big possibilities.", "To-dos and ideas have a home of their own.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("To-dos", "Ideas", "Suggestions").forEach { label ->
                    FilterChip(section == label, { section = label }, label = { Text(if (label == "Suggestions" && pending.isNotEmpty()) "Suggestions · ${pending.size}" else label) })
                }
            }
            if (section != "Suggestions") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onNew(if (section == "Ideas") "idea" else "todo") }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text(if (section == "Ideas") "New idea" else "New to-do") }
                    Spacer(Modifier.weight(1f))
                    Text("${items.size} items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Open", "Done", "Archived").forEach { label -> FilterChip(filter == label, { filter = label }, label = { Text(if (section == "Ideas" && label == "Done") "Tried" else label) }) }
                }
            }
        }
        if (section == "Suggestions") {
            item { Text("Found during daily reflection. Review, edit, or dismiss each suggestion. Nothing is added or counted automatically.", style = MaterialTheme.typography.bodyMedium) }
            if (pending.isEmpty()) item { SectionCard { Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary); Text("No suggestions waiting."); Text("When a note mentions a new goal, task, or idea, daily reflection can suggest it here.", style = MaterialTheme.typography.bodySmall) } }
            items(pending, key = { it.id }) { p ->
                val current = PlannerRules.current(state, p)
                SectionCard(Modifier.animateItem()) {
                    Text("${p.item.kind.uppercase()} · ${p.day}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(p.item.title, style = MaterialTheme.typography.titleMedium)
                    if (p.item.details.isNotBlank()) Text(p.item.details)
                    if (p.item.kind == "goal") Text("${p.item.period} · ${amountText(p.item.target)} ${p.item.unit}", style = MaterialTheme.typography.bodySmall)
                    if (!current) Text("Supporting notes changed. Refresh that day's review.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    p.item.sources.forEach { id ->
                        TextButton(onClick = { onEntry(id) }, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Outlined.Link, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                            Text(state.entries.find { it.id == id }?.text?.take(70)?.ifBlank { "Original note" } ?: "Original note", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { onReview(p) }, enabled = current) { Text("Review suggestion") }
                        TextButton(onClick = { onReject(p.id) }) { Text("Dismiss") }
                    }
                }
            }
            val accepted = state.proposals.filter { it.status == "accepted" }.takeLast(5).reversed()
            if (accepted.isNotEmpty()) item { Text("Recently added", style = MaterialTheme.typography.titleMedium) }
            items(accepted, key = { it.id }) { p ->
                SectionCard(Modifier.animateItem()) {
                    Text(p.item.title, style = MaterialTheme.typography.titleSmall)
                    Text(if (p.item.kind == "goal") "Created in Goals. No activity was logged." else "Added to ${if (p.item.kind == "todo") "To-dos" else "Ideas"}.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onUndo(p.id) }) { Text("Undo creation") }
                }
            }
        } else {
            if (items.isEmpty()) item { SectionCard {
                Icon(if (section == "Ideas") Icons.Outlined.Lightbulb else Icons.Outlined.Checklist, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (filter != "Open") "Nothing here yet." else if (section == "Ideas") "Keep something you’d like to try." else "One less thing to keep in your head.")
                Text("Add your own, or approve a suggestion from daily reflection.", style = MaterialTheme.typography.bodySmall)
            } }
            items(items, key = { it.id }) { item ->
                SectionCard(Modifier.animateItem().animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(item.done, { onUpdate(item.copy(done = it)) }, modifier = Modifier.semantics { contentDescription = "Mark ${item.title} ${if (item.done) "open" else "done"}" })
                        Column(Modifier.weight(1f).clickable { onEdit(item) }.padding(start = 6.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            if (item.details.isNotBlank()) Text(item.details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onEdit(item) }) { Icon(Icons.Outlined.Edit, "Edit item", Modifier.size(18.dp)) }
                    }
                    if (item.kind == "idea") Text(if (item.done) "Tried" else "Something to explore", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row {
                        TextButton(onClick = { onUpdate(item.copy(archived = !item.archived)) }) { Text(if (item.archived) "Restore item" else "Archive item") }
                        item.sources.firstOrNull()?.let { id -> TextButton(onClick = { onEntry(id) }) { Text("Source note") } }
                    }
                }
            }
        }
    }
}

@Composable fun ListItemDialog(kind: String, initial: ListItem? = null, proposal: Proposal? = null, evidence: String = "",
    onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: proposal?.item?.title.orEmpty()) }
    var details by remember { mutableStateOf(initial?.details ?: proposal?.item?.details.orEmpty()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (proposal != null) "Review suggested ${if (kind == "todo") "to-do" else "idea"}" else if (initial != null) "Edit item" else if (kind == "todo") "A little to-do" else "Something to try") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Item title") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(details, { details = it }, label = { Text("Details") }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
                if (evidence.isNotBlank()) { Text("From your notes", style = MaterialTheme.typography.labelLarge); Text(evidence, style = MaterialTheme.typography.bodySmall) }
                if (proposal != null) Text("Only added after you approve. It starts as an open item.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { Button(onClick = { onSave(title.trim(), details.trim()) }, enabled = title.isNotBlank() && title.length <= 200 && details.length <= 4000) { Text(if (proposal != null) "Approve & add" else "Save item") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
