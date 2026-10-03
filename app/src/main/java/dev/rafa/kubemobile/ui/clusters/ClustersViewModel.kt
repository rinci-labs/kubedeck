package dev.rafa.kubemobile.ui.clusters

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.config.KubeconfigParser
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.hostPort
import dev.rafa.kubemobile.ui.toUiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** A parsed kubeconfig context awaiting the user's confirmation. */
data class ImportCandidate(
    val profile: ClusterProfile,
    val selected: Boolean = true,
)

/** State of the kubeconfig import flow. */
sealed interface ImportState {
    data object Idle : ImportState
    data object Parsing : ImportState
    data class Ready(val candidates: List<ImportCandidate>, val warnings: List<String>) : ImportState
    data class Error(val error: UiError) : ImportState
}

/** Live connection attempt shown on the cluster row that was tapped. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data class Connecting(val profileId: String) : ConnectState
    data class Failed(val profileId: String, val error: UiError) : ConnectState
}

class ClustersViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val application: Application get() = app.getApplication<Application>()

    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import.asStateFlow()

    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    val profiles: StateFlow<List<ClusterProfile>> = app.profiles
    val activeId: StateFlow<String?> = app.activeId

    init {
        pendingImport()?.let { uri -> importUri(uri) }
    }

    /* --------------------------------------------------------------------------------------- */

    fun pendingImport(): Uri? = savedState.get<String>(KEY_PENDING_URI)?.let(Uri::parse)

    fun rememberPendingImport(uri: Uri?) {
        savedState[KEY_PENDING_URI] = uri?.toString()
    }

    fun importText(text: String, label: String?) {
        _import.value = ImportState.Parsing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { KubeconfigParser.parse(text) }
            _import.value = if (result.profiles.isEmpty()) {
                ImportState.Ready(emptyList(), result.warnings)
            } else {
                ImportState.Ready(result.profiles.map { ImportCandidate(it) }, result.warnings)
            }
            if (label != null) savedState[KEY_IMPORT_LABEL] = label
        }
    }

    fun importUri(uri: Uri) {
        _import.value = ImportState.Parsing
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    application.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                _import.value = ImportState.Error(failure(application.getString(dev.rafa.kubemobile.R.string.clusters_read_failed)))
                return@launch
            }
            importText(text, uri.lastPathSegment)
        }
    }

    fun toggleCandidate(id: String) {
        val current = _import.value as? ImportState.Ready ?: return
        _import.value = current.copy(
            candidates = current.candidates.map {
                if (it.profile.id == id) it.copy(selected = !it.selected) else it
            },
        )
    }

    fun setAllCandidates(selected: Boolean) {
        val current = _import.value as? ImportState.Ready ?: return
        _import.value = current.copy(candidates = current.candidates.map { it.copy(selected = selected) })
    }

    fun resetImport() {
        _import.value = ImportState.Idle
        savedState[KEY_PENDING_URI] = null
    }

    fun resetConnect() {
        _connect.value = ConnectState.Idle
    }

    /** Persists the selected contexts. Returns how many profiles were written. */
    fun confirmImport(onDone: (Int) -> Unit) {
        val current = _import.value as? ImportState.Ready ?: return
        val selected = current.candidates.filter { it.selected }.map { it.profile }
        if (selected.isEmpty()) {
            app.notify(application.getString(dev.rafa.kubemobile.R.string.clusters_import_none))
            return
        }
        viewModelScope.launch {
            val named = disambiguateNames(selected, app.profiles.value)
            val saved = runCatching { app.upsert(named) }.getOrElse {
                _import.value = ImportState.Error(it.toUiError())
                return@launch
            }
            _import.value = ImportState.Idle
            savedState[KEY_PENDING_URI] = null
            onDone(saved.size)
        }
    }

    /**
     * Makes every imported cluster identifiable.
     *
     * One kubeconfig routinely yields several contexts that share a name, and two kubeconfigs for
     * the same cluster (a direct address and a tunneled one, say) collide too. Since the name is
     * what the user picks from, a collision is made unique by appending `host:port` — the field
     * that actually differs. A cluster we already store keeps whatever name it has, so
     * re-importing the same file never flips its label back.
     */
    private fun disambiguateNames(
        incoming: List<ClusterProfile>,
        existing: List<ClusterProfile>,
    ): List<ClusterProfile> {
        val storedByServer = existing.associateBy({ it.server.trimEnd('/') }, { it.name })
        val claimed = existing.associate { it.name.lowercase() to it.server.trimEnd('/') }.toMutableMap()
        return incoming.map { profile ->
            val server = profile.server.trimEnd('/')
            val known = storedByServer[server]
            val collides = claimed[profile.name.lowercase()]?.let { it != server } == true
            val resolved = when {
                known != null -> profile.copy(name = known)
                collides -> profile.copy(name = "${profile.name} (${profile.hostPort})")
                else -> profile
            }
            claimed[resolved.name.lowercase()] = server
            resolved
        }
    }

    /* --------------------------------------------------------------------------------------- */

    /**
     * Opens the cluster, showing progress on the row itself. On success the caller navigates and
     * shows a confirmation; on failure the row keeps a readable API error with a retry action, so a
     * tap can never appear to do nothing.
     */
    fun connect(profile: ClusterProfile, onConnected: (String) -> Unit) {
        _connect.value = ConnectState.Connecting(profile.id)
        viewModelScope.launch {
            runCatching { app.connect(profile) }
                .onSuccess {
                    _connect.value = ConnectState.Idle
                    onConnected(profile.name)
                }
                .onFailure {
                    _connect.value = ConnectState.Failed(profile.id, it.toUiError(discovery = true))
                }
        }
    }

    fun addManual(profile: ClusterProfile, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { app.upsert(listOf(profile)) }
                .onSuccess { onDone() }
                .onFailure { app.notify(it.shortMessageOrNull()) }
        }
    }

    fun setActive(profile: ClusterProfile) {
        viewModelScope.launch {
            runCatching { app.setActive(profile.id) }.onFailure { app.notify(it.shortMessageOrNull()) }
        }
    }

    fun duplicate(profile: ClusterProfile) {
        viewModelScope.launch {
            val copy = profile.copy(
                id = UUID.randomUUID().toString(),
                name = profile.name + application.getString(dev.rafa.kubemobile.R.string.clusters_duplicate_suffix),
                createdAt = System.currentTimeMillis(),
                lastUsedAt = 0L,
                source = ClusterProfile.SOURCE_MANUAL,
            )
            runCatching { app.upsert(listOf(copy)) }
                .onFailure { app.notify(it.shortMessageOrNull()) }
        }
    }

    fun updateNamespace(profile: ClusterProfile, namespace: String?) {
        viewModelScope.launch {
            runCatching { app.update(profile.copy(namespace = namespace?.takeIf { it.isNotBlank() })) }
                .onSuccess { app.notify(application.getString(dev.rafa.kubemobile.R.string.clusters_updated)) }
                .onFailure { app.notify(it.shortMessageOrNull()) }
        }
    }

    fun delete(profile: ClusterProfile) {
        viewModelScope.launch {
            runCatching { app.removeProfile(profile.id) }
                .onFailure { app.notify(it.shortMessageOrNull()) }
        }
    }

    private fun failure(message: String): UiError = UiError(
        dev.rafa.kubemobile.R.string.error_generic_title,
        message,
        message,
    )

    private fun Throwable.shortMessageOrNull(): String = message ?: "Request failed"

    companion object {
        private const val KEY_PENDING_URI = "pendingImportUri"
        private const val KEY_IMPORT_LABEL = "importLabel"
    }
}
