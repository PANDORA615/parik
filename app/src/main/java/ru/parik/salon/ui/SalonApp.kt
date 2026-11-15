package ru.parik.salon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.*
import ru.parik.salon.R
import ru.parik.salon.domain.*

@Composable fun SalonApp(vm: SalonViewModel, initialAppointment: String? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route.orEmpty()
    val settingsDirty by vm.settingsDirty.collectAsStateWithLifecycle()
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun navigateTab(path: String) { nav.navigate(path) { popUpTo("schedule"); launchSingleTop = true } }
    BackHandler(enabled = route == "settings" && settingsDirty) { pendingPath = "schedule" }
    val dark = when (state.settings.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFA9CFB5), secondary = Color(0xFFB9CCC0)) else lightColorScheme(primary = Color(0xFF496958), secondary = Color(0xFF6C6255), background = Color(0xFFF8FAF5), surface = Color(0xFFF8FAF5))
    MaterialTheme(colorScheme = colors) {
        val snackbar = remember { SnackbarHostState() }
        val savedMessage = stringResource(R.string.saved)
        LaunchedEffect(vm) { vm.saved.collect { close -> if (close) nav.popBackStack() else snackbar.showSnackbar(savedMessage) } }
        LaunchedEffect(state.loaded, initialAppointment) { if (state.loaded && initialAppointment != null) nav.navigate("appointment/$initialAppointment") { launchSingleTop = true } }
        val tabs = listOf(Triple("schedule", R.string.schedule, Icons.Default.DateRange), Triple("clients", R.string.clients, Icons.Default.Person), Triple("services", R.string.services, Icons.AutoMirrored.Filled.List), Triple("settings", R.string.settings, Icons.Default.Settings))
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
            if (route in tabs.map { it.first }) NavigationBar {
                tabs.forEach { (path, title, icon) -> NavigationBarItem(selected = route == path, onClick = { if (route == "settings" && settingsDirty && path != route) pendingPath = path else if (path != route) navigateTab(path) }, icon = { Icon(icon, contentDescription = null) }, label = { Text(stringResource(title)) }) }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!state.loaded) { CircularProgressIndicator(Modifier.padding(32.dp)) }
                else NavHost(nav, startDestination = "schedule") {
                    composable("schedule") { ScheduleScreen(vm, state, { vm.clearCreatedClient(); vm.clearCreatedService(); nav.navigate("edit-appointment/new") }, { nav.navigate("appointment/$it") }) }
                    composable("clients") { ClientsScreen(state, { nav.navigate("edit-client/new") }, { nav.navigate("client/$it") }) }
                    composable("services") { ServicesScreen(state, { nav.navigate("edit-service/$it") }) }
                    composable("settings") { SettingsScreen(vm, state, busy) }
                    composable("edit-client/{id}") { ClientEditor(vm, state, it.arguments?.getString("id").orEmpty(), busy) { nav.popBackStack() } }
                    composable("client/{id}") { ClientDetails(vm, state, it.arguments?.getString("id").orEmpty(), busy, { nav.popBackStack() }, { id -> nav.navigate("edit-client/$id") }, { id -> vm.clearCreatedClient(); vm.clearCreatedService(); nav.navigate("edit-appointment/new?client=$id") }, { id -> nav.navigate("appointment/$id") }) }
                    composable("edit-service/{id}") { ServiceEditor(vm, state, it.arguments?.getString("id").orEmpty(), busy) { nav.popBackStack() } }
                    composable("edit-appointment/{id}?client={client}") { AppointmentEditor(vm, state, it.arguments?.getString("id").orEmpty(), it.arguments?.getString("client"), busy, { nav.popBackStack() }, { nav.navigate("edit-client/new") }, { nav.navigate("edit-service/new") }) }
                    composable("appointment/{id}") { AppointmentDetails(vm, state, it.arguments?.getString("id").orEmpty(), busy, { nav.popBackStack() }, { id -> vm.clearCreatedClient(); vm.clearCreatedService(); nav.navigate("edit-appointment/$id") }) }
                }
                if (busy && route in tabs.map { it.first }) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        pendingPath?.let { path ->
            Confirmation(stringResource(R.string.unsaved_title), stringResource(R.string.unsaved_message), { pendingPath = null }, { pendingPath = null; vm.settingsDirty.value = false; navigateTab(path) }, R.string.discard)
        }
        error?.let { failure ->
            val duplicate = failure.problem == Problem.DUPLICATE
            val outside = failure.problem == Problem.OUTSIDE_HOURS
            val conflict = failure.problem == Problem.CONFLICT
            AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text(stringResource(if (duplicate) R.string.duplicate_title else if (outside) R.string.outside_title else if (conflict) R.string.conflict_title else R.string.error_title)) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (duplicate) R.string.duplicate_message else if (outside) R.string.outside_message else if (conflict) R.string.conflict_message else errorText(failure.problem)))
                    failure.conflicts.forEach { id ->
                        if (duplicate) state.clients.find { it.id == id }?.let { client ->
                            TextButton(onClick = { vm.error.value = null; nav.navigate("client/${client.id}") }) { Text(client.name + " · " + client.phone.orEmpty()) }
                        } else state.appointments.find { it.id == id }?.let { appointment ->
                            Text(startText(appointment) + "–" + endText(appointment) + " · " + appointment.clientNameSnapshot)
                        }
                    }
                }
            }, confirmButton = {
                if ((duplicate || outside) && vm.retry != null) TextButton(onClick = { val retry = vm.retry; vm.error.value = null; retry?.invoke() }) { Text(stringResource(if (duplicate) R.string.save_duplicate else R.string.save_anyway)) }
                else TextButton(onClick = { vm.error.value = null }) { Text(stringResource(R.string.close)) }
            }, dismissButton = { if (duplicate || outside) TextButton(onClick = { vm.error.value = null }) { Text(stringResource(R.string.cancel)) } })
        }
        if (state.loaded && !state.settings.starterOffered) {
            val starterNames = listOf(R.string.starter_men, R.string.starter_women, R.string.starter_child, R.string.starter_color, R.string.starter_style).map { stringResource(it) }
            AlertDialog(onDismissRequest = {}, title = { Text(stringResource(R.string.starter_title)) }, text = { Text(stringResource(R.string.starter_message)) }, confirmButton = { TextButton(onClick = { vm.seed(starterNames) }, enabled = !busy) { Text(stringResource(R.string.starter_add)) } }, dismissButton = { TextButton(onClick = { vm.settings(state.settings.copy(starterOffered = true)) }, enabled = !busy) { Text(stringResource(R.string.starter_skip)) } })
        }
    }
}
