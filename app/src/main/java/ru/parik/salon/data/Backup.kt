package ru.parik.salon.data

import java.io.InputStream
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import ru.parik.salon.domain.*

@Serializable data class Backup(
    val version: Int = 1,
    val exportedAt: Long,
    val clients: List<Client>,
    val services: List<Service>,
    val appointments: List<Appointment>,
    val settings: Settings
)
object BackupCodec {
    const val MAX_BYTES = 20 * 1024 * 1024
    val json = Json { encodeDefaults = true }
    fun read(stream: InputStream): Backup {
        val bytes = stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) throw RuleViolation(Problem.BACKUP_SIZE)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.size > MAX_BYTES) throw RuleViolation(Problem.BACKUP_SIZE)
        val backup = try {
            val text = bytes.toString(Charsets.UTF_8)
            if ("version" !in json.parseToJsonElement(text).jsonObject) throw RuleViolation(Problem.BACKUP)
            json.decodeFromString<Backup>(text)
        }
            catch (_: Exception) { throw RuleViolation(Problem.BACKUP) }
        validate(backup)
        return backup
    }
    fun validate(backup: Backup) {
        if (backup.version != 1) throw RuleViolation(Problem.BACKUP_VERSION)
        Rules.settings(backup.settings)
        fun ids(values: List<String>) {
            if (values.distinct().size != values.size || values.any { runCatching { UUID.fromString(it) }.isFailure }) throw RuleViolation(Problem.BACKUP)
        }
        ids(backup.clients.map { it.id }); ids(backup.services.map { it.id }); ids(backup.appointments.map { it.id })
        fun timestamps(created: Long, updated: Long) { if (created < 0 || updated < created) throw RuleViolation(Problem.BACKUP) }
        backup.clients.forEach {
            Rules.name(it.name); timestamps(it.createdAt, it.updatedAt)
            if (it.normalizedPhone != Rules.normalizePhone(it.phone)) throw RuleViolation(Problem.BACKUP)
        }
        backup.services.forEach {
            Rules.name(it.name); Rules.duration(it.defaultDurationMinutes); Rules.price(it.defaultPriceMinor); Rules.currency(it.currencyCode)
            timestamps(it.createdAt, it.updatedAt)
        }
        val clients = backup.clients.map { it.id }.toSet()
        val services = backup.services.map { it.id }.toSet()
        val active = backup.appointments.filter { it.status == Status.PLANNED }.sortedBy { it.startAt }
        backup.appointments.forEach {
            if (it.clientId !in clients || it.serviceId !in services) throw RuleViolation(Problem.BACKUP)
            Rules.duration(it.durationMinutes); Rules.price(it.priceMinor); Rules.currency(it.currencyCode)
            Rules.name(it.serviceNameSnapshot); Rules.name(it.clientNameSnapshot); timestamps(it.createdAt, it.updatedAt)
            if (it.startAt !in 0..253402214400000L) throw RuleViolation(Problem.BACKUP)
        }
        active.zipWithNext().forEach { (a, b) -> if (a.interval().end > Instant.ofEpochMilli(b.startAt)) throw RuleViolation(Problem.CONFLICT) }
    }
}
