package ru.parik.salon

import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.parik.salon.data.*
import ru.parik.salon.domain.*
import ru.parik.salon.platform.ReminderWorker
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderTest {
    private val app get() = ApplicationProvider.getApplicationContext<SalonApplication>()
    private val repository get() = app.repository
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private lateinit var appointment: Appointment
    @Before fun setup() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("pm grant ru.parik.salon android.permission.POST_NOTIFICATIONS").close()
        }
        val now = System.currentTimeMillis()
        val client = Client(UUID.randomUUID().toString(), "Private client", createdAt = now, updatedAt = now)
        val service = Service(UUID.randomUUID().toString(), "Private service", 30, currencyCode = "RUB", createdAt = now, updatedAt = now)
        repository.restore(Backup(exportedAt = now, clients = listOf(client), services = listOf(service), appointments = emptyList(), settings = Settings(reminderMinutes = 15, starterOffered = true)))
        repository.saveAppointment(Appointment(UUID.randomUUID().toString(), client.id, service.id, now + 3600000, 30, currencyCode = "RUB", serviceNameSnapshot = service.name, clientNameSnapshot = client.name, createdAt = now, updatedAt = now), allowOutside = true)
        appointment = repository.dao.allAppointments().single()
        notifications.cancelAll()
    }
    private suspend fun runOriginalWorker() {
        TestListenableWorkerBuilder<ReminderWorker>(app)
            .setInputData(workDataOf("id" to appointment.id, "revision" to appointment.updatedAt, "minutes" to 15)).build().doWork()
    }
    private fun hasNotification() = notifications.activeNotifications.any { it.tag == appointment.id }
    @Test fun cancelledAppointmentDoesNotNotify() = runBlocking {
        repository.changeStatus(appointment.id, Status.CANCELLED); runOriginalWorker(); assertFalse(hasNotification())
    }
    @Test fun movedAppointmentRejectsOldWorker() = runBlocking {
        repository.saveAppointment(appointment.copy(startAt = appointment.startAt + 3600000), allowOutside = true)
        runOriginalWorker(); assertFalse(hasNotification())
    }
    @Test fun deletedAppointmentDoesNotNotify() = runBlocking {
        repository.deleteAppointment(appointment.id); runOriginalWorker(); assertFalse(hasNotification())
    }
    @Test fun disabledRemindersDoNotNotify() = runBlocking {
        repository.saveSettings(repository.settings.current().copy(reminderMinutes = 0)); runOriginalWorker(); assertFalse(hasNotification())
    }
}
