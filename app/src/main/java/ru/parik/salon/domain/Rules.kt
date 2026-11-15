package ru.parik.salon.domain

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable enum class Status { PLANNED, COMPLETED, CANCELLED, NO_SHOW }
@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable data class Settings(
    val days: Set<Int> = setOf(1, 2, 3, 4, 5),
    val startMinute: Int = 540,
    val endMinute: Int = 1080,
    val stepMinutes: Int = 15,
    val reminderMinutes: Int = 0,
    val currency: String = "RUB",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val starterOffered: Boolean = false
)

enum class Problem {
    NAME, DURATION, PRICE, CURRENCY, CLIENT, SERVICE, DATE, TIME, HOURS,
    CONFLICT, OUTSIDE_HOURS, DUPLICATE, BACKUP, BACKUP_SIZE, BACKUP_VERSION, FUTURE_COMPLETION, NOT_FOUND, IO
}
class RuleViolation(val problem: Problem, val conflicts: List<String> = emptyList()) : IllegalArgumentException(problem.name)

data class BusyInterval(val id: String, val start: Instant, val duration: Int, val status: Status) {
    val end: Instant get() = start.plusSeconds(duration * 60L)
}
object Rules {
    fun name(value: String) { if (value.isBlank() || value.length > 200) throw RuleViolation(Problem.NAME) }
    fun duration(value: Int) { if (value !in 1..10080) throw RuleViolation(Problem.DURATION) }
    fun price(value: Long?) { if (value != null && value < 0) throw RuleViolation(Problem.PRICE) }
    fun currency(value: String) {
        if (value.length != 3 || runCatching { Currency.getInstance(value) }.isFailure) throw RuleViolation(Problem.CURRENCY)
    }
    fun settings(value: Settings) {
        if (value.days.any { it !in 1..7 } || value.startMinute !in 0..1439 ||
            value.endMinute !in 1..1440 || value.startMinute >= value.endMinute ||
            value.stepMinutes !in setOf(15, 30, 60) || value.reminderMinutes !in setOf(0, 15, 30, 60)) throw RuleViolation(Problem.HOURS)
        currency(value.currency)
    }
    // No country is inferred. Local and international spellings intentionally differ.
    fun normalizePhone(phone: String?): String? {
        val value = phone?.trim().orEmpty()
        val digits = value.filter { it in '0'..'9' }
        return digits.takeIf { it.isNotEmpty() }?.let { if (value.startsWith("+")) "+$it" else it }
    }
    fun conflicts(candidate: BusyInterval, existing: List<BusyInterval>): List<String> {
        if (candidate.status != Status.PLANNED) return emptyList()
        return existing.filter { it.id != candidate.id && it.status == Status.PLANNED &&
            it.start < candidate.end && candidate.start < it.end }.map { it.id }
    }
    fun insideHours(start: Instant, duration: Int, settings: Settings, zone: ZoneId): Boolean {
        val local = start.atZone(zone)
        if (local.dayOfWeek.value !in settings.days) return false
        val opening = local.toLocalDate().atStartOfDay().plusMinutes(settings.startMinute.toLong()).atZone(zone).toInstant()
        val closing = local.toLocalDate().atStartOfDay().plusMinutes(settings.endMinute.toLong()).atZone(zone).toInstant()
        return start >= opening && start.plusSeconds(duration * 60L) <= closing
    }
    fun slots(date: LocalDate, duration: Int, settings: Settings, busy: List<BusyInterval>, zone: ZoneId): List<Instant> {
        duration(duration)
        settings(settings)
        if (date.dayOfWeek.value !in settings.days) return emptyList()
        val opening = date.atStartOfDay().plusMinutes(settings.startMinute.toLong()).atZone(zone).toInstant()
        val closing = date.atStartOfDay().plusMinutes(settings.endMinute.toLong()).atZone(zone).toInstant()
        return generateSequence(opening) { it.plusSeconds(settings.stepMinutes * 60L) }
            .takeWhile { it.plusSeconds(duration * 60L) <= closing }
            .filter { it.atZone(zone).toLocalDateTime().atZone(zone).toInstant() == it }
            .filter { conflicts(BusyInterval("", it, duration, Status.PLANNED), busy).isEmpty() }.toList()
    }
    fun transition(status: Status, start: Instant, now: Instant) {
        if (status in setOf(Status.COMPLETED, Status.NO_SHOW) && start > now) throw RuleViolation(Problem.FUTURE_COMPLETION)
    }
    fun parsePrice(value: String, currency: String): Long? {
        if (value.isBlank()) return null
        currency(currency)
        return try {
            BigDecimal(value.trim().replace(',', '.')).movePointRight(Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0))
                .longValueExact().also { price(it) }
        } catch (_: ArithmeticException) { throw RuleViolation(Problem.PRICE) }
        catch (_: NumberFormatException) { throw RuleViolation(Problem.PRICE) }
    }
    fun priceInput(value: Long?, currency: String): String = value?.let {
        BigDecimal.valueOf(it, Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)).toPlainString()
    }.orEmpty()
    fun formatPrice(value: Long?, currency: String): String = value?.let {
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("ru-RU")).apply {
            this.currency = Currency.getInstance(currency)
            minimumFractionDigits = Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)
            maximumFractionDigits = minimumFractionDigits
        }
            .format(BigDecimal.valueOf(it, Currency.getInstance(currency).defaultFractionDigits.coerceAtLeast(0)))
    }.orEmpty()
    val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT)
    fun localStart(date: String, time: String, zone: ZoneId): Instant {
        val day = try { LocalDate.parse(date, dateFormat) } catch (_: Exception) { throw RuleViolation(Problem.DATE) }
        val clock = try { LocalTime.parse(time, timeFormat) } catch (_: Exception) { throw RuleViolation(Problem.TIME) }
        val local = day.atTime(clock)
        // Reject a DST gap instead of silently moving an appointment; first offset for a repeated hour.
        if (zone.rules.getValidOffsets(local).isEmpty()) throw RuleViolation(Problem.TIME)
        return local.atZone(zone).toInstant()
    }
}
