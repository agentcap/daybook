package dev.daybook.app.data
import android.content.Context
import android.os.Build
import dev.daybook.app.AppGraph
import dev.daybook.app.BuildConfig
object SupportDiagnostics {
    fun text(context: Context, graph: AppGraph): String = listOf(
        "Daybook " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")",
        "Android API " + Build.VERSION.SDK_INT,
        "Device " + Build.MANUFACTURER + " " + Build.MODEL,
        "Free app storage (MB): " + context.filesDir.usableSpace / 1048576,
        "Speech model ready: " + graph.model.ready(),
        "Review model ready: " + graph.localModel.ready()
    ).joinToString("\n")
}
