package ru.parik.salon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ru.parik.salon.R
import ru.parik.salon.data.Appointment
import ru.parik.salon.domain.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun TextField(value: String, changed: (String) -> Unit, label: Int, keyboard: KeyboardType = KeyboardType.Text, multiline: Boolean = false) {
    OutlinedTextField(value, changed, label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(), singleLine = !multiline, minLines = if (multiline) 3 else 1, keyboardOptions = KeyboardOptions(keyboardType = keyboard))
}
@Composable fun PriceField(value: String, changed: (String) -> Unit, currency: String) {
    OutlinedTextField(value, changed, label = { Text(stringResource(R.string.price, currency)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
@Composable fun Form(title: String, busy: Boolean, dirty: Boolean, back: () -> Unit, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    val exit = { if (dirty) confirmExit = true else back() }
    BackHandler(enabled = true) { if (!busy) exit() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = exit, enabled = !busy) { Text(stringResource(R.string.back)) }
            Button(onClick = save, enabled = !busy) { Text(stringResource(R.string.save)) }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.required), style = MaterialTheme.typography.bodySmall)
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmExit) Confirmation(stringResource(R.string.unsaved_title), stringResource(R.string.unsaved_message), { confirmExit = false }, { confirmExit = false; back() }, R.string.discard)
}
@Composable fun Confirmation(title: String, message: String, dismiss: () -> Unit, confirm: () -> Unit, action: Int = R.string.confirm) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = confirm) { Text(stringResource(action)) } }, dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
@Composable fun <T> Choice(label: String, selected: String, values: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(selected.ifBlank { stringResource(R.string.choose) }) }
        if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text(label) }, text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(values.size) { index -> TextButton(onClick = { onSelect(values[index].first); expanded = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(values[index].second) } }
            }
        }, confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.close)) } })
    }
}
fun statusText(status: Status): Int = when (status) {
    Status.PLANNED -> R.string.planned
    Status.COMPLETED -> R.string.completed
    Status.CANCELLED -> R.string.cancelled
    Status.NO_SHOW -> R.string.no_show
}
@Composable fun StatusLabel(status: Status) {
    val color = when (status) { Status.PLANNED -> MaterialTheme.colorScheme.primaryContainer; Status.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer; Status.CANCELLED, Status.NO_SHOW -> MaterialTheme.colorScheme.surfaceVariant }
    Surface(color = color, shape = MaterialTheme.shapes.small) { Text(stringResource(statusText(status)), modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp), style = MaterialTheme.typography.labelLarge) }
}
fun startText(appointment: Appointment): String = Instant.ofEpochMilli(appointment.startAt).atZone(ZoneId.systemDefault()).format(Rules.timeFormat)
fun endText(appointment: Appointment): String {
    val start = Instant.ofEpochMilli(appointment.startAt).atZone(ZoneId.systemDefault())
    val end = appointment.interval().end.atZone(ZoneId.systemDefault())
    return if (start.toLocalDate() == end.toLocalDate()) end.format(Rules.timeFormat) else end.format(DateTimeFormatter.ofPattern("dd.MM HH:mm"))
}
@Composable fun AppointmentCard(appointment: Appointment, clientName: String, click: () -> Unit, showDate: Boolean = false) {
    Card(onClick = click, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (showDate) Text(Instant.ofEpochMilli(appointment.startAt).atZone(ZoneId.systemDefault()).format(Rules.dateFormat), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.time_range, startText(appointment), endText(appointment)), style = MaterialTheme.typography.titleMedium)
            Text(clientName, style = MaterialTheme.typography.titleLarge)
            Text(appointment.serviceNameSnapshot)
            if (appointment.priceMinor != null) Text(Rules.formatPrice(appointment.priceMinor, appointment.currencyCode))
            StatusLabel(appointment.status)
        }
    }
}
fun errorText(problem: Problem): Int = when (problem) {
    Problem.NAME -> R.string.error_NAME
    Problem.DURATION -> R.string.error_DURATION
    Problem.PRICE -> R.string.error_PRICE
    Problem.CURRENCY -> R.string.error_CURRENCY
    Problem.CLIENT -> R.string.error_CLIENT
    Problem.SERVICE -> R.string.error_SERVICE
    Problem.DATE -> R.string.error_DATE
    Problem.TIME -> R.string.error_TIME
    Problem.HOURS -> R.string.error_HOURS
    Problem.CONFLICT -> R.string.error_CONFLICT
    Problem.OUTSIDE_HOURS -> R.string.error_OUTSIDE_HOURS
    Problem.DUPLICATE -> R.string.error_DUPLICATE
    Problem.BACKUP -> R.string.error_BACKUP
    Problem.BACKUP_SIZE -> R.string.error_BACKUP_SIZE
    Problem.BACKUP_VERSION -> R.string.error_BACKUP_VERSION
    Problem.FUTURE_COMPLETION -> R.string.error_FUTURE_COMPLETION
    Problem.NOT_FOUND -> R.string.error_NOT_FOUND
    Problem.IO -> R.string.error_IO
}
