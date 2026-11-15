package ru.parik.salon

import org.junit.Assert.*
import org.junit.Test
import ru.parik.salon.domain.*
import java.time.*

class RulesTest {
    private val zone = ZoneId.of("UTC")
    private val date = LocalDate.of(2026, 11, 16)
    private fun instant(time: String) = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun busy(id: String, time: String, duration: Int, status: Status = Status.PLANNED) = BusyInterval(id, instant(time), duration, status)
    private inline fun invalid(problem: Problem, block: () -> Unit) {
        try { block(); fail("Expected $problem") } catch (failure: RuleViolation) { assertEquals(problem, failure.problem) }
    }
    @Test fun adjacentIntervalsAreAllowed() { assertTrue(Rules.conflicts(busy("new", "11:00", 60), listOf(busy("old", "10:00", 60))).isEmpty()) }
    @Test fun overlapsAndNestingAreBlocked() {
        val old = listOf(busy("old", "10:00", 60))
        assertEquals(listOf("old"), Rules.conflicts(busy("new", "10:30", 60), old))
        assertEquals(listOf("old"), Rules.conflicts(busy("new", "10:15", 15), old))
        assertEquals(listOf("old"), Rules.conflicts(busy("new", "09:00", 180), old))
    }
    @Test fun editingSelfDoesNotConflict() { val value = busy("same", "10:00", 60); assertTrue(Rules.conflicts(value, listOf(value)).isEmpty()) }
    @Test fun onlyPlannedBlocksTimeIncludingInPast() {
        Status.entries.filter { it != Status.PLANNED }.forEach { status ->
            assertTrue(Rules.conflicts(busy("new", "10:00", 60), listOf(busy("old", "10:00", 60, status))).isEmpty())
            assertTrue(Rules.conflicts(busy("new", "10:00", 60, status), listOf(busy("old", "10:00", 60))).isEmpty())
        }
        assertEquals(1, Rules.conflicts(busy("new", "10:00", 60), listOf(busy("old", "10:00", 60))).size)
    }
    @Test fun crossingMidnightIsDetected() {
        val old = BusyInterval("old", instant("23:30").minusSeconds(86400), 90, Status.PLANNED)
        assertEquals(listOf("old"), Rules.conflicts(busy("new", "00:15", 60), listOf(old)))
    }
    @Test fun emptyDaySlotsFitWorkingHours() {
        val slots = Rules.slots(date, 60, Settings(), emptyList(), zone)
        assertEquals(33, slots.size)
        assertEquals(instant("09:00"), slots.first()); assertEquals(instant("17:00"), slots.last())
    }
    @Test fun filledDayHasNoSlots() { assertTrue(Rules.slots(date, 30, Settings(), listOf(busy("all", "09:00", 540)), zone).isEmpty()) }
    @Test fun slotsRespectDurationAndNeighbours() {
        val slots = Rules.slots(date, 120, Settings(), listOf(busy("old", "10:00", 60)), zone)
        assertFalse(instant("09:00") in slots); assertTrue(instant("11:00") in slots); assertFalse(instant("16:15") in slots)
    }
    @Test fun closedDayAndLongServiceHaveNoSlots() {
        assertTrue(Rules.slots(date.plusDays(6), 30, Settings(), emptyList(), zone).isEmpty())
        assertTrue(Rules.slots(date, 600, Settings(), emptyList(), zone).isEmpty())
    }
    @Test fun outsideHoursRequiresConfirmation() {
        assertTrue(Rules.insideHours(instant("09:00"), 540, Settings(), zone))
        assertFalse(Rules.insideHours(instant("08:45"), 30, Settings(), zone))
        assertFalse(Rules.insideHours(instant("17:45"), 30, Settings(), zone))
        assertFalse(Rules.insideHours(instant("23:30"), 120, Settings(), zone))
    }
    @Test fun phoneNormalizationDoesNotInferCountry() {
        assertEquals("+79991234567", Rules.normalizePhone("+7 (999) 123-45-67"))
        assertEquals("89991234567", Rules.normalizePhone("8 999 123 45 67"))
        assertNotEquals(Rules.normalizePhone("+44 123"), Rules.normalizePhone("044 123"))
        assertNull(Rules.normalizePhone("")); assertNull(Rules.normalizePhone(null))
    }
    @Test fun validatesNamesDurationPricesAndSettings() {
        invalid(Problem.NAME) { Rules.name("  ") }; invalid(Problem.NAME) { Rules.name("x".repeat(201)) }
        invalid(Problem.DURATION) { Rules.duration(0) }; invalid(Problem.DURATION) { Rules.duration(-1) }
        invalid(Problem.PRICE) { Rules.price(-1) }; invalid(Problem.CURRENCY) { Rules.currency("XXXx") }
        invalid(Problem.HOURS) { Rules.settings(Settings(startMinute = 600, endMinute = 500)) }
        Rules.price(null); Rules.price(0); Rules.duration(30); Rules.name("Анна")
    }
    @Test fun moneyUsesExactMinorUnits() {
        assertEquals(123456L, Rules.parsePrice("1234,56", "RUB"))
        assertEquals(1234L, Rules.parsePrice("1234", "JPY"))
        assertEquals("1234.56", Rules.priceInput(123456, "RUB"))
        assertNull(Rules.parsePrice("", "RUB"))
        invalid(Problem.PRICE) { Rules.parsePrice("1.001", "RUB") }
        invalid(Problem.PRICE) { Rules.parsePrice("-1", "RUB") }
        invalid(Problem.PRICE) { Rules.parsePrice("999999999999999999999999999", "RUB") }
        assertTrue(Rules.formatPrice(123456, "RUB").contains("234,56"))
        assertFalse(Rules.formatPrice(1234, "JPY").contains(","))
    }
    @Test fun datesAreStrictAndUseChosenZone() {
        invalid(Problem.DATE) { Rules.localStart("31.02.2026", "10:00", zone) }
        invalid(Problem.TIME) { Rules.localStart("16.11.2026", "25:00", zone) }
        assertEquals(instant("10:00"), Rules.localStart("16.11.2026", "10:00", zone))
        assertEquals("16.11.2026", date.format(Rules.dateFormat))
    }
    @Test fun dstGapIsRejectedAndOverlapUsesEarlierOffset() {
        val berlin = ZoneId.of("Europe/Berlin")
        invalid(Problem.TIME) { Rules.localStart("29.03.2026", "02:30", berlin) }
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), Rules.localStart("25.10.2026", "02:30", berlin))
    }
    @Test fun futureCompletionAndNoShowAreRejected() {
        listOf(Status.COMPLETED, Status.NO_SHOW).forEach { status -> invalid(Problem.FUTURE_COMPLETION) { Rules.transition(status, instant("11:00"), instant("10:00")) } }
        Status.entries.forEach { Rules.transition(it, instant("09:00"), instant("10:00")) }
        Rules.transition(Status.CANCELLED, instant("11:00"), instant("10:00"))
    }
}
