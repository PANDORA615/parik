package ru.parik.salon.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.parik.salon.R
import ru.parik.salon.domain.*
import ru.parik.salon.data.BackupCodec
import java.time.LocalTime

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(vm: SalonViewModel, state: SalonState, busy: Boolean) {
    val context = LocalContext.current
    var days by rememberSaveable { mutableStateOf(state.settings.days.sorted().joinToString(",")) }
    fun selectedDays() = days.split(',').mapNotNull { it.toIntOrNull() }.toSet()
    var start by rememberSaveable { mutableStateOf(LocalTime.ofSecondOfDay(state.settings.startMinute * 60L).format(Rules.timeFormat)) }
    var end by rememberSaveable { mutableStateOf(if (state.settings.endMinute == 1440) "24:00" else LocalTime.ofSecondOfDay(state.settings.endMinute * 60L).format(Rules.timeFormat)) }
    var currency by rememberSaveable { mutableStateOf(state.settings.currency) }
    var reminder by rememberSaveable { mutableIntStateOf(state.settings.reminderMinutes) }
    var step by rememberSaveable { mutableIntStateOf(state.settings.stepMinutes) }
    var theme by rememberSaveable { mutableStateOf(state.settings.theme.name) }
    var pendingReminder by rememberSaveable { mutableIntStateOf(0) }
    var explainPermission by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var exportConfirm by rememberSaveable { mutableStateOf(false) }
    val imported by vm.imported.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) reminder = pendingReminder else { reminder = 0; permissionDenied = true }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) vm.export(uri) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.inspectImport(uri) }
    var lastSettings by rememberSaveable { mutableStateOf(BackupCodec.json.encodeToString(state.settings)) }
    val expectedStart = LocalTime.ofSecondOfDay(state.settings.startMinute * 60L).format(Rules.timeFormat)
    val expectedEnd = if (state.settings.endMinute == 1440) "24:00" else LocalTime.ofSecondOfDay(state.settings.endMinute * 60L).format(Rules.timeFormat)
    val dirty = selectedDays() != state.settings.days || start != expectedStart || end != expectedEnd ||
        currency != state.settings.currency || reminder != state.settings.reminderMinutes || step != state.settings.stepMinutes || theme != state.settings.theme.name
    SideEffect { vm.settingsDirty.value = dirty }
    DisposableEffect(Unit) { onDispose { vm.settingsDirty.value = false } }
    // Imported settings refresh the editor; activity recreation preserves drafts.
    LaunchedEffect(state.settings) {
        val encoded = BackupCodec.json.encodeToString(state.settings)
        if (encoded == lastSettings) return@LaunchedEffect
        lastSettings = encoded
        days = state.settings.days.sorted().joinToString(",")
        start = LocalTime.ofSecondOfDay(state.settings.startMinute * 60L).format(Rules.timeFormat)
        end = if (state.settings.endMinute == 1440) "24:00" else LocalTime.ofSecondOfDay(state.settings.endMinute * 60L).format(Rules.timeFormat)
        currency = state.settings.currency; reminder = state.settings.reminderMinutes; step = state.settings.stepMinutes; theme = state.settings.theme.name
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(stringResource(R.string.work_days), style = MaterialTheme.typography.titleMedium)
                val labels = listOf(R.string.mon, R.string.tue, R.string.wed, R.string.thu, R.string.fri, R.string.sat, R.string.sun)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { labels.forEachIndexed { index, label ->
                    FilterChip(selected = index + 1 in selectedDays(), onClick = {
                        val selected = selectedDays().toMutableSet(); if (!selected.add(index + 1)) selected.remove(index + 1)
                        days = selected.sorted().joinToString(",")
                    }, label = { Text(stringResource(label)) })
                } }
            }
            item { TextField(start, { start = it }, R.string.work_start) }
            item { TextField(end, { end = it }, R.string.work_end) }
            item { Choice(stringResource(R.string.grid_step), stringResource(R.string.minutes, step), listOf(15, 30, 60).map { it to stringResource(R.string.minutes, it) }) { step = it } }
            item { TextField(currency, { currency = it.uppercase(java.util.Locale.ROOT) }, R.string.currency) }
            item {
                Choice(stringResource(R.string.reminders), if (reminder == 0) stringResource(R.string.reminders_off) else stringResource(R.string.reminder_before, reminder), listOf(0, 15, 30, 60).map { it to if (it == 0) stringResource(R.string.reminders_off) else stringResource(R.string.reminder_before, it) }) {
                    if (it > 0 && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        pendingReminder = it; explainPermission = true
                    } else reminder = it
                }
                Text(stringResource(R.string.reminder_explanation), style = MaterialTheme.typography.bodySmall)
                if (permissionDenied) Text(stringResource(R.string.notification_denied), color = MaterialTheme.colorScheme.error)
            }
            item {
                val themes = listOf(ThemeMode.SYSTEM to stringResource(R.string.theme_system), ThemeMode.LIGHT to stringResource(R.string.theme_light), ThemeMode.DARK to stringResource(R.string.theme_dark))
                Choice(stringResource(R.string.theme), themes.first { it.first.name == theme }.second, themes) { theme = it.name }
            }
            item { Button(onClick = {
                try {
                    val startMinute = LocalTime.parse(start, Rules.timeFormat).toSecondOfDay() / 60
                    val endMinute = if (end == "24:00") 1440 else LocalTime.parse(end, Rules.timeFormat).toSecondOfDay() / 60
                    vm.settings(state.settings.copy(days = selectedDays(), startMinute = startMinute, endMinute = endMinute, currency = currency, reminderMinutes = reminder, stepMinutes = step, theme = ThemeMode.valueOf(theme)))
                } catch (_: Exception) { vm.showError(Problem.HOURS) }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) } }
            item { HorizontalDivider(); Text(stringResource(R.string.backup), style = MaterialTheme.typography.titleLarge) }
            item { OutlinedButton(onClick = { exportConfirm = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export)) } }
            item { OutlinedButton(onClick = { runCatching { importPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure { vm.showError(Problem.IO) } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_backup)) } }
            item { Text(stringResource(R.string.privacy_note)) }
            item { Text(stringResource(R.string.timezone_note)) }
            item { Text(stringResource(R.string.break_note)) }
        }
    }
    if (explainPermission) Confirmation(stringResource(R.string.reminders), stringResource(R.string.reminder_explanation), { explainPermission = false }, { explainPermission = false; if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) })
    if (exportConfirm) Confirmation(stringResource(R.string.export), stringResource(R.string.export_warning), { exportConfirm = false }, { exportConfirm = false; runCatching { exportPicker.launch("salon-${java.time.LocalDate.now()}.json") }.onFailure { vm.showError(Problem.IO) } }, R.string.export)
    imported?.let { backup ->
        Confirmation(stringResource(R.string.restore), stringResource(R.string.import_summary, backup.clients.size, backup.services.size, backup.appointments.size) + "\n\n" + stringResource(R.string.import_warning), { vm.imported.value = null }, { vm.restore() }, R.string.restore)
    }
}
