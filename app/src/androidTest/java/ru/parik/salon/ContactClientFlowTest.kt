package ru.parik.salon

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import ru.parik.salon.data.*
import ru.parik.salon.domain.Rules
import ru.parik.salon.domain.Settings

@RunWith(AndroidJUnit4::class)
class ContactClientFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>
    private val app get() = ApplicationProvider.getApplicationContext<SalonApplication>()

    @Before fun setup() {
        runBlocking {
            app.repository.restore(Backup(exportedAt = 1, clients = emptyList(), services = emptyList(), appointments = emptyList(), settings = Settings(starterOffered = true)))
            app.repository.saveService(Service(UUID.randomUUID().toString(), "Женская стрижка", 60, currencyCode = "RUB", createdAt = 1, updatedAt = 1))
        }
        Intents.init()
        intending(hasAction(Intent.ACTION_PICK)).respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitText("На этот день записей нет")
    }

    @After fun close() {
        try { if (::activity.isInitialized) activity.close() } finally { Intents.release() }
    }

    private fun waitText(text: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun contactsOptionLaunchesPickerOnceAndCancellationKeepsManualEntry() {
        compose.onNodeWithText("Клиенты").performClick()
        compose.onNodeWithText("Добавить клиента").performClick()
        compose.onNodeWithText("Ввести вручную").assertExists()
        compose.onNodeWithText("Из контактов").performClick()
        waitText("Новый клиент")
        intended(hasAction(Intent.ACTION_PICK), times(1))
        assertTrue(runBlocking { app.repository.dao.allClients().isEmpty() })

        compose.onNodeWithText("Имя клиента *").performTextInput("Елена")
        activity.recreate()
        compose.onNodeWithText("Имя клиента *").assertTextContains("Елена")
        intended(hasAction(Intent.ACTION_PICK), times(1))
        compose.onNodeWithText("Сохранить").performClick()
        waitText("Добавить клиента")
        compose.onNodeWithText("Елена").assertExists()
        assertEquals(1, runBlocking { app.repository.dao.allClients().size })
    }

    @Test fun addingClientThroughContactsReturnsToCompleteAppointmentDraft() {
        val date = generateSequence(LocalDate.now().plusDays(1)) { it.plusDays(1) }
            .first { it.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
        compose.onNodeWithText("+ Запись").performClick()
        compose.onAllNodesWithText("Не выбрано")[1].performClick()
        compose.onNodeWithText("Женская стрижка").performClick()
        compose.onNodeWithText("Дата (дд.мм.гггг) *").performScrollTo().performTextReplacement(date.format(Rules.dateFormat))
        compose.onNodeWithText("Начало (чч:мм) *").performScrollTo().performTextReplacement("13:45")
        compose.onNodeWithText("Стоимость (RUB)").performScrollTo().performTextInput("1250,50")
        compose.onNodeWithText("Добавить клиента").performScrollTo().performClick()
        compose.onNodeWithText("Из контактов").performClick()
        waitText("Новый клиент")
        intended(hasAction(Intent.ACTION_PICK), times(1))
        compose.onNodeWithText("Имя клиента *").performTextInput("Ольга")
        compose.onNodeWithText("Телефон").performTextInput("+7 999 333 22 11")
        compose.onNodeWithText("Сохранить").performClick()
        waitText("Новая запись")

        compose.onNodeWithText("Ольга").assertExists()
        compose.onNodeWithText("Женская стрижка").assertExists()
        compose.onNodeWithText("Дата (дд.мм.гггг) *").performScrollTo().assertTextContains(date.format(Rules.dateFormat))
        compose.onNodeWithText("Начало (чч:мм) *").performScrollTo().assertTextContains("13:45")
        compose.onNodeWithText("Длительность, мин *").performScrollTo().assertTextContains("60")
        compose.onNodeWithText("Стоимость (RUB)").performScrollTo().assertTextContains("1250,50")
        compose.onNodeWithText("Сохранить").performClick()
        waitText("На этот день записей нет")
        val booking = runBlocking { app.repository.dao.allAppointments().single() }
        val client = runBlocking { app.repository.dao.client(booking.clientId)!! }
        val start = Instant.ofEpochMilli(booking.startAt).atZone(ZoneId.systemDefault())
        assertEquals("Ольга", client.name)
        assertEquals("+7 999 333 22 11", client.phone)
        assertEquals(date, start.toLocalDate())
        assertEquals(LocalTime.of(13, 45), start.toLocalTime())
        assertEquals(60, booking.durationMinutes)
        assertEquals(125050L, booking.priceMinor)
    }
}
