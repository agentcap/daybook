package dev.daybook.app.data
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import dev.daybook.core.*
import kotlinx.coroutines.flow.*

private val Context.daybookPreferences by preferencesDataStore(name = "daybook_preferences")
class Preferences(private val context: Context) {
    private val gson = Gson()
    private val key = stringPreferencesKey("settings_v1")
    val changes: Flow<Settings> = context.daybookPreferences.data.map { p ->
        p[key]?.let { gson.fromJson(it, Settings::class.java) } ?: Settings()
    }
    suspend fun read() = changes.first()
    suspend fun save(s: Settings) {
        JournalRules.validateSettings(s)
        context.daybookPreferences.edit { it[key] = gson.toJson(s) }
    }
}
