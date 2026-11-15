package ru.parik.salon

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.parik.salon.data.*
import ru.parik.salon.domain.*

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: SalonDatabase
    private lateinit var repository: SalonRepository
    private val clock = Clock.fixed(Instant.parse("2026-11-15T12:00:00Z"), ZoneOffset.UTC)
    private val client = Client(UUID.randomUUID().toString(), "Анна", "+7 123", "+7123", createdAt = 1, updatedAt = 1)
    private val service = Service(UUID.randomUUID().toString(), "Стрижка", 60, currencyCode = "RUB", createdAt = 1, updatedAt = 1)
    private fun appointment(time: String = "10:00") = Appointment(UUID.randomUUID().toString(), client.id, service.id,
        LocalDate.of(2026, 11, 16).atTime(LocalTime.parse(time)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 60,
        currencyCode = "RUB", serviceNameSnapshot = service.name, clientNameSnapshot = client.name, createdAt = 1, updatedAt = 1)
    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SalonDatabase::class.java).build()
        repository = SalonRepository(db, SettingsStore(context, db.dao()), clock)
        repository.saveSettings(Settings(starterOffered = true))
        repository.saveClient(client); repository.saveService(service)
    }
    @After fun close() { db.close() }
    @Test fun concurrentConflictsCannotBothCommit() = runBlocking {
        val outcomes = coroutineScope { listOf(appointment(), appointment("10:30")).map { value -> async(Dispatchers.IO) { runCatching { repository.saveAppointment(value) }.isSuccess } }.awaitAll() }
        assertEquals(1, outcomes.count { it }); assertEquals(1, db.dao().allAppointments().size)
    }
    @Test fun cancellationMoveAndEditUpdateAvailability() = runBlocking {
        val a = appointment(); repository.saveAppointment(a)
        repository.saveAppointment(a.copy(startAt = appointment("14:00").startAt))
        repository.saveAppointment(appointment())
        repository.changeStatus(a.id, Status.CANCELLED)
        repository.saveAppointment(appointment("14:00"))
        assertEquals(3, db.dao().allAppointments().size)
    }
    @Test fun foreignKeysPreventOrphansAndArchivingPreservesHistory() = runBlocking {
        val a = appointment(); repository.saveAppointment(a)
        repository.archiveClient(client.id); repository.archiveService(service.id)
        assertEquals(1, db.dao().allAppointments().size)
        assertTrue(db.dao().client(client.id)!!.archived)
        assertTrue(runCatching { db.dao().clearClients() }.isFailure)
        repository.saveAppointment(a.copy(notes = "История"))
    }
    @Test fun invalidRestoreDoesNotTouchDatabase() = runBlocking {
        repository.saveAppointment(appointment())
        val before = repository.export()
        assertTrue(runCatching { repository.restore(before.copy(clients = emptyList())) }.isFailure)
        assertEquals(before.copy(exportedAt = 0), repository.export().copy(exportedAt = 0))
    }
    @Test fun exportRestoreRestoresRelationsSettingsAndArchive() = runBlocking {
        repository.saveAppointment(appointment()); repository.archiveClient(client.id)
        repository.saveSettings(Settings(currency = "EUR", reminderMinutes = 30, starterOffered = true))
        val backup = repository.export()
        repository.restore(Backup(exportedAt = 0, clients = emptyList(), services = emptyList(), appointments = emptyList(), settings = Settings()))
        repository.restore(backup)
        assertEquals(backup.copy(exportedAt = 0), repository.export().copy(exportedAt = 0))
    }
    @Test fun databaseJournalWinsOverDataStoreAfterInterruptedImport() = runBlocking {
        val expected = Settings(currency = "USD", reminderMinutes = 60)
        db.dao().put(PendingSettings(json = repository.settings.encode(expected)))
        assertEquals(expected, repository.settings.current())
        assertEquals(expected, repository.settings.flow.first())
        repository.settings.flushPending()
        assertNull(db.dao().pending()); assertEquals(expected, repository.settings.current())
    }
    @Test fun dayFlowReactsAndIncludesCrossMidnightAppointment() = runBlocking {
        val day = LocalDate.of(2026, 11, 16).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val flow = db.dao().day(day.toEpochMilli(), day.plusSeconds(86400).toEpochMilli())
        assertTrue(flow.first().isEmpty())
        val overnight = appointment().copy(startAt = day.minusSeconds(1800).toEpochMilli(), durationMinutes = 90)
        repository.saveAppointment(overnight, allowOutside = true)
        assertEquals(overnight.id, flow.first().single().id)
    }
    @Test fun completedPastDoesNotBlockAndSnapshotSurvivesRename() = runBlocking {
        val past = appointment().copy(startAt = clock.millis() - 7200000, status = Status.COMPLETED)
        repository.saveAppointment(past, allowOutside = true)
        repository.saveClient(client.copy(name = "Анна Новая"))
        repository.saveService(service.copy(name = "Новое название"))
        repository.saveAppointment(appointment().copy(startAt = past.startAt), allowOutside = true)
        assertEquals("Стрижка", db.dao().appointment(past.id)!!.serviceNameSnapshot)
    }
    @Test fun diskDatabaseSurvivesReopenAndHasRequiredIndices() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-${UUID.randomUUID()}.db"
        val disk = Room.databaseBuilder(context, SalonDatabase::class.java, name).build()
        disk.dao().put(client); disk.close()
        val reopened = Room.databaseBuilder(context, SalonDatabase::class.java, name).build()
        try {
            assertEquals(client, reopened.dao().client(client.id))
            reopened.openHelper.readableDatabase.query("PRAGMA index_list(appointments)").use { cursor -> assertTrue(cursor.count >= 4) }
        } finally { reopened.close(); context.deleteDatabase(name) }
    }
}
