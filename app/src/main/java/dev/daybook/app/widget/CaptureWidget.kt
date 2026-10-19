package dev.daybook.app.widget
import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.widget.RemoteViews
import dev.daybook.app.MainActivity
import dev.daybook.app.R

class CaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.capture_widget)
            listOf(R.id.widget_record to "voice", R.id.widget_write to "write", R.id.widget_photo to "photo").forEachIndexed { index, (view, capture) ->
                val intent = Intent(context, MainActivity::class.java).putExtra("capture", capture).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                views.setOnClickPendingIntent(view, PendingIntent.getActivity(context, id * 4 + index, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(id, views)
        }
    }
}
