package dev.rafa.kubemobile.ui.helm

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.k8s.YamlIo
import dev.rafa.kubemobile.ops.HelmOps
import dev.rafa.kubemobile.ops.HelmRelease
import dev.rafa.kubemobile.ops.ManifestEntry
import dev.rafa.kubemobile.ops.manifestEntries
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.shortMessage
import dev.rafa.kubemobile.ui.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class HelmListState(
    val releases: List<HelmRelease> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiError? = null,
)

class HelmViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(HelmListState())
    val state: StateFlow<HelmListState> = _state.asStateFlow()

    private val _scope = MutableStateFlow<List<String>>(emptyList())
    /** Namespaces searched; an empty selection means "every namespace" (`listOf("")`). */
    val scope: StateFlow<List<String>> = _scope.asStateFlow()

    private val _search = MutableStateFlow(savedState.get<String>(KEY_SEARCH).orEmpty())
    val search: StateFlow<String> = _search.asStateFlow()

    private var loadJob: Job? = null

    fun start() {
        if (_scope.value.isEmpty()) {
            val profileNamespace = app.activeProfile?.namespace?.takeIf { it.isNotBlank() }
            _scope.value = profileNamespace?.let { listOf(it) } ?: emptyList()
        }
        if (_state.value.releases.isEmpty()) load(initial = true)
    }

    fun setSearch(value: String) {
        _search.value = value
        savedState[KEY_SEARCH] = value
    }

    fun setScope(namespaces: List<String>) {
        _scope.value = namespaces
        load(initial = true)
    }

    fun toggleNamespace(namespace: String) {
        val current = _scope.value.toMutableList()
        if (!current.remove(namespace)) current += namespace
        setScope(current)
    }

    fun refresh() = load(initial = false)

    fun retry() = load(initial = true)

    fun visible(): List<HelmRelease> {
        val query = _search.value.trim().lowercase()
        val releases = _state.value.releases
        if (query.isEmpty()) return releases
        return releases.filter {
            it.name.lowercase().contains(query) ||
                it.chartName.lowercase().contains(query) ||
                it.namespace.lowercase().contains(query) ||
                it.appVersion.lowercase().contains(query)
        }
    }

    private fun load(initial: Boolean) {
        val session = app.session ?: run {
            _state.value = HelmListState(
                error = UiError(
                    dev.rafa.kubemobile.R.string.error_missing_cluster,
                    app.getApplication<android.app.Application>()
                        .getString(dev.rafa.kubemobile.R.string.error_missing_cluster),
                    null,
                ),
            )
            return
        }
        loadJob?.cancel()
        _state.value = _state.value.copy(
            loading = initial && _state.value.releases.isEmpty(),
            refreshing = !initial || _state.value.releases.isNotEmpty(),
            error = null,
        )
        loadJob = viewModelScope.launch {
            val scope = _scope.value.ifEmpty { listOf("") }
            runCatching { HelmOps.listAll(session.connection, scope) }
                .onSuccess {
                    _state.value = _state.value.copy(
                        releases = it,
                        loading = false,
                        refreshing = false,
                        error = null,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        error = it.toUiError(),
                    )
                }
        }
    }

    companion object {
        private const val KEY_SEARCH = "helm.search"
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Detail                                                                                        */
/* -------------------------------------------------------------------------------------------- */

data class HelmDetailState(
    val release: HelmRelease? = null,
    val history: List<HelmRelease> = emptyList(),
    val loading: Boolean = true,
    val error: UiError? = null,
    val problems: List<String> = emptyList(),
    val uninstalled: Boolean = false,
)

class HelmDetailViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(HelmDetailState())
    val state: StateFlow<HelmDetailState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var namespace: String = ""
    private var releaseName: String = ""

    fun start(namespace: String, name: String, revision: Int?) {
        if (this.namespace == namespace && this.releaseName == name && _state.value.release != null) {
            revision?.let { selectRevision(it) }
            return
        }
        this.namespace = namespace
        this.releaseName = name
        load(revision)
    }

    fun load(revision: Int? = null) {
        val session = app.session ?: return
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = _state.value.release == null, error = null)
        loadJob = viewModelScope.launch {
            runCatching {
                val releases = HelmOps.listFor(session.connection, namespace)
                    .filter { it.name == releaseName }
                val history = releases.firstOrNull()?.let { HelmOps.history(session.connection, it) }.orEmpty()
                val target = revision?.let { wanted -> history.firstOrNull { it.revision == wanted } }
                    ?: history.firstOrNull()
                    ?: releases.firstOrNull()
                target to history
            }
                .onSuccess { (release, history) ->
                    _state.value = _state.value.copy(
                        release = release,
                        history = history,
                        loading = false,
                        error = null,
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = it.toUiError())
                }
        }
    }

    fun selectRevision(revision: Int) = load(revision)

    fun entries(): List<ManifestEntry> = _state.value.release?.let { HelmOps.manifestEntries(it) }.orEmpty()

    fun userValues(): String = _state.value.release?.userValues.orEmpty()

    fun chartValues(): String = _state.value.release?.values.orEmpty()

    /** Diffs the two value documents so the Overview tab can flag what the user overrode. */
    fun valueDiff(): List<Triple<String, String?, String?>> {
        val defaults = parseFlat(chartValues())
        val user = parseFlat(userValues())
        val keys = (defaults.keys + user.keys).sorted()
        return keys.map { key -> Triple(key, defaults[key], user[key]) }
    }

    private fun parseFlat(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val element = runCatching { YamlIo.parse(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        fun walk(prefix: String, obj: JsonObject) {
            obj.forEach { (key, value) ->
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when (value) {
                    is JsonObject -> walk(path, value)
                    else -> out[path] = value.toString().trim('"')
                }
            }
        }
        walk("", element)
        return out
    }

    fun uninstall(deleteResources: Boolean, onDone: (List<String>) -> Unit) {
        val session: ClusterSession = app.session ?: return
        val release = _state.value.release ?: return
        viewModelScope.launch {
            val problems = runCatching {
                HelmOps.uninstall(app.repository, session, release, deleteResources)
            }.getOrElse { error ->
                app.notify(error.shortMessage())
                return@launch
            }
            _state.value = _state.value.copy(problems = problems, uninstalled = true)
            onDone(problems)
        }
    }
}
