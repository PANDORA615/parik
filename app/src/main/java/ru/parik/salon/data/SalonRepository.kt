package ru.parik.salon.data

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.parik.salon.domain.*
import java.time.*
import java.util.UUID

class SalonRepository(val db: SalonDatabase, val settings: SettingsStore, private val clock: Clock = Clock.systemUTC()) {
    val dao = db.dao()
    private val mutex = Mutex()
    suspend fun saveClient(client: Client, allowDuplicate: Boolean = false) = mutex.withLock {
        Rules.name(client.name)
        val normalized = Rules.normalizePhone(client.phone)
        db.withTransaction {
            val duplicates = normalized?.let { dao.duplicates(it, client.id) }.orEmpty()
            if (duplicates.isNotEmpty() && !allowDuplicate) throw RuleViolation(Problem.DUPLICATE, duplicates.map { it.id })
            dao.put(client.copy(name = client.name.trim(), normalizedPhone = normalized, updatedAt = clock.millis()))
        }
    }
    suspend fun saveService(service: Service) = mutex.withLock {
        Rules.name(service.name); Rules.duration(service.defaultDurationMinutes)
        Rules.price(service.defaultPriceMinor); Rules.currency(service.currencyCode)
        dao.put(service.copy(name = service.name.trim(), updatedAt = clock.millis()))
    }
    suspend fun saveAppointment(appointment: Appointment, allowOutside: Boolean = false) = mutex.withLock {
        saveAppointmentLocked(appointment, allowOutside)
    }
    private suspend fun saveAppointmentLocked(appointment: Appointment, allowOutside: Boolean) {
        Rules.duration(appointment.durationMinutes); Rules.price(appointment.priceMinor); Rules.currency(appointment.currencyCode)
        val start = Instant.ofEpochMilli(appointment.startAt)
        if (appointment.startAt !in 0..253402214400000L) throw RuleViolation(Problem.DATE)
        Rules.transition(appointment.status, start, clock.instant())
        db.withTransaction {
            val previous = dao.appointment(appointment.id)
            val client = dao.client(appointment.clientId) ?: throw RuleViolation(Problem.CLIENT)
            val service = dao.service(appointment.serviceId) ?: throw RuleViolation(Problem.SERVICE)
            if (client.archived && previous?.clientId != client.id) throw RuleViolation(Problem.CLIENT)
            if (service.archived && previous?.serviceId != service.id) throw RuleViolation(Problem.SERVICE)
            if (appointment.status == Status.PLANNED) {
                val conflicts = dao.conflicts(appointment.startAt, appointment.interval().end.toEpochMilli(), appointment.id)
                if (conflicts.isNotEmpty()) throw RuleViolation(Problem.CONFLICT, conflicts.map { it.id })
                if (!allowOutside && !Rules.insideHours(start, appointment.durationMinutes, settings.current(), ZoneId.systemDefault()))
                    throw RuleViolation(Problem.OUTSIDE_HOURS)
            }
            dao.put(appointment.copy(
                clientNameSnapshot = if (previous?.clientId == client.id) previous.clientNameSnapshot else client.name,
                serviceNameSnapshot = if (previous?.serviceId == service.id) previous.serviceNameSnapshot else service.name,
                updatedAt = clock.millis()
            ))
        }
    }
    suspend fun changeStatus(id: String, status: Status, actualPrice: Long? = null) = mutex.withLock {
        val appointment = dao.appointment(id) ?: throw RuleViolation(Problem.NOT_FOUND)
        saveAppointmentLocked(appointment.copy(status = status, priceMinor = actualPrice ?: appointment.priceMinor), allowOutside = true)
    }
    suspend fun archiveClient(id: String) = mutex.withLock { dao.archiveClient(id, clock.millis()) }
    suspend fun archiveService(id: String) = mutex.withLock { dao.archiveService(id, clock.millis()) }
    suspend fun deleteAppointment(id: String) = mutex.withLock { dao.deleteAppointment(id) }
    suspend fun saveSettings(value: Settings) = mutex.withLock {
        Rules.settings(value)
        dao.put(PendingSettings(json = settings.encode(value)))
        // Journal remains authoritative if the DataStore write fails; retry on next start.
        runCatching { settings.flushPending() }
        Unit
    }
    suspend fun seedServices(names: List<String>) = mutex.withLock {
        if (settings.current().starterOffered) return@withLock
        db.withTransaction {
            names.zip(listOf(30, 60, 30, 120, 45)).forEach { (name, duration) ->
                val now = clock.millis()
                dao.put(Service(UUID.randomUUID().toString(), name, duration, currencyCode = settings.current().currency, createdAt = now, updatedAt = now))
            }
            dao.put(PendingSettings(json = settings.encode(settings.current().copy(starterOffered = true))))
        }
        runCatching { settings.flushPending() }
        Unit
    }
    suspend fun export(): Backup = mutex.withLock {
        db.withTransaction { Backup(exportedAt = clock.millis(), clients = dao.allClients(), services = dao.allServices(), appointments = dao.allAppointments(), settings = settings.current()) }
    }
    suspend fun restore(backup: Backup) = mutex.withLock {
        BackupCodec.validate(backup)
        db.withTransaction {
            dao.clearAppointments(); dao.clearClients(); dao.clearServices()
            backup.clients.forEach { dao.put(it) }; backup.services.forEach { dao.put(it) }; backup.appointments.forEach { dao.put(it) }
            dao.put(PendingSettings(json = settings.encode(backup.settings)))
        }
        runCatching { settings.flushPending() }
        Unit
    }
}
