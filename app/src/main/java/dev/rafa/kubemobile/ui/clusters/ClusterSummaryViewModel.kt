package dev.rafa.kubemobile.ui.clusters

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.Metrics
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.k8s.ResourceUsage
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.bool
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ops.Status
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.JsonObject

/** One big-number tile on the summary grid. `null` renders as `—`, never as `0`. */
data class StatTile(
    val value: Long?,
    val total: Long? = null,
    val labelRes: Int,
)

/** A warning event surfaced by the attention card. */
data class AttentionEvent(
    val reason: String,
    val message: String,
    val involvedKind: String,
    val involvedName: String,
    val namespace: String?,
    val timestamp: String?,
)

data class ClusterSummaryState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: UiError? = null,
    /** `GET /version` -> `gitVersion`; null while loading or when the call was refused. */
    val serverVersion: String? = null,
    val nodesReady: Long? = null,
    val nodesTotal: Long? = null,
    val namespaces: Long? = null,
    val podsRunning: Long? = null,
    val podsTotal: Long? = null,
    val deploymentsAvailable: Long? = null,
    val deploymentsTotal: Long? = null,
    /** False when `metrics.k8s.io` is absent, which must render as an explicit state, not zeroes. */
    val metricsAvailable: Boolean = true,
    /** True when metrics-server exists but the read was refused or failed — distinct from absent. */
    val metricsError: Boolean = false,
    val usedCpuMillis: Long? = null,
    val usedMemoryBytes: Long? = null,
    val allocatableCpuMillis: Long? = null,
    val allocatableMemoryBytes: Long? = null,
    val notReadyPods: Long? = null,
    val unavailableDeployments: Long? = null,
    val failingKustomizations: Long? = null,
    val failingHelmReleases: Long? = null,
    val warningEvents: List<AttentionEvent> = emptyList(),
    /**
     * Labels of the cluster-wide health sources that could not be read this refresh. Non-empty means
     * the attention card is reporting on partial evidence and must say so rather than claim health.
     */
    val unavailableSources: List<Int> = emptyList(),
    /**
     * True once every health source has answered *successfully*. A source that is absent, refused or
     * unreachable leaves this false, because "we could not read it" is not "it is fine".
     */
    val healthResolved: Boolean = false,
) {
    val cpuFraction: Float?
        get() {
            val used = usedCpuMillis ?: return null
            val total = allocatableCpuMillis ?: return null
            if (total <= 0) return null
            return (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    val memoryFraction: Float?
        get() {
            val used = usedMemoryBytes ?: return null
            val total = allocatableMemoryBytes ?: return null
            if (total <= 0) return null
            return (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    /** Only a fully-read cluster with nothing wrong may say it is healthy. */
    val allHealthy: Boolean
        get() = healthResolved &&
            (notReadyPods ?: 0L) == 0L &&
            (unavailableDeployments ?: 0L) == 0L &&
            (failingKustomizations ?: 0L) == 0L &&
            (failingHelmReleases ?: 0L) == 0L &&
            warningEvents.isEmpty()
}

/**
 * Aggregates the cluster-wide numbers the summary screen shows. Everything is a real list call;
 * a resource the API server does not expose leaves its tile `null` (rendered `—`) rather than
 * pretending the count is zero.
 */
class ClusterSummaryViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(ClusterSummaryState())
    val state: StateFlow<ClusterSummaryState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun start() {
        if (_state.value.loading) load(initial = true)
    }

    fun refresh() = load(initial = false)

    fun retry() = load(initial = true)

    private fun load(initial: Boolean) {
        val session = app.session ?: run {
            _state.value = ClusterSummaryState(
                loading = false,
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
            loading = initial && _state.value.nodesTotal == null,
            refreshing = !initial,
            error = null,
        )
        loadJob = viewModelScope.launch {
            runCatching { gather(session) }
                .onSuccess { _state.value = it.copy(loading = false, refreshing = false) }
                .onFailure {
                    _state.value = _state.value.copy(
                        loading = false,
                        refreshing = false,
                        error = it.toUiError(),
                    )
                }
        }
    }

    /**
     * Every call is independent and best-effort: a 403 on Deployments must not blank the nodes
     * tile, so each is caught on its own. A failure is recorded as an unavailable source rather
     * than folded into a null, so the attention card can say "we could not read this" instead of
     * presenting silence as health.
     */
    private suspend fun gather(session: ClusterSession): ClusterSummaryState = coroutineScope {
        val versionDeferred = async { runCatching { serverVersion(session) }.getOrNull() }
        val nodesDeferred = async { listAll(session, "nodes", "", null) }
        val namespacesDeferred = async { listAll(session, "namespaces", "", null) }
        val podsDeferred = async { listAll(session, "pods", "", null) }
        val deploymentsDeferred = async { listAll(session, "deployments", "apps", null) }
        val eventsDeferred = async { listAll(session, "events", "", null) }

        val nodes = nodesDeferred.await()
        val namespaces = namespacesDeferred.await()
        val pods = podsDeferred.await()
        val deployments = deploymentsDeferred.await()
        val events = eventsDeferred.await()

        val nodeList = nodes.value
        val podList = pods.value
        val deploymentList = deployments.value

        val nodeReady = nodeList?.count { obj ->
            Status.conditions(obj).firstOrNull { it.type == "Ready" }?.isTrue == true
        }
        val podsRunning = podList?.count { obj ->
            obj.str("status/phase").equals("Running", true)
        }
        val notReady = podList?.count { obj ->
            val phase = obj.str("status/phase")
            val containers = obj.arrayAt("status/containerStatuses")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val ready = containers.count { it.bool("ready") == true }
            val succeeded = phase.equals("Succeeded", true)
            // A pod that has finished is not "attention": only a running-but-unready or a
            // non-succeeded, non-running pod counts.
            !succeeded && (phase != "Running" || ready < containers.size || containers.isEmpty())
        }
        val available = deploymentList?.count { obj ->
            val desired = obj.long("spec/replicas") ?: 1L
            val ready = obj.long("status/availableReplicas") ?: 0L
            // Scaled to zero on purpose is not a fault: it is available until it says otherwise.
            val failed = Status.conditions(obj).any { it.type == "ReplicaFailure" && it.isTrue }
            desired == 0L && !failed || (desired > 0L && ready >= desired)
        }

        val metricsAvailable = app.catalog.forResource("pods", "metrics.k8s.io") != null
        val usage = if (metricsAvailable) {
            supervisorScope {
                val podsUsage = async { runCatching { Metrics.podMetrics(session.connection, app.catalog) } }
                val nodeUsage = async { runCatching { Metrics.nodeMetrics(session.connection, app.catalog) } }
                podsUsage.await() to nodeUsage.await()
            }
        } else {
            Result.success(emptyMap<String, ResourceUsage>()) to Result.success(emptyMap<String, ResourceUsage>())
        }

        // Cluster usage is the sum of pod metrics: what the workloads actually consume. The
        // allocatable denominator is summed from `status.allocatable` on each node.
        val usedTotal = Metrics.sumUsage(usage.first.getOrDefault(emptyMap()).values)
        val allocatable = nodeAllocatable(nodeList)

        val kustomizations = listAll(session, "kustomizations", "kustomize.toolkit.fluxcd.io", null)
        val helmReleases = listAll(session, "helmreleases", "helm.toolkit.fluxcd.io", null)

        val warnings = events.value
            ?.filter { it.str("type").equals("Warning", true) }
            ?.sortedByDescending { it.str("lastTimestamp") ?: it.str("eventTime") ?: it.str("metadata/creationTimestamp") ?: "" }
            ?.take(WARNING_EVENT_LIMIT)
            ?.map { obj ->
                AttentionEvent(
                    reason = obj.str("reason").orEmpty().ifBlank { "Warning" },
                    message = obj.str("message").orEmpty(),
                    involvedKind = obj.str("involvedObject/kind").orEmpty(),
                    involvedName = obj.str("involvedObject/name").orEmpty(),
                    namespace = obj.str("involvedObject/namespace") ?: obj.str("metadata/namespace"),
                    timestamp = obj.str("lastTimestamp") ?: obj.str("eventTime"),
                )
            }
            .orEmpty()

        // Only the sources the attention card actually reports on decide "healthy". A refused or
        // absent source is named so the card can show it as unknown instead of green.
        val healthSources = listOf(
            R.string.summary_not_ready_pods to pods,
            R.string.summary_unavailable_deployments to deployments,
            R.string.summary_failing_kustomizations to kustomizations,
            R.string.summary_failing_helmreleases to helmReleases,
            R.string.summary_recent_warnings to events,
        )
        val unavailable = healthSources.filter { !it.second.resolved }.map { it.first }

        ClusterSummaryState(
            loading = false,
            serverVersion = versionDeferred.await(),
            nodesReady = nodeReady?.toLong(),
            nodesTotal = nodeList?.size?.toLong(),
            namespaces = namespaces.value?.size?.toLong(),
            podsRunning = podsRunning?.toLong(),
            podsTotal = podList?.size?.toLong(),
            deploymentsAvailable = available?.toLong(),
            deploymentsTotal = deploymentList?.size?.toLong(),
            metricsAvailable = metricsAvailable,
            metricsError = metricsAvailable && usage.first.isFailure,
            usedCpuMillis = usedTotal.cpuMillis,
            usedMemoryBytes = usedTotal.memoryBytes,
            allocatableCpuMillis = allocatable.cpuMillis,
            allocatableMemoryBytes = allocatable.memoryBytes,
            notReadyPods = notReady?.toLong(),
            unavailableDeployments = if (available == null || deploymentList == null) {
                null
            } else {
                (deploymentList.size - available).toLong()
            },
            failingKustomizations = kustomizations.value?.let { rows -> rows.count { isFailing(it) }.toLong() },
            failingHelmReleases = helmReleases.value?.let { rows -> rows.count { isFailing(it) }.toLong() },
            warningEvents = warnings,
            unavailableSources = unavailable,
            healthResolved = unavailable.isEmpty(),
        )
    }

    /** A list result that remembers whether it was actually read, so null never masquerades as "fine". */
    private data class Source(val resolved: Boolean, val value: List<JsonObject>?)

    /**
     * Reads every page of a collection.
     *
     * A single `limit=1000` request silently truncates: the API server returns a `continue` token
     * and the remainder is lost, so a large cluster would report a partial count as an exact total.
     * Continuation is followed to the end; [MAX_PAGES] bounds the walk so a pathological cluster
     * cannot spin here forever.
     */
    private suspend fun listAll(
        session: ClusterSession,
        plural: String,
        group: String,
        namespace: String?,
    ): Source {
        val resource: ApiResource = app.catalog.forResource(plural, group)
            ?: return Source(resolved = false, value = null)
        return runCatching {
            val all = mutableListOf<JsonObject>()
            var token: String? = null
            var pages = 0
            do {
                val page = app.repository.list(
                    session,
                    resource,
                    namespace,
                    limit = PAGE_SIZE,
                    continueToken = token,
                )
                all += page.items
                token = page.continueToken
                pages++
            } while (token != null && pages < MAX_PAGES)
            // A capped continuation is incomplete evidence, never a complete-looking exact total.
            if (token != null) Source(resolved = false, value = null)
            else Source(resolved = true, value = all)
        }.fold(
            onSuccess = { it },
            onFailure = { Source(resolved = false, value = null) },
        )
    }

    /** A Flux object is failing when its Ready condition is present and not True, and not suspended. */
    private fun isFailing(obj: JsonObject): Boolean {
        if (obj.bool("spec/suspend") == true) return false
        val ready = Status.readyCondition(obj) ?: return false
        return !ready.isTrue
    }

    private suspend fun serverVersion(session: ClusterSession): String? {
        val body = session.connection.get("/version") as? JsonObject ?: return null
        return body.str("gitVersion")
    }

    /** Sums `status.allocatable` across the nodes, reusing the metrics quantity parser. */
    private fun nodeAllocatable(nodes: List<JsonObject>?): ResourceUsage {
        val readings = nodes.orEmpty().map { node ->
            ResourceUsage(
                name = node.str("metadata/name").orEmpty(),
                namespace = null,
                cpuMillis = Metrics.parseQuantity(node.str("status/allocatable/cpu"), isCpu = true),
                memoryBytes = Metrics.parseQuantity(node.str("status/allocatable/memory"), isCpu = false),
            )
        }
        return Metrics.sumUsage(readings)
    }

    companion object {
        private const val WARNING_EVENT_LIMIT = 5

        /** One page of a cluster-wide list; continuation is followed until the token runs out. */
        private const val PAGE_SIZE = 500

        /** Bounds the continuation walk so a misbehaving server cannot spin here forever. */
        private const val MAX_PAGES = 40
    }
}
