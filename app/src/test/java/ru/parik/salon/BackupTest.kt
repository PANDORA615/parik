package ru.parik.salon

import org.junit.Assert.*
import org.junit.Test
import ru.parik.salon.data.*
import ru.parik.salon.domain.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.UUID

class BackupTest {
    private val client = Client(UUID.randomUUID().toString(), "Анна", "+7 123", "+7123", createdAt = 1, updatedAt = 1)
    private val service = Service(UUID.randomUUID().toString(), "Стрижка", 60, currencyCode = "RUB", createdAt = 1, updatedAt = 1)
    private val appointment = Appointment(UUID.randomUUID().toString(), client.id, service.id, 100000, 60, currencyCode = "RUB", serviceNameSnapshot = service.name, clientNameSnapshot = client.name, createdAt = 1, updatedAt = 1)
    private fun valid() = Backup(exportedAt = 1, clients = listOf(client), services = listOf(service), appointments = listOf(appointment), settings = Settings())
    private fun rejected(backup: Backup) { try { BackupCodec.validate(backup); fail("Expected rejection") } catch (_: RuleViolation) { } }
    @Test fun roundTripPreservesAllDataAndSettings() {
        val value = valid().copy(settings = Settings(currency = "EUR", reminderMinutes = 30, days = setOf(2, 4)))
        val encoded = BackupCodec.json.encodeToString(value)
        assertEquals(value, BackupCodec.read(ByteArrayInputStream(encoded.toByteArray())))
    }
    @Test fun rejectsBadVersionAndBrokenReferencesAndDuplicateIds() {
        rejected(valid().copy(version = 2)); rejected(valid().copy(clients = emptyList()))
        rejected(valid().copy(services = emptyList())); rejected(valid().copy(clients = listOf(client, client)))
        rejected(valid().copy(appointments = listOf(appointment.copy(durationMinutes = 0))))
        rejected(valid().copy(appointments = listOf(appointment, appointment.copy(id = UUID.randomUUID().toString()))))
        rejected(valid().copy(clients = listOf(client.copy(normalizedPhone = "bad"))))
    }
    @Test fun rejectsEmptyMalformedAndTruncatedFiles() {
        listOf("", "{}", "{broken", "[]", BackupCodec.json.encodeToString(valid()).replace("\"version\":1,", "")).forEach { text ->
            try { BackupCodec.read(ByteArrayInputStream(text.toByteArray())); fail("Expected rejection") } catch (error: RuleViolation) { assertEquals(Problem.BACKUP, error.problem) }
        }
    }
    @Test fun tooLargeStreamIsRejectedBeforeDecoding() {
        val stream = object : InputStream() {
            override fun read() = ' '.code
            override fun read(bytes: ByteArray, off: Int, len: Int): Int { bytes.fill(' '.code.toByte(), off, off + len); return len }
        }
        try { BackupCodec.read(stream); fail("Expected rejection") } catch (error: RuleViolation) { assertEquals(Problem.BACKUP_SIZE, error.problem) }
    }
}
