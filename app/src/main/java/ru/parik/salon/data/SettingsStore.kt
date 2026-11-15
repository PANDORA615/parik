package ru.parik.salon.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import ru.parik.salon.domain.Settings

private val Context.salonSettings by preferencesDataStore("salon_settings")
class SettingsStore(private val context: Context, private val dao: SalonDao) {
    private val key = stringPreferencesKey("settings_v1")
    private val json = Json { encodeDefaults = true }
    private val stored = context.salonSettings.data.map { it[key]?.let { value -> json.decodeFromString<Settings>(value) } ?: Settings() }
    // A Room import journal takes precedence until its DataStore write is complete.
    // This makes database + settings restoration crash-consistent across two stores.
    val flow: Flow<Settings> = combine(dao.pendingFlow(), stored) { pending, saved ->
        pending?.let { json.decodeFromString<Settings>(it.json) } ?: saved
    }
    suspend fun current(): Settings = dao.pending()?.let { json.decodeFromString<Settings>(it.json) } ?: stored.first()
    fun encode(settings: Settings) = json.encodeToString(settings)
    suspend fun flushPending() {
        val pending = dao.pending() ?: return
        context.salonSettings.edit { it[key] = pending.json }
        dao.clearPending(pending.json)
    }
}
