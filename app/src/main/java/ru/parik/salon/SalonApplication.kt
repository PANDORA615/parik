package ru.parik.salon

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.*
import ru.parik.salon.data.*
import ru.parik.salon.platform.ReminderScheduler

class SalonApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, SalonDatabase::class.java, "salon.db").build() }
    val repository by lazy { SalonRepository(database, SettingsStore(this, database.dao())) }
    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching { repository.settings.flushPending() }
            // Retry reconciliation if a transient platform error occurs. Data is
            // already committed and remains usable even when reminders fail.
            while (isActive) {
                try { ReminderScheduler(this@SalonApplication, repository).observe() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { delay(30_000) }
            }
        }
    }
}
