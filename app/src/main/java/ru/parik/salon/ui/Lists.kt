package ru.parik.salon.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.parik.salon.R
import ru.parik.salon.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun ScheduleScreen(vm: SalonViewModel, state: SalonState, add: () -> Unit, details: (String) -> Unit) {
    val dayString by vm.selectedDay.collectAsStateWithLifecycle()
    val day = LocalDate.parse(dayString)
    val zone = ZoneId.systemDefault()
    val start = day.atStartOfDay(zone).toInstant()
    val end = day.plusDays(1).atStartOfDay(zone).toInstant()
    val appointments = remember(state.appointments, day, zone) { state.appointments.filter { Instant.ofEpochMilli(it.startAt) < end && it.interval().end > start } }
    val names = remember(state.clients) { state.clients.associate { it.id to it.name } }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.schedule), style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = { vm.day(LocalDate.now()) }) { Text(stringResource(R.string.today)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = { vm.day(day.minusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.previous_day)) }
            TextButton(onClick = {
                DatePickerDialog(context, { _, year, month, date -> vm.day(LocalDate.of(year, month + 1, date)) }, day.year, day.monthValue - 1, day.dayOfMonth).show()
            }) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.format(Rules.dateFormat), style = MaterialTheme.typography.titleLarge)
                Text(day.format(DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("ru"))), style = MaterialTheme.typography.labelLarge)
            } }
            IconButton(onClick = { vm.day(day.plusDays(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.next_day)) }
        }
        Button(onClick = add, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) { Text(stringResource(R.string.add_appointment)) }
        if (appointments.isEmpty()) Text(stringResource(R.string.empty_day), modifier = Modifier.padding(vertical = 32.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(appointments, key = { it.id }) { appointment -> AppointmentCard(appointment, names[appointment.clientId] ?: appointment.clientNameSnapshot, { details(appointment.id) }) }
        }
    }
}
@Composable fun ClientsScreen(state: SalonState, add: () -> Unit, details: (String) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    val clients = remember(state.clients, search) {
        val phone = Rules.normalizePhone(search)
        state.clients.filter { !it.archived && (it.name.contains(search.trim(), true) || (phone != null && it.normalizedPhone?.contains(phone) == true)) }
            .sortedWith(compareBy(java.text.Collator.getInstance(Locale.forLanguageTag("ru"))) { it.name })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.clients), style = MaterialTheme.typography.headlineMedium)
        OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_clients)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = add, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_client)) }
        if (clients.isEmpty()) Text(stringResource(R.string.empty_clients))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(clients, key = { it.id }) { client -> Card(onClick = { details(client.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) { Text(client.name, style = MaterialTheme.typography.titleMedium); if (!client.phone.isNullOrBlank()) Text(client.phone) }
            } }
        }
    }
}
@Composable fun ServicesScreen(state: SalonState, edit: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.services), style = MaterialTheme.typography.headlineMedium)
        Button(onClick = { edit("new") }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_service)) }
        val services = state.services.filter { !it.archived }
        if (services.isEmpty()) Text(stringResource(R.string.empty_services))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(services, key = { it.id }) { service -> Card(onClick = { edit(service.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(service.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.minutes, service.defaultDurationMinutes))
                    Text(if (service.defaultPriceMinor != null) Rules.formatPrice(service.defaultPriceMinor, service.currencyCode) else stringResource(R.string.no_price))
                }
            } }
        }
    }
}
@Composable fun ClientDetails(vm: SalonViewModel, state: SalonState, id: String, busy: Boolean, back: () -> Unit, edit: (String) -> Unit, book: (String) -> Unit, appointment: (String) -> Unit) {
    val client = state.clients.find { it.id == id }
    if (client == null) { Text(stringResource(R.string.not_found)); return }
    val history = state.appointments.filter { it.clientId == id }.sortedByDescending { it.startAt }
    val context = LocalContext.current
    var archive by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            TextButton(onClick = back) { Text(stringResource(R.string.back)) }
            Text(client.name, style = MaterialTheme.typography.headlineMedium)
            if (client.archived) Text(stringResource(R.string.archived))
            Text(client.phone ?: stringResource(R.string.no_phone))
            client.notes?.let { Text(it) }
            Text(stringResource(R.string.visits, history.count { it.status == Status.COMPLETED }))
            if (!client.phone.isNullOrBlank()) TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", client.phone, null))) }.onFailure { vm.showError(Problem.IO) } }) { Text(stringResource(R.string.call)) }
            if (!client.archived) {
                Button(onClick = { book(id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.book_client)) }
                TextButton(onClick = { edit(id) }, enabled = !busy) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = { archive = true }, enabled = !busy) { Text(stringResource(R.string.archive)) }
            }
            Text(stringResource(R.string.history), style = MaterialTheme.typography.titleLarge)
            if (history.isEmpty()) Text(stringResource(R.string.history_empty))
        }
        items(history, key = { it.id }) { AppointmentCard(it, client.name, { appointment(it.id) }, showDate = true) }
    }
    if (archive) Confirmation(stringResource(R.string.archive), stringResource(R.string.archive_client_confirm), { archive = false }, { archive = false; vm.archiveClient(id) }, R.string.archive)
}
@Composable fun AppointmentDetails(vm: SalonViewModel, state: SalonState, id: String, busy: Boolean, back: () -> Unit, edit: (String) -> Unit) {
    val appointment = state.appointments.find { it.id == id }
    if (appointment == null) { Column { TextButton(onClick = back) { Text(stringResource(R.string.back)) }; Text(stringResource(R.string.not_found)) }; return }
    var deleting by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = back) { Text(stringResource(R.string.back)) }; Text(stringResource(R.string.details), style = MaterialTheme.typography.headlineSmall) }
        item { AppointmentCard(appointment, state.clients.find { it.id == appointment.clientId }?.name ?: appointment.clientNameSnapshot, {}, showDate = true) }
        item { appointment.notes?.let { Text(it) }; Text(stringResource(R.string.actual_price_hint), style = MaterialTheme.typography.bodySmall) }
        item { Button(onClick = { edit(id) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.move_appointment)) } }
        if (appointment.status == Status.PLANNED) {
            if (appointment.startAt <= System.currentTimeMillis()) {
                item { Button(onClick = { vm.status(id, Status.COMPLETED) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.complete_appointment)) } }
                item { OutlinedButton(onClick = { vm.status(id, Status.NO_SHOW) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mark_no_show)) } }
            }
            item { OutlinedButton(onClick = { vm.status(id, Status.CANCELLED) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel_appointment)) } }
        }
        item { TextButton(onClick = { deleting = true }, enabled = !busy) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } }
    }
    if (deleting) Confirmation(stringResource(R.string.delete), stringResource(R.string.delete_confirm), { deleting = false }, { deleting = false; vm.delete(id) }, R.string.delete)
}
