package ru.parik.salon

import android.content.pm.ActivityInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import ru.parik.salon.data.*
import ru.parik.salon.domain.Settings
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SalonUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityScenario<MainActivity>
    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<SalonApplication>()
        runBlocking {
            app.repository.restore(Backup(exportedAt = 1, clients = emptyList(), services = emptyList(), appointments = emptyList(), settings = Settings(starterOffered = true)))
            app.repository.saveService(Service(UUID.randomUUID().toString(), "Женская стрижка", 60, currencyCode = "RUB", createdAt = 1, updatedAt = 1))
        }
        activity = ActivityScenario.launch(MainActivity::class.java)
        waitText("На этот день записей нет")
    }
    @After fun close() { activity.close() }
    private fun waitText(text: String) {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun client() {
        compose.onNodeWithText("Клиенты").performClick()
        compose.onNodeWithText("Добавить клиента").performClick()
        compose.onNodeWithText("Ввести вручную").performClick()
        compose.onNodeWithText("Имя клиента *").performTextInput("Анна")
        compose.onNodeWithText("Телефон").performTextInput("+7 999 123 45 67")
        compose.onNodeWithText("Сохранить").performClick()
        waitText("Добавить клиента")
        waitText("Анна")
    }
    @Test fun manualClientCanBeFoundByNameAndPhone() {
        client()
        compose.onNodeWithText("Поиск по имени или телефону").performTextInput("999123")
        compose.onNodeWithText("Анна").assertIsDisplayed()
        compose.onNodeWithText("Поиск по имени или телефону").performTextReplacement("Нет такого")
        compose.onNodeWithText("Анна").assertDoesNotExist()
    }
    @Test fun appointmentPersistsShowsTimeAndCanBeCancelled() {
        client()
        compose.onNodeWithText("Расписание").performClick()
        compose.onNodeWithText("+ Запись").performClick()
        compose.onAllNodesWithText("Не выбрано")[0].performClick()
        compose.onNodeWithText("Анна · +7 999 123 45 67").performClick()
        compose.onNodeWithText("Не выбрано").performClick()
        compose.onNodeWithText("Женская стрижка").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        // Today's date is a Sunday in this fixture: explicit outside-hours approval.
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Сохранить вне графика").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("09:00–10:00").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Сохранить вне графика").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Сохранить вне графика").performClick()
        waitText("09:00–10:00")
        activity.recreate()
        waitText("09:00–10:00")
        compose.onNodeWithText("Анна").performClick()
        compose.onNodeWithText("Отменить запись").performScrollTo().performClick()
        waitText("Отменена")
        compose.onNodeWithText("Отменена").assertIsDisplayed()
    }
    @Test fun draftSurvivesActivityRecreation() {
        compose.onNodeWithText("Клиенты").performClick()
        compose.onNodeWithText("Добавить клиента").performClick()
        compose.onNodeWithText("Ввести вручную").performClick()
        compose.onNodeWithText("Имя клиента *").performTextInput("Мария")
        compose.onNodeWithText("Телефон").performTextInput("123456")
        activity.recreate()
        compose.onNodeWithText("Имя клиента *").assertTextContains("Мария")
        compose.onNodeWithText("Телефон").assertTextContains("123456")
    }
    @Test fun dayArrowsAndTodayWork() {
        val today = java.time.LocalDate.now().format(ru.parik.salon.domain.Rules.dateFormat)
        compose.onNodeWithContentDescription("Следующий день").performClick()
        compose.onNodeWithText(java.time.LocalDate.now().plusDays(1).format(ru.parik.salon.domain.Rules.dateFormat)).assertExists()
        compose.onNodeWithText("Сегодня").performClick()
        compose.onNodeWithText(today).assertExists()
    }
    private fun seedAppointment() {
        val app = ApplicationProvider.getApplicationContext<SalonApplication>()
        runBlocking {
            val now = System.currentTimeMillis()
            val client = Client(UUID.randomUUID().toString(), "Анна", createdAt = now, updatedAt = now)
            app.repository.saveClient(client)
            val service = app.repository.dao.allServices().single()
            val start = java.time.LocalDate.now().atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            app.repository.saveAppointment(Appointment(UUID.randomUUID().toString(), client.id, service.id, start, 60, currencyCode = "RUB", serviceNameSnapshot = service.name, clientNameSnapshot = client.name, createdAt = now, updatedAt = now), allowOutside = true)
        }
        waitText("10:00–11:00")
    }
    @Test fun conflictingAppointmentShowsBlockingWarning() {
        seedAppointment()
        compose.onNodeWithText("+ Запись").performClick()
        compose.onAllNodesWithText("Не выбрано")[0].performClick()
        compose.onNodeWithText("Анна").performClick()
        compose.onNodeWithText("Не выбрано").performClick()
        compose.onNodeWithText("Женская стрижка").performClick()
        compose.onNodeWithText("Начало (чч:мм) *").performScrollTo().performTextReplacement("10:30")
        compose.onNodeWithText("Сохранить").performClick()
        waitText("Время занято")
        compose.onNodeWithText("Время занято").assertIsDisplayed()
        val app = ApplicationProvider.getApplicationContext<SalonApplication>()
        runBlocking { org.junit.Assert.assertEquals(1, app.repository.dao.allAppointments().size) }
    }
    @Test fun movingAppointmentShowsNewTime() {
        seedAppointment()
        compose.onNodeWithText("Анна").performClick()
        compose.onNodeWithText("Перенести / изменить").performScrollTo().performClick()
        compose.onNodeWithText("Начало (чч:мм) *").performScrollTo().performTextReplacement("14:00")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText("Сохранить вне графика").fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithText("14:00–15:00").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText("Сохранить вне графика").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Сохранить вне графика").performClick()
        waitText("14:00–15:00")
        compose.onNodeWithText("10:00–11:00").assertDoesNotExist()
    }

    @Test fun creatingCatalogServiceDoesNotReplaceExistingAppointmentService() {
        seedAppointment()
        compose.onNodeWithText("Услуги").performClick()
        compose.onNodeWithText("Добавить услугу").performClick()
        compose.onNodeWithText("Название услуги *").performTextInput("Другое название")
        compose.onNodeWithText("Длительность, мин *").performTextInput("30")
        compose.onNodeWithText("Сохранить").performClick()
        waitText("Добавить услугу")
        compose.onNodeWithText("Расписание").performClick()
        compose.onNodeWithText("Анна").performClick()
        compose.onNodeWithText("Перенести / изменить").performScrollTo().performClick()
        compose.onNodeWithText("Женская стрижка").assertExists()
        compose.onNodeWithText("Другое название").assertDoesNotExist()
    }

}
