package dev.rafa.kubemobile.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.KubeApiException
import dev.rafa.kubemobile.k8s.KubeList
import dev.rafa.kubemobile.k8s.Metrics
import dev.rafa.kubemobile.k8s.ResourceUsage
import dev.rafa.kubemobile.k8s.YamlIo
import dev.rafa.kubemobile.k8s.resourceName
import dev.rafa.kubemobile.k8s.resourceNamespace
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ui.ALL_NAMESPACES
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.ResourceRef
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.minimalManifest
import dev.rafa.kubemobile.ui.shortMessage
import dev.rafa.kubemobile.ui.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** One row in the resource list, precomputed so recomposition stays cheap. */
data class ResourceRow(
    val uid: String,
    val name: String,
    val namespace: String?,
    val object_: JsonObject,
)

data class ListUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val resource: ApiResource? = null,
    val rows: List<ResourceRow> = emptyList(),
    val total: Int = 0,
    val error: UiError? = null,
    val truncated: Boolean = false,
    /** Set when a cluster-wide list was refused and the list was assembled namespace by namespace. */
    val partial: Boolean = false,
    /** Metrics API failures are separate from a genuinely absent metrics-server. */
    val metricsError: UiError? = null,
    /** Per-pod usage keyed by `namespace/name`. */
    val metrics: Map<String, ResourceUsage> = emptyMap(),
)

class ResourceListViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(ListUiState())
    val state: StateFlow<ListUiState> = _state.asStateFlow()

    private val _namespace = MutableStateFlow<String?>(null)
    val namespace: StateFlow<String?> = _namespace.asStateFlow()

    private val _search = MutableStateFlow(savedState.get<String>(KEY_SEARCH).orEmpty())
    val search: StateFlow<String> = _search.asStateFlow()

    private val _selector = MutableStateFlow(savedState.get<String>(KEY_SELECTOR).orEmpty())
    val selector: StateFlow<String> = _selector.asStateFlow()

    private val _createYaml = MutableStateFlow(savedState.get<String>(KEY_CREATE_YAML).orEmpty())
    val createYaml: StateFlow<String> = _createYaml.asStateFlow()

    private var resourceRef: ResourceRef? = null
    private var loadJob: Job? = null
    private var allRows: List<ResourceRow> = emptyList()

    /** Binds the screen to a navigation key. Idempotent: re-entering the route keeps the state. */
    fun start(ref: ResourceRef) {
        val previous = resourceRef
        val session = app.session ?: return
        val first = previous == null || previous.key != ref.key
        resourceRef = ref
        if (first) {
            val remembered = app.rememberedNamespace(ref.key, session.profile.id)
            _namespace.value = remembered ?: defaultNamespace(ref)
        }
        if (first || allRows.isEmpty()) load(initial = true)
    }

    /**
     * Namespaced resources start in the profile's namespace (never an accidental cluster-wide read
     * that RBAC may refuse); cluster-scoped resources ignore the choice entirely.
     */
    private fun defaultNamespace(ref: ResourceRef): String? {
        if (ref.resolve(app.catalog)?.namespaced != true) return null
        return app.session?.profile?.namespace?.takeIf { it.isNotBlank() } ?: "default"
    }

    fun setNamespace(value: String?) {
        val ref = resourceRef ?: return
        if (value == _namespace.value) return
        _namespace.value = value
        app.rememberNamespace(ref.key, app.session?.profile?.id, value)
        load(initial = true)
    }

    fun setSearch(value: String) {
        _search.value = value
        savedState[KEY_SEARCH] = value
        applyFilter()
    }

    fun setSelector(value: String) {
        _selector.value = value
        savedState[KEY_SELECTOR] = value
    }

    fun setCreateYaml(value: String) {
        _createYaml.value = value
        savedState[KEY_CREATE_YAML] = value
    }

    fun refresh() = load(initial = false)

    fun retry() = load(initial = true)

    /** True when the current choice means "every namespace". */
    fun isAllNamespaces(): Boolean {
        val resource = _state.value.resource ?: return true
        return !resource.namespaced || _namespace.value == ALL_NAMESPACES
    }

    /* --------------------------------------------------------------------------------------- */
    /* Loading                                                                                 */
    /* --------------------------------------------------------------------------------------- */

    private fun load(initial: Boolean) {
        val ref = resourceRef ?: return
        val session = app.session
        if (session == null) {
            _state.value = ListUiState(error = message(R.string.error_missing_cluster))
            return
        }
        val resource = ref.resolve(app.catalog)
        if (resource == null) {
            _state.value = ListUiState(
                error = UiError(R.string.error_generic_title, "Unknown resource \"${ref.plural}\"", null),
            )
            return
        }
        val namespaceArg = namespaceArgument(resource)
        loadJob?.cancel()
        _state.value = _state.value.copy(
            loading = initial && allRows.isEmpty(),
            refreshing = !initial || allRows.isNotEmpty(),
            resource = resource,
            error = null,
            partial = false,
        )
        loadJob = viewModelScope.launch {
            val selector = _selector.value.takeIf { it.isNotBlank() }
            val direct = runCatching {
                app.repository.list(session, resource, namespaceArg, labelSelector = selector, limit = 500)
            }
            val outcome: Result<Pair<KubeList, Boolean>> = direct.map { it to false }.recoverCatching { error ->
                val clusterWide = resource.namespaced && namespaceArg == null
                val forbidden = error is KubeApiException && (error.isForbidden || error.isUnauthorized)
                if (!clusterWide || !forbidden) throw error
                listPerNamespace(session, resource, selector)
            }
            outcome
                .onSuccess { (list, partial) ->
                    allRows = list.items.map { it.toRow() }.sortedBy { it.namespace.orEmpty() + "\u0000" + it.name }
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        total = allRows.size,
                        error = null,
                        truncated = list.continueToken != null,
                        partial = partial,
                    )
                    applyFilter(allRows)
                    // Rows are already on screen; usage arrives separately so a slow or absent
                    // metrics-server never delays them.
                    if (resource.kind == "Pod") loadMetrics(session)
                }
                .onFailure { error ->
                    allRows = emptyList()
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        rows = emptyList(),
                        total = 0,
                        error = error.toUiError(),
                    )
                }
        }
    }

    /** Fallback for a user without cluster-wide list rights: read every visible namespace. */
    private suspend fun listPerNamespace(
        session: ClusterSession,
        resource: ApiResource,
        selector: String?,
    ): Pair<KubeList, Boolean> {
        val namespaces = app.namespaces.value.takeIf { it.isNotEmpty() } ?: session.namespaces
        if (namespaces.isEmpty()) throw KubeApiException(403, "Forbidden", "No namespaces are visible")
        val attempts = coroutineScope {
            namespaces.map { ns ->
                async {
                    runCatching {
                        app.repository.list(session, resource, ns, labelSelector = selector, limit = 500).items
                    }
                }
            }.awaitAll()
        }
        val items = attempts.flatMap { it.getOrDefault(emptyList()) }
        if (items.isEmpty() && attempts.all { it.isFailure }) {
            throw attempts.first().exceptionOrNull()!!
        }
        return KubeList(items, null, null, null) to attempts.any { it.isFailure }
    }

    private fun JsonObject.toRow(): ResourceRow = ResourceRow(
        uid = str("metadata/uid") ?: (resourceNamespace().orEmpty() + "|" + resourceName()),
        name = resourceName(),
        namespace = resourceNamespace(),
        object_ = this,
    )

    /**
     * Fills in per-pod CPU/memory. `Metrics.podMetrics` returns an empty map when the metrics.k8s.io
     * group is not served, so a cluster without metrics-server simply leaves [ListUiState.metrics]
     * empty and the rows render without a usage segment.
     */
    private suspend fun loadMetrics(session: ClusterSession) {
        val attempt = runCatching { Metrics.podMetrics(session.connection, app.catalog) }
        if (session !== app.session) return
        if (attempt.isSuccess) {
            _state.value = _state.value.copy(metrics = attempt.getOrThrow(), metricsError = null)
        } else {
            _state.value = _state.value.copy(metricsError = attempt.exceptionOrNull()?.toUiError())
        }
    }

    private fun applyFilter(rows: List<ResourceRow> = allRows) {
        val query = _search.value.trim().lowercase()
        val filtered = if (query.isEmpty()) rows else rows.filter { it.name.lowercase().contains(query) }
        _state.value = _state.value.copy(rows = filtered, total = rows.size)
    }

    private fun namespaceArgument(resource: ApiResource): String? {
        if (!resource.namespaced) return null
        val value = _namespace.value ?: return null
        return if (value == ALL_NAMESPACES) null else value
    }

    private fun message(res: Int): UiError {
        val text = app.getApplication<android.app.Application>().getString(res)
        return UiError(res, text, null)
    }

    /* --------------------------------------------------------------------------------------- */
    /* Mutations                                                                               */
    /* --------------------------------------------------------------------------------------- */

    fun delete(row: ResourceRow, gracePeriodSeconds: Long? = null, onDone: (String) -> Unit) {
        val session = app.session ?: return
        val resource = _state.value.resource ?: return
        viewModelScope.launch {
            app.repository.invalidate(session.profile.id)
            runCatching { app.repository.delete(session, resource, row.name, row.namespace, gracePeriodSeconds) }
                .onSuccess {
                    onDone(row.name)
                    load(initial = false)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    fun scale(row: ResourceRow, replicas: Int, onDone: (String) -> Unit) {
        val session = app.session ?: return
        val resource = _state.value.resource ?: return
        viewModelScope.launch {
            runCatching { app.repository.scale(session, resource, row.name, row.namespace, replicas) }
                .onSuccess {
                    onDone(row.name)
                    load(initial = false)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    fun rolloutRestart(row: ResourceRow, onDone: (String) -> Unit) {
        val session = app.session ?: return
        val resource = _state.value.resource ?: return
        viewModelScope.launch {
            runCatching { app.repository.rolloutRestart(session, resource, row.name, row.namespace) }
                .onSuccess {
                    onDone(row.name)
                    load(initial = false)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    /** The current create template, generated from the resource identity on first use. */
    fun createTemplate(): String {
        val resource = _state.value.resource ?: return ""
        val existing = _createYaml.value
        if (existing.isNotBlank()) return existing
        val template = minimalManifest(resource, namespaceArgument(resource))
        setCreateYaml(template)
        return template
    }

    fun submitCreate(onCreated: (String) -> Unit, onError: (Throwable) -> Unit) {
        val session = app.session ?: return
        val body = runCatching { YamlIo.parse(createYaml.value) as? JsonObject }.getOrNull()
        if (body == null) {
            onError(IllegalArgumentException("The YAML could not be parsed"))
            return
        }
        viewModelScope.launch {
            runCatching { app.repository.create(session, body) }
                .onSuccess {
                    val name = body.str("metadata/name").orEmpty()
                    setCreateYaml("")
                    onCreated(name)
                    load(initial = false)
                }
                .onFailure(onError)
        }
    }

    companion object {
        private const val KEY_SEARCH = "list.search"
        private const val KEY_SELECTOR = "list.selector"
        private const val KEY_CREATE_YAML = "list.createYaml"
    }
}
