package dev.rafa.kubemobile.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.KubeList
import dev.rafa.kubemobile.k8s.Metrics
import dev.rafa.kubemobile.k8s.ResourceUsage
import dev.rafa.kubemobile.k8s.YamlIo
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.resourceName
import dev.rafa.kubemobile.k8s.resourceNamespace
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ops.GitOps
import dev.rafa.kubemobile.ops.Status
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.ResourceRef
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.humanAge
import dev.rafa.kubemobile.ui.routeKey
import dev.rafa.kubemobile.ui.shortMessage
import dev.rafa.kubemobile.ui.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One container as rendered by the Containers tab. */
data class ContainerInfo(
    val name: String,
    val image: String,
    val ready: Boolean,
    val restarts: Long,
    val state: String,
    val stateDetail: String?,
    val startedAt: String?,
    val ports: List<Int>,
)

/** One ReplicaSet revision, as rendered by the ReplicaSets tab. */
data class ReplicaSetRow(
    val revision: Long,
    val name: String,
    val desired: Int,
    val current: Int,
    val ready: Int,
    val image: String?,
    val age: String?,
    val currentRevision: Boolean,
)

/** One Job owned by a CronJob, as rendered by the Jobs tab. */
data class JobRow(
    val name: String,
    val active: Int,
    val succeeded: Int,
    val failed: Int,
    val complete: Boolean,
    val age: String?,
)

/** One revision offered by the rollout-undo picker. */
data class RevisionOption(val revision: Long, val replicaSet: String, val age: String?)

/**
 * Detail tabs. Every kind gets the first three; the rest appear only when the kind has them and the
 * tab would render something meaningful — an empty tab is never offered.
 */
enum class DetailTab {
    SUMMARY,
    YAML,
    EVENTS,
    PODS,
    CONTAINERS,
    CONTROLLER,
    REPLICASETS,
    JOBS,
}

class ObjectDetailViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _object = MutableStateFlow<JsonObject?>(null)
    val objectBody: StateFlow<JsonObject?> = _object.asStateFlow()

    private val _yaml = MutableStateFlow("")
    val yaml: StateFlow<String> = _yaml.asStateFlow()

    private val _yamlDirty = MutableStateFlow(false)
    val yamlDirty: StateFlow<Boolean> = _yamlDirty.asStateFlow()

    private val _yamlEditing = MutableStateFlow(false)
    val yamlEditing: StateFlow<Boolean> = _yamlEditing.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<UiError?>(null)
    val error: StateFlow<UiError?> = _error.asStateFlow()

    private val _events = MutableStateFlow<KubeList?>(null)
    val events: StateFlow<KubeList?> = _events.asStateFlow()

    private val _eventsLoading = MutableStateFlow(false)
    val eventsLoading: StateFlow<Boolean> = _eventsLoading.asStateFlow()

    private val _eventsError = MutableStateFlow<UiError?>(null)
    val eventsError: StateFlow<UiError?> = _eventsError.asStateFlow()

    private val _containers = MutableStateFlow<List<ContainerInfo>>(emptyList())
    val containers: StateFlow<List<ContainerInfo>> = _containers.asStateFlow()

    private val _revisions = MutableStateFlow<List<RevisionOption>>(emptyList())
    val revisions: StateFlow<List<RevisionOption>> = _revisions.asStateFlow()

    /** ReplicaSet revisions of a Deployment, newest first, with the current one flagged. */
    private val _replicaSets = MutableStateFlow<List<ReplicaSetRow>>(emptyList())
    val replicaSets: StateFlow<List<ReplicaSetRow>> = _replicaSets.asStateFlow()

    private val _replicaSetsLoading = MutableStateFlow(false)
    val replicaSetsLoading: StateFlow<Boolean> = _replicaSetsLoading.asStateFlow()

    private val _replicaSetsError = MutableStateFlow<UiError?>(null)
    val replicaSetsError: StateFlow<UiError?> = _replicaSetsError.asStateFlow()

    /** The pods this object owns, shared by the Pods tab and the merged log view. */
    private val _pods = MutableStateFlow<List<PodInfo>>(emptyList())
    val pods: StateFlow<List<PodInfo>> = _pods.asStateFlow()

    private val _podsLoading = MutableStateFlow(false)
    val podsLoading: StateFlow<Boolean> = _podsLoading.asStateFlow()

    private val _podsError = MutableStateFlow<UiError?>(null)
    val podsError: StateFlow<UiError?> = _podsError.asStateFlow()

    /** The Jobs a CronJob owns, newest first. */
    private val _jobs = MutableStateFlow<List<JobRow>>(emptyList())
    val jobs: StateFlow<List<JobRow>> = _jobs.asStateFlow()

    private val _jobsLoading = MutableStateFlow(false)
    val jobsLoading: StateFlow<Boolean> = _jobsLoading.asStateFlow()

    private val _jobsError = MutableStateFlow<UiError?>(null)
    val jobsError: StateFlow<UiError?> = _jobsError.asStateFlow()

    /** Per-pod usage; errors are distinct from a missing metrics API. */
    private val _usage = MutableStateFlow<ResourceUsage?>(null)
    val usage: StateFlow<ResourceUsage?> = _usage.asStateFlow()
    private val _usageError = MutableStateFlow<UiError?>(null)
    val usageError: StateFlow<UiError?> = _usageError.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var ref: ResourceRef? = null
    private var namespace: String? = null
    private var objectName: String = ""
    private var loadJob: Job? = null

    val resource: ApiResource? get() = ref?.resolve(app.catalog)

    /** Flux kinds share the reconcile/suspend contract. */
    val isFlux: Boolean
        get() = resource?.group?.let { group ->
            group == "kustomize.toolkit.fluxcd.io" ||
                group == "helm.toolkit.fluxcd.io" ||
                group == "source.toolkit.fluxcd.io"
        } == true

    val isArgoApplication: Boolean
        get() = resource?.group == "argoproj.io" && resource?.kind == "Application"

    val isPod: Boolean get() = resource?.kind == "Pod"

    val kind: String get() = resource?.kind ?: ""

    /** Kinds whose pods this tab can enumerate, so the Pods tab is worth offering. */
    val hasPodsTab: Boolean
        get() = kind in setOf(
            "Deployment",
            "ReplicaSet",
            "ReplicationController",
            "StatefulSet",
            "DaemonSet",
            "Job",
            "CronJob",
            "Node",
        )

    /** Kinds whose logs are a merge of several pods. */
    val hasMergedLogs: Boolean get() = kind in WORKLOAD_LOG_KINDS

    /** The ReplicaSets tab belongs to a Deployment alone; that is where revisions live. */
    val hasReplicaSetsTab: Boolean get() = kind == "Deployment"

    val hasJobsTab: Boolean get() = kind == "CronJob"

    /**
     * The tabs this object actually offers, in order. Anything that would render an empty pane is
     * left out entirely rather than shown with a placeholder.
     */
    fun tabs(): List<DetailTab> = buildList {
        add(DetailTab.SUMMARY)
        add(DetailTab.YAML)
        add(DetailTab.EVENTS)
        if (hasPodsTab) add(DetailTab.PODS)
        if (isPod) add(DetailTab.CONTAINERS)
        add(DetailTab.CONTROLLER)
        if (hasReplicaSetsTab) add(DetailTab.REPLICASETS)
        if (hasJobsTab) add(DetailTab.JOBS)
    }

    fun start(resourceRef: ResourceRef, namespaceArg: String?, name: String) {
        val changed = ref?.key != resourceRef.key || this.namespace != namespaceArg || objectName != name
        ref = resourceRef
        namespace = namespaceArg
        objectName = name
        if (changed) {
            _yamlEditing.value = false
            _yamlDirty.value = false
            _events.value = null
            _revisions.value = emptyList()
            _replicaSets.value = emptyList()
            _pods.value = emptyList()
            _podsError.value = null
            _jobs.value = emptyList()
            _jobsError.value = null
            load()
        }
    }

    fun load() {
        val session = app.session ?: run {
            _loading.value = false
            _error.value = message(R.string.error_missing_cluster)
            return
        }
        val resource = resource ?: run {
            _loading.value = false
            _error.value = message(R.string.error_generic_title)
            return
        }
        loadJob?.cancel()
        _loading.value = true
        _error.value = null
        loadJob = viewModelScope.launch {
            val result = runCatching { app.repository.get(session, resource, objectName, namespace) }
            result
                .onSuccess { body ->
                    _object.value = body
                    _containers.value = containersOf(body)
                    if (!_yamlDirty.value) {
                        _yaml.value = runCatching { YamlIo.toYaml(body) }.getOrDefault("")
                    }
                    _loading.value = false
                    if (resource.kind == "Deployment") loadRevisions()
                    _usage.value = if (resource.kind == "Pod") loadUsage(session, body) else null
                }
                .onFailure {
                    _loading.value = false
                    _error.value = it.toUiError()
                }
        }
    }

    fun loadEvents(force: Boolean = false) {
        val session = app.session ?: return
        val resource = resource ?: return
        if (_events.value != null && !force) return
        _eventsLoading.value = true
        _eventsError.value = null
        viewModelScope.launch {
            runCatching {
                app.repository.eventsFor(session, namespace, resource.kind, objectName, limit = 200)
            }
                .onSuccess {
                    _events.value = it
                    _eventsLoading.value = false
                }
                .onFailure {
                    _eventsError.value = it.toUiError()
                    _eventsLoading.value = false
                }
        }
    }

    fun refreshYaml() {
        val session = app.session ?: return
        val resource = resource ?: return
        viewModelScope.launch {
            runCatching { app.repository.rawYaml(session, resource, objectName, namespace) }
                .onSuccess {
                    _yaml.value = it
                    _yamlDirty.value = false
                    _yamlEditing.value = false
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    fun editYaml(value: String) {
        _yaml.value = value
        _yamlDirty.value = true
    }

    fun setEditing(editing: Boolean) {
        _yamlEditing.value = editing
    }

    fun discardYamlChanges() {
        _yamlEditing.value = false
        _yamlDirty.value = false
        refreshYaml()
    }

    /**
     * Parses the editor buffer and verifies it still describes the object being viewed; a document
     * that renames or re-kinds the object would silently create something else, so it is refused.
     */
    fun planApply(): Result<JsonObject> {
        val parsed = runCatching { YamlIo.parse(_yaml.value) as? JsonObject }
            .getOrElse { return Result.failure(it) }
        if (parsed == null) return Result.failure(IllegalArgumentException("The YAML could not be parsed"))
        val current = _object.value
        val kind = parsed.str("kind").orEmpty()
        val name = parsed.str("metadata/name").orEmpty()
        val expectedKind = current?.str("kind").orEmpty()
        val expectedName = current?.str("metadata/name").orEmpty()
        if (kind != expectedKind || name != expectedName) {
            return Result.failure(
                ApplyMismatch(expectedKind, expectedName, kind, name),
            )
        }
        return Result.success(parsed)
    }

    fun apply(parsed: JsonObject) {
        val session = app.session ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { app.repository.replace(session, parsed) }
                .onSuccess { body ->
                    _busy.value = false
                    _object.value = body
                    _yaml.value = runCatching { YamlIo.toYaml(body) }.getOrDefault(_yaml.value)
                    _yamlDirty.value = false
                    _yamlEditing.value = false
                    _containers.value = containersOf(body)
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.detail_apply_ok, parsed.str("metadata/name").orEmpty()),
                    )
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun delete(gracePeriodSeconds: Long? = null, onDeleted: () -> Unit) {
        val session = app.session ?: return
        val resource = resource ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { app.repository.delete(session, resource, objectName, namespace, gracePeriodSeconds) }
                .onSuccess {
                    _busy.value = false
                    onDeleted()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun scale(replicas: Int, onDone: () -> Unit) {
        val session = app.session ?: return
        val resource = resource ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { app.repository.scale(session, resource, objectName, namespace, replicas) }
                .onSuccess {
                    _busy.value = false
                    onDone()
                    load()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun rolloutRestart() {
        val session = app.session ?: return
        val resource = resource ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { app.repository.rolloutRestart(session, resource, objectName, namespace) }
                .onSuccess {
                    _busy.value = false
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.list_restart_done, objectName),
                    )
                    load()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun rolloutUndo(toRevision: Long?, onDone: (String) -> Unit) {
        val session = app.session ?: return
        val resource = resource ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching {
                GitOps.rolloutUndo(app.repository, session, resource, objectName, namespace.orEmpty(), toRevision)
            }
                .onSuccess {
                    _busy.value = false
                    onDone(it)
                    load()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun setSuspended(suspended: Boolean) {
        val session = app.session ?: return
        val resource = resource ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching {
                GitOps.setSuspended(session.connection, resource, objectName, namespace.orEmpty(), suspended)
            }
                .onSuccess {
                    _busy.value = false
                    app.notify(
                        app.getApplication<android.app.Application>().getString(
                            R.string.detail_suspend_done,
                            if (suspended) {
                                app.getApplication<android.app.Application>().getString(R.string.action_suspend)
                            } else {
                                app.getApplication<android.app.Application>().getString(R.string.action_resume)
                            },
                        ),
                    )
                    load()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun reconcile(withSource: Boolean) {
        val session = app.session ?: return
        val resource = resource ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching {
                GitOps.reconcile(session.connection, resource, objectName, namespace.orEmpty(), withSource)
            }
                .onSuccess {
                    _busy.value = false
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.detail_reconcile_done),
                    )
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun argoRefresh(hard: Boolean) {
        val session = app.session ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { GitOps.argocdRefresh(session.connection, objectName, namespace.orEmpty(), hard) }
                .onSuccess {
                    _busy.value = false
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.detail_argo_refresh),
                    )
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun argoSync(revision: String?, prune: Boolean, dryRun: Boolean) {
        val session = app.session ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching {
                GitOps.argocdSync(session.connection, objectName, namespace.orEmpty(), revision, prune, dryRun)
            }
                .onSuccess {
                    _busy.value = false
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.detail_argo_sync_done),
                    )
                    load()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    fun argoTerminate() {
        val session = app.session ?: return
        _busy.value = true
        viewModelScope.launch {
            runCatching { GitOps.argocdTerminateOperation(session.connection, objectName, namespace.orEmpty()) }
                .onSuccess {
                    _busy.value = false
                    app.notify(
                        app.getApplication<android.app.Application>()
                            .getString(R.string.detail_argo_terminated),
                    )
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    /* --------------------------------------------------------------------------------------- */

    /** Loads whichever extra tabs this kind offers, each with its own error surface. */
    fun loadTab(tab: DetailTab) {
        when (tab) {
            DetailTab.PODS -> loadPods()
            DetailTab.REPLICASETS -> loadReplicaSets()
            DetailTab.JOBS -> loadJobs()
            else -> Unit
        }
    }

    /**
     * The pods this object owns, resolved through the shared workload resolver so the Pods tab and
     * the merged log view can never disagree.
     */
    fun loadPods(force: Boolean = false) {
        if (!hasPodsTab) return
        val session = app.session ?: return
        if (_pods.value.isNotEmpty() && !force) return
        _podsLoading.value = true
        _podsError.value = null
        viewModelScope.launch {
            val resource = resource ?: return@launch
            runCatching {
                if (resource.kind == "Node") podsOnNode(session) else resolveWorkloadPods(
                    app,
                    resource.kind,
                    objectName,
                    namespace,
                )
            }
                .onSuccess {
                    _pods.value = it.map(::podInfo)
                    _podsLoading.value = false
                }
                .onFailure {
                    _podsError.value = it.toUiError()
                    _podsLoading.value = false
                }
        }
    }

    /** Pods bound to a Node are found by field selector, which is a server-side filter. */
    private suspend fun podsOnNode(session: ClusterSession): List<JsonObject> {
        val podsResource = app.catalog.forResource("pods", "") ?: error("Pods are not served by this cluster")
        return app.repository.list(
            session,
            podsResource,
            // A cluster-scoped object has no namespace of its own, and its pods live in every
            // namespace, so this list is deliberately namespace-agnostic.
            namespace = null,
            fieldSelector = "spec.nodeName=$objectName",
            limit = 500,
        ).items.let(::orderForStreaming)
    }

    /**
     * The Deployment's ReplicaSets, newest revision first. Reuses the same fetch the rollout-undo
     * picker needs, so the two views can never show different revisions.
     */
    fun loadReplicaSets(force: Boolean = false) {
        if (!hasReplicaSetsTab) return
        val session = app.session ?: return
        if (_replicaSets.value.isNotEmpty() && !force) return
        val deployment = _object.value ?: return
        _replicaSetsLoading.value = true
        _replicaSetsError.value = null
        viewModelScope.launch {
            val replicaSets = app.catalog.forResource("replicasets", "apps")
            val rows = runCatching {
                val selector = selectorOf(deployment)
                    ?: return@runCatching emptyList<ReplicaSetRow>()
                val currentRevision = deployment.annotation(REVISION_ANNOTATION)?.toLongOrNull()
                val list = app.repository.list(
                    session,
                    replicaSets ?: return@runCatching emptyList<ReplicaSetRow>(),
                    namespace,
                    labelSelector = selector,
                    limit = 200,
                )
                list.items.mapNotNull { rs ->
                    val revision = rs.annotation(REVISION_ANNOTATION)?.toLongOrNull()
                        ?: return@mapNotNull null
                    ReplicaSetRow(
                        revision = revision,
                        name = rs.resourceName(),
                        desired = rs.long("spec/replicas")?.toInt() ?: 0,
                        current = rs.long("status/replicas")?.toInt() ?: 0,
                        ready = rs.long("status/readyReplicas")?.toInt() ?: 0,
                        image = rs.arrayAt("spec/template/spec/containers")
                            ?.firstOrNull()
                            ?.let { (it as? JsonObject)?.str("image") },
                        age = humanAge(rs.str("metadata/creationTimestamp")),
                        // The API server stamps the live revision on the Deployment itself, so the
                        // highlight follows the real rollout rather than whichever RS is newest.
                        currentRevision = currentRevision != null && revision == currentRevision,
                    )
                }.sortedByDescending { it.revision }
            }.getOrElse {
                _replicaSetsError.value = it.toUiError()
                emptyList()
            }
            _replicaSets.value = rows
            _replicaSetsLoading.value = false
        }
    }

    /** The Jobs a CronJob owns, newest first. */
    fun loadJobs(force: Boolean = false) {
        if (!hasJobsTab) return
        val session = app.session ?: return
        if (_jobs.value.isNotEmpty() && !force) return
        _jobsLoading.value = true
        _jobsError.value = null
        viewModelScope.launch {
            val jobsResource = app.catalog.forResource("jobs", "batch")
            if (jobsResource == null) {
                _jobsLoading.value = false
                return@launch
            }
            runCatching {
                val uid = _object.value?.str("metadata/uid")
                app.repository.list(session, jobsResource, namespace, limit = 500).items
                    .filter { ownedBy(it, objectName, "CronJob", uid) }
                    .map { job ->
                        JobRow(
                            name = job.resourceName(),
                            active = job.long("status/active")?.toInt() ?: 0,
                            succeeded = job.long("status/succeeded")?.toInt() ?: 0,
                            failed = job.long("status/failed")?.toInt() ?: 0,
                            complete = job.arrayAt("status/conditions")
                                ?.any { (it as? JsonObject)?.str("type") == "Complete" } == true,
                            age = humanAge(job.str("metadata/creationTimestamp")),
                        )
                    }
                    .sortedByDescending { it.name }
            }
                .onSuccess {
                    _jobs.value = it
                    _jobsLoading.value = false
                }
                .onFailure {
                    _jobsError.value = it.toUiError()
                    _jobsLoading.value = false
                }
        }
    }

    /** ReplicaSet history for the undo picker, newest first. */
    private suspend fun loadRevisions() {
        val session = app.session ?: return
        val resource = resource ?: return
        val deployment = _object.value ?: return
        val replicaSets = app.catalog.forResource("replicasets", "apps") ?: return
        val selector = selectorOf(deployment) ?: return
        val list = runCatching {
            app.repository.list(session, replicaSets, namespace, labelSelector = selector, limit = 200)
        }.getOrNull() ?: return
        _revisions.value = list.items.mapNotNull { rs ->
            val revision = rs.annotation(REVISION_ANNOTATION)?.toLongOrNull()
                ?: return@mapNotNull null
            RevisionOption(
                revision = revision,
                replicaSet = rs.resourceName(),
                age = humanAge(rs.str("metadata/creationTimestamp")),
            )
        }.sortedByDescending { it.revision }
    }

    private fun containersOf(body: JsonObject): List<ContainerInfo> {
        val specs = body.arrayAt("spec/containers")?.mapNotNull { it as? JsonObject }.orEmpty()
        val statuses = body.arrayAt("status/containerStatuses")?.mapNotNull { it as? JsonObject }.orEmpty()
        val statusByName = statuses.associateBy { it.str("name").orEmpty() }
        return specs.map { spec ->
            val name = spec.str("name").orEmpty()
            val status = statusByName[name]
            val waiting = status?.objAt("state/waiting")
            val terminated = status?.objAt("state/terminated")
            val running = status?.objAt("state/running")
            val state = when {
                waiting != null -> "Waiting"
                terminated != null -> "Terminated"
                running != null -> "Running"
                else -> "Unknown"
            }
            ContainerInfo(
                name = name,
                image = spec.str("image").orEmpty(),
                ready = status?.let { it["ready"].toString() == "true" } ?: false,
                restarts = status?.long("restartCount") ?: 0L,
                state = state,
                stateDetail = when {
                    waiting != null -> listOfNotNull(
                        waiting.str("reason"),
                        waiting.str("message"),
                    ).joinToString(" — ").ifBlank { null }

                    terminated != null -> listOfNotNull(
                        terminated.str("reason"),
                        terminated.long("exitCode")?.let { "exit code $it" },
                    ).joinToString(" — ").ifBlank { null }

                    else -> null
                },
                startedAt = status?.str("state/running/startedAt")
                    ?: status?.str("state/terminated/startedAt"),
                ports = spec.arrayAt("ports")
                    ?.mapNotNull { (it as? JsonObject)?.long("containerPort")?.toInt() }
                    .orEmpty(),
            )
        }
    }

    /** Pod container ports for the port-forward screen. */
    fun containerPorts(): List<Triple<String, Int, String>> {
        val body = _object.value ?: return emptyList()
        return body.arrayAt("spec/containers")?.mapNotNull { it as? JsonObject }.orEmpty()
            .flatMap { container ->
                val containerName = container.str("name").orEmpty()
                container.arrayAt("ports")?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { port ->
                    val number = port.long("containerPort")?.toInt() ?: return@mapNotNull null
                    Triple(containerName, number, port.str("protocol") ?: "TCP")
                }
            }
    }

    fun conditions() = _object.value?.let { Status.conditions(it) }.orEmpty()

    fun detailRows() = _object.value?.let { body ->
        Status.detailRows(body, resource?.kind.orEmpty())
    }.orEmpty()

    /** Labels and annotations of the live object, sorted for stable rendering. */
    fun labels(): List<Pair<String, String>> = _object.value?.objAt("metadata/labels")
        ?.entries
        ?.map { (key, value) -> key to (value as? JsonPrimitive)?.content.orEmpty() }
        ?.sortedBy { it.first }
        .orEmpty()

    fun annotations(): List<Pair<String, String>> = _object.value?.objAt("metadata/annotations")
        ?.entries
        ?.map { (key, value) -> key to (value as? JsonPrimitive)?.content.orEmpty() }
        ?.sortedBy { it.first }
        .orEmpty()

    /** `ownerReferences[]` plus the `created-by` annotation controllers such as Argo CD leave. */
    fun owners(): List<Triple<String, String, Boolean>> {
        val body = _object.value ?: return emptyList()
        val references = body.arrayAt("metadata/ownerReferences")
            ?.mapNotNull { it as? JsonObject }
            ?.map { ref ->
                Triple(
                    ref.str("kind").orEmpty(),
                    ref.str("name").orEmpty(),
                    ref["controller"].toString() == "true",
                )
            }
            .orEmpty()
        return references
    }

    fun createdBy(): String? = _object.value?.objAt("metadata/annotations")
        ?.entries
        ?.firstOrNull { (key, _) -> key.contains("created-by") }
        ?.let { (key, value) -> "$key = ${(value as? JsonPrimitive)?.content.orEmpty()}" }

    /**
     * The controlling spec that explains how the object behaves — update strategy, revision cap,
     * sync policy, taints and the like. Rendered as labelled rows so it reads in the same visual
     * language as Summary. Only rows that exist are emitted; nothing is invented.
     */
    fun controllerRows(): List<Pair<String, String>> {
        val body = _object.value ?: return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        fun put(labelRes: Int, value: String?) {
            value?.takeIf { it.isNotBlank() }?.let { rows += label(labelRes) to it }
        }
        fun putLong(labelRes: Int, value: Long?, suffix: String = "") {
            value?.let { rows += label(labelRes) to "$it$suffix" }
        }
        fun putBool(labelRes: Int, value: Boolean) {
            rows += label(labelRes) to label(if (value) R.string.string_yes else R.string.string_no)
        }

        when (kind) {
            "Deployment" -> {
                body.objAt("spec/strategy")?.let { strategy ->
                    put(R.string.label_strategy_type, strategy.str("type"))
                    strategy.objAt("rollingUpdate")?.let { roll ->
                        put(R.string.label_max_surge, roll.scalar("maxSurge"))
                        put(R.string.label_max_unavailable, roll.scalar("maxUnavailable"))
                    }
                }
                putLong(R.string.label_revision_history, body.long("spec/revisionHistoryLimit"))
                putLong(R.string.label_progress_deadline, body.long("spec/progressDeadlineSeconds"), "s")
                putLong(R.string.label_min_ready, body.long("spec/minReadySeconds"), "s")
            }

            "StatefulSet" -> {
                put(R.string.label_strategy_type, body.str("spec/updateStrategy/type"))
                putLong(R.string.label_revision_history, body.long("spec/revisionHistoryLimit"))
                put(R.string.label_pod_management, body.str("spec/podManagementPolicy"))
                putLong(R.string.label_min_ready, body.long("spec/minReadySeconds"), "s")
            }

            "DaemonSet" -> {
                put(R.string.label_strategy_type, body.str("spec/updateStrategy/type"))
                put(R.string.label_max_unavailable, body.objAt("spec/updateStrategy/rollingUpdate")?.scalar("maxUnavailable"))
                putLong(R.string.label_revision_history, body.long("spec/revisionHistoryLimit"))
                putLong(R.string.label_min_ready, body.long("spec/minReadySeconds"), "s")
            }

            "ReplicaSet", "ReplicationController" -> {
                putLong(R.string.label_revision_history, body.long("spec/revisionHistoryLimit"))
            }

            "Job" -> {
                putLong(R.string.label_completion, body.long("spec/completions"))
                putLong(R.string.label_parallelism, body.long("spec/parallelism"))
                putLong(R.string.label_backoff_limit, body.long("spec/backoffLimit"))
            }

            "CronJob" -> {
                put(R.string.label_schedule, body.str("spec/schedule"))
                put(R.string.label_concurrency_policy, body.str("spec/concurrencyPolicy"))
                putBool(R.string.label_suspend, body["spec/suspend"].toString() == "true")
                putLong(R.string.label_completion, body.long("spec/jobTemplate/spec/completions"))
                putLong(R.string.label_parallelism, body.long("spec/jobTemplate/spec/parallelism"))
            }

            "Node" -> {
                putBool(R.string.label_schedulable, !isNodeUnschedulable())
                val taints = taints()
                if (taints.isEmpty()) {
                    rows += label(R.string.label_taints) to label(R.string.action_none)
                } else {
                    taints.forEachIndexed { index, taint ->
                        rows += (if (index == 0) label(R.string.label_taints) else "") to taint
                    }
                }
            }
        }

        if (isArgoApplication) {
            body.objAt("spec/syncPolicy")?.let { policy ->
                // `automated` is a JSON object ({prune, selfHeal}), never a bare boolean; it is
                // absent when the application is manual-only. Both cases are stated explicitly.
                val automated = policy["automated"]
                val automatedOn = automated != null && automated.toString() != "null"
                rows += label(R.string.label_sync_policy) to
                    label(if (automatedOn) R.string.argo_autosync else R.string.label_manual)
            }
            body.objAt("status/operationState")?.let { state ->
                put(R.string.label_operation_state, state.str("phase"))
                put(R.string.label_message, state.str("message"))
            }
        }

        if (isFlux) {
            putBool(R.string.label_suspend, suspendState())
            put(R.string.label_interval, body.str("spec/interval"))
            body["spec/prune"]?.let { prune ->
                putBool(R.string.label_prune, prune.toString() == "true")
            }
            put(R.string.label_target_namespace, body.str("spec/targetNamespace"))
        }

        selectorOf(body)?.let { rows += label(R.string.label_selector) to it }
        return rows
    }

    /** Node taints as `key=value:Effect`, empty when the node tolerates everything. */
    fun taints(): List<String> = _object.value?.arrayAt("spec/taints")
        ?.mapNotNull { it as? JsonObject }
        ?.map { taint ->
            val key = taint.str("key").orEmpty()
            val value = taint.str("value")?.takeIf { it.isNotBlank() }?.let { "=$it" }.orEmpty()
            val effect = taint.str("effect").orEmpty()
            if (effect.isBlank()) "$key$value" else "$key$value:$effect"
        }
        .orEmpty()

    private fun label(res: Int): String =
        app.getApplication<android.app.Application>().getString(res)

    /** A JSON scalar as display text; `{}` and `[]` never reach the UI. */
    private fun JsonObject.scalar(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** Whether the object is currently suspended (Flux `spec.suspend`). */
    fun suspendState(): Boolean = _object.value?.objAt("spec")?.get("suspend").toString() == "true"

    /** Resolves the kind of an owner reference so the Controller tab can navigate to it. */
    fun ownerResource(kind: String): ApiResource? = app.catalog.forKind(kind)

    /** The navigation key of a resource kind, resolved from discovery rather than hand-written. */
    fun routeFor(plural: String, group: String? = null): String? =
        app.catalog.forResource(plural, group ?: "")?.routeKey()

    fun ownerNamespace(): String? = namespace

    /**
     * Per-pod usage from metrics-server. Returns null when the server is absent, so the detail
     * screen simply omits the section instead of showing zeroes or an error.
     */
    private suspend fun loadUsage(session: ClusterSession, body: JsonObject): ResourceUsage? {
        val podName = body.resourceName()
        val podNamespace = body.resourceNamespace() ?: namespace
        if (podName.isBlank() || podNamespace.isNullOrBlank()) return null
        return try {
            val all = Metrics.podMetrics(session.connection, app.catalog)
            _usageError.value = null
            all["$podNamespace/$podName"]
        } catch (error: Throwable) {
            _usageError.value = error.toUiError()
            null
        }
    }

    /** `status.allocatable` of a Node, read from the object itself (no extra API call). */
    fun nodeAllocatable(): List<Pair<String, String>> = nodeResourceMap("status/allocatable")

    /** `status.capacity` of a Node. */
    fun nodeCapacity(): List<Pair<String, String>> = nodeResourceMap("status/capacity")

    private fun nodeResourceMap(path: String): List<Pair<String, String>> {
        val obj = _object.value?.objAt(path) ?: return emptyList()
        val cpu = obj.str("cpu")
        val memory = obj.str("memory")
        val pods = obj.str("pods")
        return buildList {
            cpu?.let { add("CPU" to it) }
            memory?.let { add("Memory" to it) }
            pods?.let { add("Pods" to it) }
        }
    }

    /** True when this is a Node and the catalog exposes nodes, so the actions are offerable. */
    fun supportsNodeActions(): Boolean =
        resource?.kind == "Node" && app.catalog.forResource("nodes") != null

    fun isNodeUnschedulable(): Boolean = _object.value?.objAt("spec")?.get("unschedulable").toString() == "true"

    fun setNodeSchedulable(schedulable: Boolean, onDone: () -> Unit) {
        val session = app.session ?: return
        val name = objectName
        _busy.value = true
        viewModelScope.launch {
            runCatching { GitOps.setNodeSchedulable(app.repository, session, name, schedulable) }
                .onSuccess {
                    _busy.value = false
                    onDone()
                    load()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    /**
     * Evicts the node's pods through the `policy/v1` Eviction subresource, then reloads so
     * `spec.unschedulable` and the pod population reflect reality. PDB-protected pods reject
     * eviction, and [GitOps.drainNode] throws with the API server's own message in that case, which
     * is surfaced verbatim rather than as a generic failure.
     */
    fun drainNode(includeDaemonSets: Boolean, onDone: (Int) -> Unit) {
        val session = app.session ?: return
        val name = objectName
        _busy.value = true
        viewModelScope.launch {
            runCatching { GitOps.drainNode(app.repository, session, name, includeDaemonSets) }
                .onSuccess {
                    _busy.value = false
                    onDone(it)
                    load()
                }
                .onFailure {
                    _busy.value = false
                    app.notify(it.shortMessage())
                }
        }
    }

    private fun message(res: Int): UiError {
        val text = app.getApplication<android.app.Application>().getString(res)
        return UiError(res, text, null)
    }
}

/** Signals that the edited document no longer describes the object being viewed. */
class ApplyMismatch(
    val expectedKind: String,
    val expectedName: String,
    val actualKind: String,
    val actualName: String,
) : IllegalArgumentException("$actualKind/$actualName does not match $expectedKind/$expectedName")

