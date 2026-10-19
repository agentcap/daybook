package dev.daybook.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.daybook.app.capture.RecordingSession

@Composable fun RecordingScreen(session: RecordingSession, active: Boolean, modelReady: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit, onPause: () -> Unit = {}, onBookmark: () -> Unit = {}, reduceMotion: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val scroll = rememberScrollState()
    LaunchedEffect(session.transcript) { kotlinx.coroutines.delay(80); if (reduceMotion) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue) }
    val level by animateFloatAsState(if (session.phase == "recording") session.level else 0f, if (reduceMotion) snap() else tween(140), label = "microphone level")
    val alpha = if (reduceMotion || !active || session.phase != "recording") 1f else {
        val pulse = rememberInfiniteTransition(label = "recording pulse")
        val value by pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "recording indicator")
        value
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = colors.background.luminance() > 0.5f
                    isAppearanceLightNavigationBars = colors.background.luminance() > 0.5f
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = colors.primaryContainer) { Icon(Icons.Outlined.Mic, null, Modifier.padding(10.dp), tint = colors.primary) }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(if (active) "Recording your day" else if (session.phase == "saved") "A thought, kept." else "Capture a thought", style = MaterialTheme.typography.titleLarge)
                        Text("On this phone · original audio preserved", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ExpandMore, if (active) "Keep recording in background" else "Close recording") }
                }
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).alpha(if (active && session.phase == "recording" && !reduceMotion) alpha else 1f).background(if (active) colors.error else colors.primary, CircleShape))
                        Text(when(session.phase) { "starting" -> "Starting…"; "paused" -> "Paused"; "saving" -> "Saving your audio…"; "saved" -> "Saved on this phone"; "error" -> "Needs attention"; else -> if (active) "Listening" else "Ready when you are" },
                            Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        Text("%02d:%02d".format(session.elapsedMs / 60000, (session.elapsedMs / 1000) % 60), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge)
                    }
                    Canvas(Modifier.fillMaxWidth().height(58.dp)) {
                        val bars = 38; val gap = size.width / bars
                        repeat(bars) { i ->
                            val envelope = 0.25f + 0.75f * kotlin.math.sin((i + 1) * 1.7).toFloat().let { kotlin.math.abs(it) }
                            val height = (5.dp.toPx() + level * size.height * envelope).coerceAtMost(size.height)
                            drawLine(colors.primary.copy(alpha = 0.45f + envelope * 0.5f),
                                androidx.compose.ui.geometry.Offset(gap * (i + .5f), (size.height - height) / 2),
                                androidx.compose.ui.geometry.Offset(gap * (i + .5f), (size.height + height) / 2), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Subtitles, null, Modifier.size(18.dp), tint = colors.primary)
                    Text("LIVE TRANSCRIPT", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    if (session.transcript.isNotBlank()) Text("· draft", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Surface(Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface) {
                    Column(Modifier.padding(20.dp).verticalScroll(scroll).then(if (reduceMotion) Modifier else Modifier.animateContentSize()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectionContainer {
                            Text(session.transcript.ifBlank { if (session.phase == "saved") "No live text was captured. Your audio is saved; open the note to retry or add text." else if (active && modelReady) "Your words will appear here as you speak…" else if (!modelReady) "Your audio is safe. Download the English model in Settings for live transcripts." else "Speak naturally. A small moment is enough." },
                                style = MaterialTheme.typography.bodyLarge, color = if (session.transcript.isBlank()) colors.onSurfaceVariant else colors.onSurface)
                        }
                    }
                }
                Text(if (session.phase == "paused") "Paused · resume when you are ready." else session.previewStatus, style = MaterialTheme.typography.bodySmall,
                    color = if (session.phase == "error") colors.error else colors.onSurfaceVariant)
                if (active) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onPause, enabled = session.phase in setOf("recording", "paused"), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(if (session.phase == "paused") Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null)
                            Spacer(Modifier.width(6.dp)); Text(if (session.phase == "paused") "Resume" else "Pause")
                        }
                        OutlinedButton(onClick = onBookmark, enabled = session.phase in setOf("recording", "paused"), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.BookmarkAdd, null); Spacer(Modifier.width(6.dp)); Text("Mark · ${session.bookmarks}")
                        }
                    }
                    Button(onClick = onStop, enabled = session.phase !in setOf("starting", "saving"), modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)) {
                        Icon(Icons.Outlined.StopCircle, null); Spacer(Modifier.width(10.dp)); Text("Stop & save recording")
                    }
                    Text("You can lock the screen or close this view. Stop from the recording notification.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else if (session.phase == "saved") Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Back to my day") }
                else Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Outlined.Mic, null); Spacer(Modifier.width(8.dp)); Text("Start recording") }
            }
        }
    }
}
