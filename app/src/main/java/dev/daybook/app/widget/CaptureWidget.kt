package dev.daybook.app.widget

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.widget.RemoteViews
import dev.daybook.app.MainActivity
import dev.daybook.app.R

/** Explicit activity launch keeps microphone startup in a visible, user-initiated flow. */
object CaptureIntents {
    private fun token(context: Context): String {
        val prefs = context.getSharedPreferences("widget_intents", Context.MODE_PRIVATE)
        return prefs.getString("token", null) ?: java.util.UUID.randomUUID().toString().also {
            check(prefs.edit().putString("token", it).commit())
        }
    }
    fun captureFrom(context: Context, intent: Intent): String {
        val capture = intent.getStringExtra("capture").orEmpty()
        // The exported launcher must not let arbitrary apps start the microphone.
        if (capture == "record" && intent.getStringExtra("widget_token") != token(context)) return ""
        return capture.takeIf { it in setOf("record", "voice", "write", "photo") }.orEmpty()
    }
    fun pending(context: Context, widgetId: Int, capture: String): PendingIntent =
        PendingIntent.getActivity(context, widgetId,
            Intent(context, MainActivity::class.java)
                .setAction("dev.daybook.CAPTURE.$capture.$widgetId")
                .putExtra("capture", capture).putExtra("widget_token", token(context))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class CaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.capture_widget)
            listOf(R.id.widget_record to "record", R.id.widget_write to "write", R.id.widget_photo to "photo").forEach { (view, capture) ->
                views.setOnClickPendingIntent(view, CaptureIntents.pending(context, id, capture))
            }
            manager.updateAppWidget(id, views)
        }
    }
}

/** A dedicated, compact microphone widget for the home screen. */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.record_widget)
            views.setOnClickPendingIntent(R.id.record_widget_root, CaptureIntents.pending(context, id, "record"))
            manager.updateAppWidget(id, views)
        }
    }
}

/** Two independent capture shortcuts in a compact home-screen tile. */
class NoteVoiceWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, views(context, id)) }
    }

    companion object {
        internal fun views(context: Context, id: Int) =
            RemoteViews(context.packageName, R.layout.note_voice_widget).apply {
                setOnClickPendingIntent(R.id.note_voice_write, CaptureIntents.pending(context, id, "write"))
                setOnClickPendingIntent(R.id.note_voice_record, CaptureIntents.pending(context, id, "record"))
            }
    }
}
