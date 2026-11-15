package ru.parik.salon.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import ru.parik.salon.SalonApplication
import ru.parik.salon.data.*
import ru.parik.salon.domain.*
import java.time.LocalDate

 data class SalonState(val clients: List<Client> = emptyList(), val services: List<Service> = emptyList(), val appointments: List<Appointment> = emptyList(), val settings: Settings = Settings(), val loaded: Boolean = false)
class SalonViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    val repository = (application as SalonApplication).repository
    val state = combine(repository.dao.clients(), repository.dao.services(), repository.dao.appointments(), repository.settings.flow) { clients, services, appointments, settings ->
        SalonState(clients, services, appointments, settings, true)
    }.catch { error.value = RuleViolation(Problem.IO) }.stateIn(viewModelScope, SharingStarted.Eagerly, SalonState())
    val selectedDay = savedState.getStateFlow("day", LocalDate.now().toString())
    val createdClient = savedState.getStateFlow("createdClient", "")
    val createdService = savedState.getStateFlow("createdService", "")
    val settingsDirty = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<RuleViolation?>(null)
    val imported = MutableStateFlow<Backup?>(null)
    private val events = Channel<Boolean>(Channel.BUFFERED)
    val saved = events.receiveAsFlow()
    var retry: (() -> Unit)? = null
        private set
    fun day(date: LocalDate) { savedState["day"] = date.toString() }
    fun clearCreatedClient() { savedState["createdClient"] = "" }
    fun clearCreatedService() { savedState["createdService"] = "" }
    private fun task(close: Boolean = true, retryAction: (() -> Unit)? = null, action: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        error.value = null
        retry = null
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; events.send(close) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: RuleViolation) { error.value = failure; retry = retryAction }
            catch (_: Exception) { error.value = RuleViolation(Problem.IO) }
            finally { busy.value = false }
        }
    }
    fun saveClient(client: Client, allowDuplicate: Boolean = false): Unit = task(retryAction = { saveClient(client, true) }) {
        repository.saveClient(client, allowDuplicate); withContext(Dispatchers.Main) { savedState["createdClient"] = client.id }
    }
    fun saveService(service: Service) = task {
        repository.saveService(service); withContext(Dispatchers.Main) { savedState["createdService"] = service.id }
    }
    fun saveAppointment(appointment: Appointment, outside: Boolean = false): Unit = task(retryAction = { saveAppointment(appointment, true) }) { repository.saveAppointment(appointment, outside) }
    fun status(id: String, status: Status) = task(close = false) { repository.changeStatus(id, status) }
    fun delete(id: String) = task { repository.deleteAppointment(id) }
    fun archiveClient(id: String) = task { repository.archiveClient(id) }
    fun archiveService(id: String) = task { repository.archiveService(id) }
    fun settings(settings: Settings) = task(close = false) { repository.saveSettings(settings) }
    fun seed(names: List<String>) = task(close = false) { repository.seedServices(names) }
    fun export(uri: Uri) = task(close = false) {
        val backup = repository.export()
        getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(BackupCodec.json.encodeToString(backup).toByteArray(Charsets.UTF_8))
        } ?: throw RuleViolation(Problem.IO)
    }
    fun inspectImport(uri: Uri) = task(close = false) {
        val stream = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw RuleViolation(Problem.IO)
        imported.value = BackupCodec.read(stream)
    }
    fun restore() {
        val backup = imported.value ?: return
        task(close = false) { repository.restore(backup); imported.value = null }
    }
    fun showError(problem: Problem) { error.value = RuleViolation(problem) }
}
