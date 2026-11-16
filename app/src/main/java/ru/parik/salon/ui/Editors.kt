package ru.parik.salon.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.parik.salon.R
import ru.parik.salon.data.*
import ru.parik.salon.domain.*
import ru.parik.salon.platform.ContactReader
import java.time.*
import java.util.UUID

@Composable fun ClientEditor(vm: SalonViewModel, state: SalonState, id: String, busy: Boolean, openContacts: Boolean = false, back: () -> Unit) {
    val original = state.clients.find { it.id == id }
    val clientId = rememberSaveable { if (id == "new") UUID.randomUUID().toString() else id }
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(original?.phone.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(original?.notes.orEmpty()) }
    var contactPreview by rememberSaveable { mutableStateOf(false) }
    var numbers by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var choosePhone by rememberSaveable { mutableStateOf(false) }
    var contactBusy by remember { mutableStateOf(false) }
    var initialContactRequested by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri != null) scope.launch {
            contactBusy = true
            try {
                val contact = withContext(Dispatchers.IO) { ContactReader.readSafely(context.contentResolver, uri) }
                if (contact.name.isNotBlank()) name = contact.name
                numbers = contact.phones.toList()
                phone = contact.phones.firstOrNull().orEmpty()
                choosePhone = contact.phones.size > 1
                contactPreview = true
            } catch (_: Exception) { vm.showError(Problem.IO) }
            finally { contactBusy = false }
        }
    }
    LaunchedEffect(openContacts) {
        if (openContacts && original == null && !initialContactRequested) {
            initialContactRequested = true
            runCatching { picker.launch(null) }.onFailure { vm.showError(Problem.IO) }
        }
    }
    val dirty = name != original?.name.orEmpty() || phone != original?.phone.orEmpty() || notes != original?.notes.orEmpty()
    Form(stringResource(if (original == null) R.string.new_client else R.string.edit_client), busy || contactBusy, dirty, back, {
        val now = System.currentTimeMillis()
        vm.saveClient(Client(clientId, name.trim(), phone.trim().ifBlank { null }, Rules.normalizePhone(phone), notes.trim().ifBlank { null }, original?.archived ?: false, original?.createdAt ?: now, now))
    }) {
        OutlinedButton(onClick = { runCatching { picker.launch(null) }.onFailure { vm.showError(Problem.IO) } }, enabled = !busy && !contactBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.from_contacts)) }
        if (contactPreview) { Text(stringResource(R.string.contact_preview)); if (numbers.isEmpty()) Text(stringResource(R.string.contact_no_phone)) }
        TextField(name, { name = it }, R.string.name)
        TextField(phone, { phone = it }, R.string.phone, KeyboardType.Phone)
        if (numbers.size > 1) TextButton(onClick = { choosePhone = true }) { Text(stringResource(R.string.choose_phone)) }
        TextField(notes, { notes = it }, R.string.notes, multiline = true)
    }
    if (choosePhone) AlertDialog(onDismissRequest = { choosePhone = false }, title = { Text(stringResource(R.string.choose_phone)) }, text = { Column { numbers.forEach { number -> TextButton(onClick = { phone = number; choosePhone = false }, modifier = Modifier.fillMaxWidth()) { Text(number) } } } }, confirmButton = { TextButton(onClick = { choosePhone = false }) { Text(stringResource(R.string.close)) } })
}
@Composable fun ServiceEditor(vm: SalonViewModel, state: SalonState, id: String, busy: Boolean, back: () -> Unit) {
    val original = state.services.find { it.id == id }
    val serviceId = rememberSaveable { if (id == "new") UUID.randomUUID().toString() else id }
    var name by rememberSaveable { mutableStateOf(original?.name.orEmpty()) }
    var duration by rememberSaveable { mutableStateOf(original?.defaultDurationMinutes?.toString().orEmpty()) }
    var currency by rememberSaveable { mutableStateOf(original?.currencyCode ?: state.settings.currency) }
    var price by rememberSaveable { mutableStateOf(Rules.priceInput(original?.defaultPriceMinor, currency)) }
    var notes by rememberSaveable { mutableStateOf(original?.notes.orEmpty()) }
    var archive by rememberSaveable { mutableStateOf(false) }
    val dirty = name != original?.name.orEmpty() || duration != original?.defaultDurationMinutes?.toString().orEmpty() || price != Rules.priceInput(original?.defaultPriceMinor, original?.currencyCode ?: state.settings.currency) || notes != original?.notes.orEmpty() || currency != (original?.currencyCode ?: state.settings.currency)
    Form(stringResource(if (original == null) R.string.new_service else R.string.edit_service), busy, dirty, back, {
        try {
            val now = System.currentTimeMillis()
            vm.saveService(Service(serviceId, name.trim(), duration.toIntOrNull() ?: throw RuleViolation(Problem.DURATION), Rules.parsePrice(price, currency), currency, notes.ifBlank { null }, original?.archived ?: false, original?.createdAt ?: now, now))
        } catch (failure: RuleViolation) { vm.error.value = failure }
    }) {
        TextField(name, { name = it }, R.string.service_name)
        TextField(duration, { duration = it }, R.string.duration, KeyboardType.Number)
        TextField(currency, { currency = it.uppercase(java.util.Locale.ROOT) }, R.string.currency)
        PriceField(price, { price = it }, currency)
        TextField(notes, { notes = it }, R.string.notes, multiline = true)
        if (original != null && !original.archived) TextButton(onClick = { archive = true }, enabled = !busy) { Text(stringResource(R.string.archive)) }
    }
    if (archive) Confirmation(stringResource(R.string.archive), stringResource(R.string.archive_service_confirm), { archive = false }, { archive = false; vm.archiveService(id) }, R.string.archive)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable fun AppointmentEditor(vm: SalonViewModel, state: SalonState, id: String, initialClient: String?, busy: Boolean, back: () -> Unit, addClient: () -> Unit, addService: () -> Unit) {
    val original = state.appointments.find { it.id == id }
    val appointmentId = rememberSaveable { if (id == "new") UUID.randomUUID().toString() else id }
    val day by vm.selectedDay.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val originalStart = original?.let { Instant.ofEpochMilli(it.startAt).atZone(zone) }
    var clientId by rememberSaveable { mutableStateOf(original?.clientId ?: initialClient.orEmpty()) }
    var serviceId by rememberSaveable { mutableStateOf(original?.serviceId.orEmpty()) }
    var date by rememberSaveable { mutableStateOf((originalStart?.toLocalDate() ?: LocalDate.parse(day)).format(Rules.dateFormat)) }
    var time by rememberSaveable { mutableStateOf(originalStart?.format(Rules.timeFormat) ?: "09:00") }
    var duration by rememberSaveable { mutableStateOf(original?.durationMinutes?.toString().orEmpty()) }
    var currency by rememberSaveable { mutableStateOf(original?.currencyCode ?: state.settings.currency) }
    var price by rememberSaveable { mutableStateOf(Rules.priceInput(original?.priceMinor, currency)) }
    var notes by rememberSaveable { mutableStateOf(original?.notes.orEmpty()) }
    var status by rememberSaveable { mutableStateOf(original?.status?.name ?: Status.PLANNED.name) }
    var touched by rememberSaveable { mutableStateOf(false) }
    var allSlots by rememberSaveable { mutableStateOf(false) }
    val createdClient by vm.createdClient.collectAsStateWithLifecycle()
    val createdService by vm.createdService.collectAsStateWithLifecycle()
    fun selectService(value: String) {
        serviceId = value
        state.services.find { it.id == value }?.let { duration = it.defaultDurationMinutes.toString(); currency = it.currencyCode; price = Rules.priceInput(it.defaultPriceMinor, it.currencyCode) }
        touched = true
    }
    LaunchedEffect(createdClient) { if (createdClient.isNotBlank()) { clientId = createdClient; touched = true; vm.clearCreatedClient() } }
    LaunchedEffect(createdService) { if (createdService.isNotBlank()) { selectService(createdService); vm.clearCreatedService() } }
    val slots = remember(date, duration, state.settings, state.appointments, id, zone) {
        runCatching { Rules.slots(LocalDate.parse(date, Rules.dateFormat), duration.toInt(), state.settings, state.appointments.filter { it.id != appointmentId }.map { it.interval() }, zone) }.getOrDefault(emptyList())
    }
    Form(stringResource(if (original == null) R.string.new_appointment else R.string.edit_appointment), busy, touched, back, {
        try {
            if (clientId.isBlank()) throw RuleViolation(Problem.CLIENT)
            if (serviceId.isBlank()) throw RuleViolation(Problem.SERVICE)
            val now = System.currentTimeMillis()
            vm.saveAppointment(Appointment(appointmentId, clientId, serviceId, Rules.localStart(date, time, zone).toEpochMilli(), duration.toIntOrNull() ?: throw RuleViolation(Problem.DURATION), Rules.parsePrice(price, currency), currency, Status.valueOf(status), notes.ifBlank { null }, state.services.find { it.id == serviceId }?.name.orEmpty(), state.clients.find { it.id == clientId }?.name.orEmpty(), original?.createdAt ?: now, now))
        } catch (failure: RuleViolation) { vm.error.value = failure }
    }) {
        Choice(stringResource(R.string.client), state.clients.find { it.id == clientId }?.name.orEmpty(), state.clients.filter { !it.archived || it.id == original?.clientId }.map { it.id to (it.name + if (it.phone.isNullOrBlank()) "" else " · ${it.phone}") }) { clientId = it; touched = true }
        TextButton(onClick = { vm.clearCreatedClient(); addClient() }) { Text(stringResource(R.string.add_client)) }
        Choice(stringResource(R.string.service), state.services.find { it.id == serviceId }?.name.orEmpty(), state.services.filter { !it.archived || it.id == original?.serviceId }.map { it.id to it.name }) { selectService(it) }
        if (state.services.none { !it.archived }) TextButton(onClick = { vm.clearCreatedService(); addService() }) { Text(stringResource(R.string.add_service)) }
        TextField(date, { date = it; touched = true }, R.string.date)
        TextField(time, { time = it; touched = true }, R.string.time)
        TextField(duration, { duration = it; touched = true }, R.string.duration, KeyboardType.Number)
        val end = runCatching { Rules.localStart(date, time, zone).plusSeconds((duration.toIntOrNull() ?: 0) * 60L).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm")) }.getOrNull()
        if (end != null) Text(stringResource(R.string.end_time, end))
        Text(stringResource(R.string.free_slots), style = MaterialTheme.typography.titleMedium)
        if (slots.isEmpty()) Text(stringResource(if (serviceId.isBlank()) R.string.slots_choose_service else R.string.slots_empty))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (allSlots) slots else slots.take(8)).forEach { slot ->
                val label = slot.atZone(zone).format(Rules.timeFormat)
                FilterChip(selected = label == time, onClick = { time = label; touched = true }, label = { Text(label) })
            }
        }
        if (slots.size > 8) TextButton(onClick = { allSlots = !allSlots }) { Text(stringResource(if (allSlots) R.string.less_slots else R.string.more_slots)) }
        TextField(currency, { currency = it.uppercase(java.util.Locale.ROOT); touched = true }, R.string.currency)
        PriceField(price, { price = it; touched = true }, currency)
        Choice(stringResource(R.string.status), stringResource(statusText(Status.valueOf(status))), Status.entries.map { it.name to stringResource(statusText(it)) }) { status = it; touched = true }
        TextField(notes, { notes = it; touched = true }, R.string.notes, multiline = true)
    }
}
