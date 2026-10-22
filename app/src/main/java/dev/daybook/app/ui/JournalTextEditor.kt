package dev.daybook.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable internal fun journalWritingStyle(): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 25.sp)

@Composable fun WriteDialog(onDismiss: () -> Unit, day: String = LocalDate.now().toString(), onSave: suspend (String) -> Unit) {
    JournalTextEditor(title = "Keep a moment", day = day, label = "What is on your mind?",
        initialText = "", saveLabel = "Save note", onDismiss = onDismiss, onSave = onSave)
}

/** A focused editor; the caller closes it only after the repository confirms the save. */
@Composable fun JournalTextEditor(title: String, day: String, label: String, initialText: String,
    saveLabel: String, onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    val baseline = rememberSaveable { initialText }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val dirty = draft.text != baseline
    val canSave = dirty && draft.text.isNotBlank() && !saving
    val words = remember(draft.text) { Regex("\\S+").findAll(draft.text).count() }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    fun close() { if (!saving) { if (dirty) confirmLeave = true else onDismiss() } }
    fun submit() {
        if (!canSave || saving) return
        saving = true; error = ""
        val text = draft.text
        scope.launch {
            try { onSave(text) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message?.take(240) ?: "Could not save your note. Your words are still here; please try again." }
            finally { saving = false }
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        val keyboard = LocalSoftwareKeyboardController.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = colors.background.luminance() > 0.5f
                    isAppearanceLightNavigationBars = colors.background.luminance() > 0.5f
                }
            }
        }
        val border by animateColorAsState(if (focused) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant,
            animationSpec = if (LocalReduceMotion.current) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(160),
            label = "writing focus")
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.25f), colors.background)))
                .systemBarsPadding().imePadding().padding(horizontal = 16.dp)) {
            val compact = maxHeight < 300.dp
            Column(Modifier.fillMaxSize().padding(vertical = if (compact) 2.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = ::close, enabled = !saving) { Icon(Icons.Outlined.Close, "Close note") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        if (!compact) Text(remember(day) { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("EEE, MMM d")) },
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Button(onClick = ::submit, enabled = canSave, modifier = Modifier.heightIn(min = 48.dp)) {
                        if (saving) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                        Text(if (saving) "Saving…" else saveLabel)
                    }
                }
                Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(if (compact) 16.dp else 24.dp),
                    color = colors.surface, border = BorderStroke(1.dp, border)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it; error = "" }, enabled = !saving,
                        label = if (compact) null else ({ Text(label) }),
                        placeholder = { Text("A thought, a small win, something to remember…", style = journalWritingStyle(), color = colors.onSurfaceVariant) },
                        modifier = Modifier.fillMaxSize().padding(if (compact) 0.dp else 6.dp).semantics { contentDescription = label }.focusRequester(focus).onFocusChanged { focused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter) { submit(); true } else false
                            },
                        textStyle = journalWritingStyle(), shape = RoundedCornerShape(20.dp),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = true, imeAction = ImeAction.Default),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            disabledBorderColor = Color.Transparent))
                }
                if (error.isNotBlank()) Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (!compact) Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (saving) "Saving your words…" else if (dirty) "Unsaved draft" else "A little space for your day",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Text("$words ${if (words == 1) "word" else "words"}", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                    IconButton(onClick = { if (imeVisible) keyboard?.hide() else { focus.requestFocus(); keyboard?.show() } }, enabled = !saving) {
                        Icon(if (imeVisible) Icons.Outlined.KeyboardHide else Icons.Outlined.Keyboard,
                            if (imeVisible) "Hide keyboard" else "Show keyboard")
                    }
                }
            }
            }
        }
        LaunchedEffect(view) {
            // A dialog can compose before Android gives its window focus.
            // Waiting for focus makes automatic keyboard opening reliable.
            if (!view.hasWindowFocus()) suspendCancellableCoroutine<Unit> { continuation ->
                val observer = view.viewTreeObserver
                lateinit var listener: android.view.ViewTreeObserver.OnWindowFocusChangeListener
                listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                    if (hasFocus && continuation.isActive) {
                        if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                        continuation.resume(Unit)
                    }
                }
                observer.addOnWindowFocusChangeListener(listener)
                continuation.invokeOnCancellation { if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener) }
                if (view.hasWindowFocus() && continuation.isActive) {
                    observer.removeOnWindowFocusChangeListener(listener)
                    continuation.resume(Unit)
                }
            }
            focus.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }
    if (confirmLeave) AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text("Discard this draft?") },
        text = { Text("Your unsaved words will be discarded. Saved journal entries stay as they are.") },
        confirmButton = { TextButton(onClick = { confirmLeave = false; onDismiss() }) { Text("Discard draft") } },
        dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep writing") } })
}
