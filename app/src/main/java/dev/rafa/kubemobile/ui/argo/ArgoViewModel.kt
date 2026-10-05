package dev.rafa.kubemobile.ui.argo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.bool
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.resourceName
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ops.GitOps
import dev.rafa.kubemobile.ops.ResourceHealth
import dev.rafa.kubemobile.ops.Status
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

val ARGO_CRD_NAMES = listOf(
    "applications.argoproj.io",
    "applicationsets.argoproj.io",
)

data class ArgoRow(
    val kind: String,
    val name: String,
    val namespace: String?,
    val health: ResourceHealth,
    val sync: String?,
    val targetRevision: String?,
    val destination: String?,
    val lastOperation: String?,
    val autoSync: Boolean,
    val object_: JsonObject,
)

data class ArgoUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val applications: List<ArgoRow> = emptyList(),
    val applicationSets: List<ArgoRow> = emptyList(),
    val installed: Boolean = false,
    val error: UiError? = null,
    /**
     * Failure of the Applications request, kept separate from [applicationSetsError] so one denied
     * kind reports itself honestly rather than blanking the dashboard — and so an RBAC failure is
     * never rendered as "Argo CD is not installed".
     */
    val applicationsError: UiError? = null,
    val applicationSetsError: UiError? = null,
    val applicationsPartial: Boolean = false,
    val applicationSetsPartial: Boolean = false,
)

class ArgoViewModel(
    private val app: AppViewModel,
) : ViewModel() {

    private val _state = MutableStateFlow(ArgoUiState())
    val state: StateFlow<ArgoUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun start() {
        if (_state.value.applications.isEmpty() && _state.value.applicationSets.isEmpty()) load(initial = true)
    }

    fun refresh() = load(initial = false)

    fun retry() = load(initial = true)

    private fun load(initial: Boolean) {
        val session: ClusterSession = app.session ?: return
        loadJob?.cancel()
        _state.value = _state.value.copy(
            loading = initial && _state.value.applications.isEmpty(),
            refreshing = !initial,
            error = null,
        )
        loadJob = viewModelScope.launch {
            val appsResource = app.catalog.forKind("Application", "argoproj.io")
            val setsResource = app.catalog.forKind("ApplicationSet", "argoproj.io")
            val appsAttempt = appsResource?.let { resource -> runCatching { listRows(session, resource) } }
            val setsAttempt = setsResource?.let { resource -> runCatching { listRows(session, resource) } }
            _state.value = ArgoUiState(
                loading = false,
                refreshing = false,
                applications = appsAttempt?.getOrNull()?.first.orEmpty(),
                applicationSets = setsAttempt?.getOrNull()?.first.orEmpty(),
                installed = appsResource != null || setsResource != null,
                error = null,
                applicationsError = appsAttempt?.exceptionOrNull()?.toUiError(),
                applicationSetsError = setsAttempt?.exceptionOrNull()?.toUiError(),
                applicationsPartial = appsAttempt?.getOrNull()?.second == true,
                applicationSetsPartial = setsAttempt?.getOrNull()?.second == true,
            )
        }
    }

    private suspend fun listRows(session: ClusterSession, resource: ApiResource): Pair<List<ArgoRow>, Boolean> {
        val namespaces = app.namespaces.value.takeIf { it.isNotEmpty() } ?: session.namespaces
        val (items, partial) = if (!resource.namespaced) {
            app.repository.list(session, resource, null, limit = 500).items to false
        } else {
            val clusterWide = runCatching { app.repository.list(session, resource, null, limit = 500) }
            val direct = clusterWide.getOrNull()
            if (direct != null) {
                direct.items to false
            } else {
                if (namespaces.isEmpty()) throw clusterWide.exceptionOrNull()!!
                val attempts = namespaces.map { ns ->
                    runCatching { app.repository.list(session, resource, ns, limit = 500).items }
                }
                if (attempts.all { it.isFailure }) throw attempts.first().exceptionOrNull()!!
                attempts.flatMap { it.getOrDefault(emptyList()) } to attempts.any { it.isFailure }
            }
        }
        return Pair(items.map { item ->
            ArgoRow(
                kind = resource.kind,
                name = item.resourceName(),
                namespace = item.str("metadata/namespace"),
                health = Status.health(item, resource.kind),
                sync = item.str("status/sync/status"),
                targetRevision = item.str("spec/source/targetRevision")
                    ?: item.str("spec/template/spec/source/targetRevision"),
                destination = listOfNotNull(
                    item.str("spec/destination/namespace"),
                    item.str("spec/destination/server"),
                ).joinToString(" ").ifBlank { null },
                lastOperation = item.str("status/operationState/phase")
                    ?: item.str("status/operationState/finishedAt"),
                // Argo CD models `automated` as an OBJECT (`{prune, selfHeal}`), not a boolean, so
                // its PRESENCE is what enables automated sync - `bool()` returns null for it and the
                // older `bool(...) != null` test therefore never matched a real Application.
                // The path is walked with `objAt` (a `JsonObject` also implements `Map`, so a
                // slash-containing key must not be indexed directly) and only the flat key is
                // indexed. An explicit `false` still means disabled.
                autoSync = item.objAt("spec/syncPolicy")
                    ?.get("automated")
                    ?.let { it.toString() != "false" } == true,
                object_ = item,
            )
        }.sortedWith(compareBy({ it.namespace.orEmpty() }, { it.name })), partial)
    }

    fun refreshApplication(row: ArgoRow, hard: Boolean, onDone: () -> Unit) {
        val session = app.session ?: return
        val namespace = row.namespace.orEmpty()
        viewModelScope.launch {
            runCatching { GitOps.argocdRefresh(session.connection, row.name, namespace, hard) }
                .onSuccess {
                    onDone()
                    load(initial = false)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    fun syncApplication(row: ArgoRow, revision: String?, prune: Boolean, dryRun: Boolean, onDone: () -> Unit) {
        val session = app.session ?: return
        val namespace = row.namespace.orEmpty()
        viewModelScope.launch {
            runCatching { GitOps.argocdSync(session.connection, row.name, namespace, revision, prune, dryRun) }
                .onSuccess {
                    onDone()
                    load(initial = false)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    fun terminate(row: ArgoRow, onDone: () -> Unit) {
        val session = app.session ?: return
        val namespace = row.namespace.orEmpty()
        viewModelScope.launch {
            runCatching { GitOps.argocdTerminateOperation(session.connection, row.name, namespace) }
                .onSuccess {
                    onDone()
                    load(initial = false)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }
}
