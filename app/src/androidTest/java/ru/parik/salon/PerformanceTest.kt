package ru.parik.salon

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.parik.salon.data.*
import ru.parik.salon.domain.Status
import java.util.UUID

/** Synthetic smoke measurement; does not stand in for physical-device profiling. */
@RunWith(AndroidJUnit4::class)
class PerformanceTest {
    @Test fun fiveThousandClientsAndTwentyThousandAppointments() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "performance-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, SalonDatabase::class.java, name).build()
        try {
            val clients = (1..5000).map { Client(UUID.randomUUID().toString(), "Клиент %05d".format(it), phone = "+7 $it", normalizedPhone = "+7$it", createdAt = 1, updatedAt = 1) }
            val service = Service(UUID.randomUUID().toString(), "Стрижка", 30, currencyCode = "RUB", createdAt = 1, updatedAt = 1)
            db.withTransaction {
                clients.forEach { db.dao().put(it) }; db.dao().put(service)
                repeat(20000) { index ->
                    db.dao().put(Appointment(UUID.randomUUID().toString(), clients[index % clients.size].id, service.id, 1_700_000_000_000L + index * 1_800_000L, 30, currencyCode = "RUB", status = Status.COMPLETED, serviceNameSnapshot = service.name, clientNameSnapshot = clients[index % clients.size].name, createdAt = 1, updatedAt = 1))
                }
            }
            val dayStart = SystemClock.elapsedRealtime()
            val day = db.dao().day(1_700_000_000_000L, 1_700_086_400_000L).first()
            val dayMs = SystemClock.elapsedRealtime() - dayStart
            val searchStart = SystemClock.elapsedRealtime()
            val found = db.dao().allClients().filter { it.name.contains("05000", true) }
            val searchMs = SystemClock.elapsedRealtime() - searchStart
            assertEquals(48, day.size); assertEquals(1, found.size)
            android.util.Log.i("SalonPerformance", "Synthetic dataset 5000/20000: day query=${dayMs}ms; client load+filter=${searchMs}ms")
            Unit
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
