package dev.rafa.kubemobile.ui.flux

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.bool
import dev.rafa.kubemobile.k8s.resourceName
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ops.GitOps
import dev.rafa.kubemobile.ops.Status
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.toUiError
import dev.rafa.kubemobile.ui.shortMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

/** The Flux kinds the dashboard looks for, in the order they are shown. */
enum class FluxSection(val kind: String, val group: String, val titleRes: Int) {
    KUSTOMIZATION("Kustomization", "kustomize.toolkit.fluxcd.io", dev.rafa.kubemobile.R.string.flux_kustomizations),
    HELM_RELEASE("HelmRelease", "helm.toolkit.fluxcd.io", dev.rafa.kubemobile.R.string.flux_helmreleases),
    GIT_REPOSITORY("GitRepository", "source.toolkit.fluxcd.io", dev.rafa.kubemobile.R.string.flux_sources),
    OCI_REPOSITORY("OCIRepository", "source.toolkit.fluxcd.io", dev.rafa.kubemobile.R.string.flux_sources),
    HELM_REPOSITORY("HelmRepository", "source.toolkit.fluxcd.io", dev.rafa.kubemobile.R.string.flux_sources),
    BUCKET("Bucket", "source.toolkit.fluxcd.io", dev.rafa.kubemobile.R.string.flux_sources),
}

/** CRD names the dashboard probed, shown verbatim when Flux is absent. */
val FLUX_CRD_NAMES = listOf(
    "kustomizations.kustomize.toolkit.fluxcd.io",
    "helmreleases.helm.toolkit.fluxcd.io",
    "gitrepositories.source.toolkit.fluxcd.io",
    "ocirepositories.source.toolkit.fluxcd.io",
    "helmrepositories.source.toolkit.fluxcd.io",
    "buckets.source.toolkit.fluxcd.io",
)

data class FluxRow(
    val kind: String,
    val group: String,
    val name: String,
    val namespace: String?,
    val health: dev.rafa.kubemobile.ops.ResourceHealth,
    val revision: String?,
    val url: String?,
    val interval: String?,
    val suspended: Boolean,
    val object_: JsonObject,
)

data class FluxSectionState(
    val section: FluxSection,
    val resource: ApiResource?,
    val rows: List<FluxRow>,
    /**
     * Set when the list request for this section failed (RBAC, timeout, ...). Kept per section so
     * one denied kind shows an honest error instead of blanking the whole dashboard, and so a 403
     * can never be mistaken for "not installed".
     */
    val error: UiError? = null,
    val partial: Boolean = false,
)

data class FluxUiState(
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val sections: List<FluxSectionState> = emptyList(),
    val error: UiError? = null,
    val installed: Boolean = false,
)

class FluxViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(FluxUiState())
    val state: StateFlow<FluxUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun start() {
        if (_state.value.sections.isEmpty()) load(initial = true)
    }

    fun refresh() = load(initial = false)

    fun retry() = load(initial = true)

    /** Reloads a single section after its error row was retried. */
    fun retrySection() = load(initial = false)

    /** A future rollout ("All namespaces") is honoured by the per-namespace fallback in `list`. */
    private fun load(initial: Boolean) {
        val session: ClusterSession = app.session ?: return
        loadJob?.cancel()
        _state.value = _state.value.copy(
            loading = initial && _state.value.sections.isEmpty(),
            refreshing = _state.value.sections.isNotEmpty(),
            error = null,
        )
        loadJob = viewModelScope.launch {
            val resolved = FluxSection.entries.map { section ->
                section to app.catalog.forKind(section.kind, section.group)
            }
            val installed = resolved.any { it.second != null }
            val sections = resolved.map { (section, resource) ->
                if (resource == null) {
                    // The CRD genuinely is not served: absence, not failure.
                    FluxSectionState(section, null, emptyList())
                } else {
                    val attempt = runCatching { listAcrossNamespaces(session, resource, section.kind) }
                    FluxSectionState(
                        section = section,
                        resource = resource,
                        rows = attempt.getOrNull()?.first.orEmpty(),
                        error = attempt.exceptionOrNull()?.toUiError(),
                        partial = attempt.getOrNull()?.second == true,
                    )
                }
            }
            _state.value = FluxUiState(
                loading = false,
                refreshing = false,
                sections = sections,
                error = null,
                installed = installed,
            )
        }
    }
    private suspend fun listAcrossNamespaces(
        session: ClusterSession,
        resource: ApiResource,
        kind: String,
    ): Pair<List<FluxRow>, Boolean> {
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
                val attempts = coroutineScope {
                    namespaces.map { ns ->
                        async { runCatching { app.repository.list(session, resource, ns, limit = 500).items } }
                    }.awaitAll()
                }
                if (attempts.all { it.isFailure }) throw attempts.first().exceptionOrNull()!!
                attempts.flatMap { it.getOrDefault(emptyList()) } to attempts.any { it.isFailure }
            }
        }
        val rows = items.map { item ->
            val objectHealth = Status.health(item, kind)
            FluxRow(
                kind = kind,
                group = resource.group,
                name = item.resourceName(),
                namespace = item.str("metadata/namespace"),
                health = objectHealth,
                revision = item.str("status/lastAppliedRevision")
                    ?: item.str("status/artifact/revision")
                    ?: item.str("status/lastAttemptedRevision"),
                url = item.str("spec/url")
                    ?: item.str("spec/secretRef/name")
                    ?: item.str("spec/sourceRef/name"),
                interval = item.str("spec/interval"),
                suspended = item.bool("spec/suspend") == true,
                object_ = item,
            )
        }.sortedWith(compareBy({ it.namespace.orEmpty() }, { it.name }))
        return rows to partial
    }

    fun reconcile(row: FluxRow, withSource: Boolean, onDone: () -> Unit) {
        val session = app.session ?: return
        val resource = app.catalog.forKind(row.kind, row.group)
            ?: row.let { app.catalog.forResource(it.kind.lowercase() + "s", it.group) }
            ?: return
        val namespace = row.namespace.orEmpty()
        viewModelScope.launch {
            runCatching { GitOps.reconcile(session.connection, resource, row.name, namespace, withSource) }
                .onSuccess {
                    onDone()
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(dev.rafa.kubemobile.R.string.detail_reconcile_done),
                    )
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    fun setSuspended(row: FluxRow, suspended: Boolean, onDone: () -> Unit) {
        val session = app.session ?: return
        val resource = app.catalog.forKind(row.kind, row.group) ?: return
        val namespace = row.namespace.orEmpty()
        viewModelScope.launch {
            runCatching { GitOps.setSuspended(session.connection, resource, row.name, namespace, suspended) }
                .onSuccess {
                    onDone()
                    load(initial = false)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }
}
