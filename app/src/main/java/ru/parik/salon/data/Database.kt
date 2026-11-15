package ru.parik.salon.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import ru.parik.salon.domain.*

@Serializable @Entity(tableName = "clients", indices = [Index("normalizedPhone"), Index("name")])
data class Client(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String? = null,
    val normalizedPhone: String? = null,
    val notes: String? = null,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)
@Serializable @Entity(tableName = "services")
data class Service(
    @PrimaryKey val id: String,
    val name: String,
    val defaultDurationMinutes: Int,
    val defaultPriceMinor: Long? = null,
    val currencyCode: String,
    val notes: String? = null,
    val archived: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)
@Serializable @Entity(tableName = "appointments", foreignKeys = [
    ForeignKey(entity = Client::class, parentColumns = ["id"], childColumns = ["clientId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = Service::class, parentColumns = ["id"], childColumns = ["serviceId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("startAt"), Index(value = ["clientId", "startAt"]), Index(value = ["status", "startAt"]), Index("serviceId")])
data class Appointment(
    @PrimaryKey val id: String,
    val clientId: String,
    val serviceId: String,
    val startAt: Long,
    val durationMinutes: Int,
    val priceMinor: Long? = null,
    val currencyCode: String,
    val status: Status = Status.PLANNED,
    val notes: String? = null,
    val serviceNameSnapshot: String,
    val clientNameSnapshot: String,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun interval() = BusyInterval(id, java.time.Instant.ofEpochMilli(startAt), durationMinutes, status)
}
@Entity(tableName = "pending_settings")
data class PendingSettings(@PrimaryKey val id: Int = 1, val json: String)

class Converters {
    @TypeConverter fun status(value: String): Status = Status.valueOf(value)
    @TypeConverter fun status(value: Status): String = value.name
}
@Dao interface SalonDao {
    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE") fun clients(): Flow<List<Client>>
    @Query("SELECT * FROM services ORDER BY name COLLATE NOCASE") fun services(): Flow<List<Service>>
    @Query("SELECT * FROM appointments ORDER BY startAt") fun appointments(): Flow<List<Appointment>>
    @Query("SELECT * FROM appointments WHERE startAt < :end AND startAt + durationMinutes * 60000 > :start ORDER BY startAt")
    fun day(start: Long, end: Long): Flow<List<Appointment>>
    @Query("SELECT * FROM clients") suspend fun allClients(): List<Client>
    @Query("SELECT * FROM services") suspend fun allServices(): List<Service>
    @Query("SELECT * FROM appointments ORDER BY startAt") suspend fun allAppointments(): List<Appointment>
    @Query("SELECT * FROM appointments WHERE status = 'PLANNED' AND id != :exclude AND startAt < :end AND startAt + durationMinutes * 60000 > :start")
    suspend fun conflicts(start: Long, end: Long, exclude: String): List<Appointment>
    @Query("SELECT * FROM clients WHERE id = :id") suspend fun client(id: String): Client?
    @Query("SELECT * FROM services WHERE id = :id") suspend fun service(id: String): Service?
    @Query("SELECT * FROM appointments WHERE id = :id") suspend fun appointment(id: String): Appointment?
    @Query("SELECT * FROM clients WHERE normalizedPhone = :phone AND id != :exclude AND archived = 0")
    suspend fun duplicates(phone: String, exclude: String): List<Client>
    @Upsert suspend fun put(client: Client)
    @Upsert suspend fun put(service: Service)
    @Upsert suspend fun put(appointment: Appointment)
    @Upsert suspend fun put(settings: PendingSettings)
    @Query("SELECT * FROM pending_settings WHERE id = 1") suspend fun pending(): PendingSettings?
    @Query("SELECT * FROM pending_settings WHERE id = 1") fun pendingFlow(): Flow<PendingSettings?>
    @Query("DELETE FROM pending_settings WHERE json = :json") suspend fun clearPending(json: String)
    @Query("UPDATE clients SET archived = 1, updatedAt = :now WHERE id = :id") suspend fun archiveClient(id: String, now: Long)
    @Query("UPDATE services SET archived = 1, updatedAt = :now WHERE id = :id") suspend fun archiveService(id: String, now: Long)
    @Query("DELETE FROM appointments WHERE id = :id") suspend fun deleteAppointment(id: String)
    @Query("DELETE FROM appointments") suspend fun clearAppointments()
    @Query("DELETE FROM clients") suspend fun clearClients()
    @Query("DELETE FROM services") suspend fun clearServices()
}
@Database(entities = [Client::class, Service::class, Appointment::class, PendingSettings::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class SalonDatabase : RoomDatabase() { abstract fun dao(): SalonDao }
