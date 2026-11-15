package ru.parik.salon.platform

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import ru.parik.salon.R
import ru.parik.salon.SalonApplication
import ru.parik.salon.MainActivity
import ru.parik.salon.data.*
import ru.parik.salon.domain.*

class ReminderScheduler(private val context: Context, private val repository: SalonRepository) {
    private val manager = WorkManager.getInstance(context)
    suspend fun observe() {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_DEFAULT))
        combine(repository.dao.appointments(), repository.settings.flow) { appointments, settings -> appointments to settings }
            .collect { (appointments, settings) ->
                manager.cancelAllWorkByTag(TAG).result.get()
                notifications.cancelAll()
                if (settings.reminderMinutes == 0) return@collect
                val now = System.currentTimeMillis()
                appointments.filter { it.status == Status.PLANNED && it.startAt > now }.forEach { appointment ->
                    val delay = (appointment.startAt - settings.reminderMinutes * 60000L - now).coerceAtLeast(0)
                    val request = OneTimeWorkRequestBuilder<ReminderWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf("id" to appointment.id, "revision" to appointment.updatedAt, "minutes" to settings.reminderMinutes))
                        .addTag(TAG).build()
                    manager.enqueueUniqueWork("appointment-${appointment.id}", ExistingWorkPolicy.REPLACE, request)
                }
            }
    }
    companion object { const val TAG = "salon-reminder"; const val CHANNEL = "appointments" }
}
class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as SalonApplication).repository
        val id = inputData.getString("id") ?: return Result.success()
        val appointment = repository.dao.appointment(id) ?: return Result.success()
        val settings = repository.settings.current()
        if (appointment.status != Status.PLANNED || appointment.updatedAt != inputData.getLong("revision", -1) ||
            settings.reminderMinutes == 0 || settings.reminderMinutes != inputData.getInt("minutes", -1) ||
            appointment.startAt + 15 * 60000L < System.currentTimeMillis()) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 && applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val intent = Intent(applicationContext, MainActivity::class.java).putExtra("appointment_id", id)
        val pending = PendingIntent.getActivity(applicationContext, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_salon).setContentTitle(applicationContext.getString(R.string.reminder_title))
            .setContentText(applicationContext.getString(R.string.reminder_body)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true).build()
        // Re-read after building: cancelled or moved appointments must not notify.
        if (repository.dao.appointment(id) != appointment || repository.settings.current().reminderMinutes != settings.reminderMinutes) return Result.success()
        applicationContext.getSystemService(NotificationManager::class.java).notify(id, 0, notification)
        return Result.success()
    }
}
