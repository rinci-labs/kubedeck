package dev.rafa.kubemobile.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.k8s.LogOptions
import dev.rafa.kubemobile.k8s.Logs
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private const val MAX_LINES = 4000

/** The most pods a single merged view will follow at once. */
const val MAX_MERGED_PODS = 20

/** Kinds whose logs are a merge of several pods, resolved from the object's own selector. */
val WORKLOAD_LOG_KINDS = setOf(
    "Deployment",
    "StatefulSet",
    "DaemonSet",
    "ReplicaSet",
    "ReplicationController",
    "Job",
    "CronJob",
)

/** One pod offered by the workload pod picker. */
data class LogsPodOption(
    val name: String,
    val ready: Boolean,
    val restarts: Long,
    val phase: String,
)

/** Log view state, deliberately independent of the composable so rotation does not drop the tail. */
data class LogsUiState(
    val lines: List<String> = emptyList(),
    val streaming: Boolean = false,
    val error: UiError? = null,
    val followed: Boolean = true,
    val tailLines: Int = 500,
    val timestamps: Boolean = false,
    /** Soft-wrap long lines; off keeps one log line per row and scrolls sideways. */
    val wrap: Boolean = true,
    /** Server-side time window (`sinceSeconds`); null reads as far back as the tail allows. */
    val sinceSeconds: Long? = null,
    /** Display filter over the transcript; never restarts the stream. */
    val query: String = "",
    /** 0 shows everything, 1 warnings and errors, 2 errors only. */
    val minSeverity: Int = 0,
    val follow: Boolean = true,
    val previous: Boolean = false,
    val container: String? = null,
    val containers: List<String> = emptyList(),
    /** Non-null turns on merged workload mode: pods are resolved from this kind's selector. */
    val workloadKind: String? = null,
    val pods: List<LogsPodOption> = emptyList(),
    val podsLoading: Boolean = false,
    val podsError: UiError? = null,
    /** True while the merged "all pods" view is selected rather than one pod. */
    val allPods: Boolean = true,
    val selectedPod: String? = null,
    /** Every pod the workload owns, which may exceed the number actually being followed. */
    val discoveredPods: Int = 0,
    val trackedPods: Int = 0,
    /** True when lines carry a `pod | ` prefix because more than one pod is being merged. */
    val tagged: Boolean = false,
) {
    val isWorkload: Boolean get() = workloadKind != null

    /** Honest disclosure when a workload has more pods than the merge cap follows. */
    val cappedFrom: Int? get() = discoveredPods.takeIf { it > trackedPods }
}

class LogsViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var resolveJob: Job? = null
    private var podNamespace: String = ""
    /** The Pod or the workload being viewed; the object name either way. */
    private var podName: String = ""

    /**
     * Starts following the logs of [pod] (a single pod) or, when [workloadKind] is one of
     * [WORKLOAD_LOG_KINDS], of every pod that workload owns, merged.
     */
    fun start(namespace: String, pod: String, workloadKind: String?, initialContainer: String?) {
        val sameTarget = podNamespace == namespace && podName == pod &&
            _state.value.workloadKind == workloadKind
        if (sameTarget && (streamJob?.isActive == true || resolveJob?.isActive == true || _state.value.lines.isNotEmpty())) {
            return
        }
        podNamespace = namespace
        podName = pod
        val saved = savedState.get<String>(KEY_CONTAINER) ?: initialContainer
        _state.value = _state.value.copy(
            container = saved,
            tailLines = savedState.get<Int>(KEY_TAIL) ?: 500,
            timestamps = savedState.get<Boolean>(KEY_TIMESTAMPS) ?: false,
            wrap = savedState.get<Boolean>(KEY_WRAP) ?: true,
            sinceSeconds = savedState.get<Long>(KEY_SINCE),
            follow = savedState.get<Boolean>(KEY_FOLLOW) ?: true,
            previous = savedState.get<Boolean>(KEY_PREVIOUS) ?: false,
            workloadKind = workloadKind,
            allPods = true,
            selectedPod = null,
            pods = emptyList(),
            podsError = null,
            lines = emptyList(),
            error = null,
        )
        if (workloadKind == null) {
            loadContainers(pod)
            restart()
        } else {
            // The first stream starts only once the pods are known, so the header can name them.
            resolveWorkloadPods()
        }
    }

    private fun loadContainers(pod: String) {
        val session = app.session ?: return
        val pods = app.catalog.forResource("pods", "") ?: return
        viewModelScope.launch {
            runCatching { app.repository.get(session, pods, pod, podNamespace) }
                .onSuccess { podObject ->
                    val names = podObject.arrayAt("spec/containers")
                        ?.mapNotNull { (it as? JsonObject)?.str("name") }
                        .orEmpty()
                    _state.value = _state.value.copy(
                        containers = names,
                        container = _state.value.container?.takeIf { it in names } ?: names.firstOrNull(),
                    )
                }
        }
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Workload pod resolution                                                                   */
    /* ---------------------------------------------------------------------------------------- */

    /**
     * Resolves the pods behind a workload. The selector is applied server-side so a large namespace
     * is never walked client-side; a workload with no `matchLabels` (a Job from `kubectl run`, for
     * instance) falls back to walking `ownerReferences`, and a Deployment or CronJob is followed
     * through its ReplicaSets or Jobs respectively.
     */
    private fun resolveWorkloadPods() {
        val session = app.session ?: run {
            _state.value = _state.value.copy(error = missingCluster())
            return
        }
        val kind = _state.value.workloadKind ?: return
        _state.value = _state.value.copy(podsLoading = true, podsError = null)
        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            runCatching { orderPods(resolveWorkloadPods(app, kind, podName, podNamespace)) }
                .onSuccess { found ->
                    _state.value = _state.value.copy(
                        pods = found,
                        podsLoading = false,
                        discoveredPods = found.size,
                        selectedPod = found.firstOrNull()?.name,
                    )
                    restart()
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        podsLoading = false,
                        streaming = false,
                        podsError = it.toUiError(),
                    )
                }
        }
    }

    /** Running pods first so the merge cap never spends its budget on a pod that cannot stream. */
    private fun orderPods(pods: List<JsonObject>): List<LogsPodOption> = orderForStreaming(pods)
        .map { pod ->
            val info = podInfo(pod)
            LogsPodOption(
                name = info.name,
                ready = info.hasStatuses && info.ready == info.total,
                restarts = info.restarts,
                phase = info.phase,
            )
        }

    /* ---------------------------------------------------------------------------------------- */
    /* Selection                                                                                 */
    /* ---------------------------------------------------------------------------------------- */

    fun setAllPods(all: Boolean) {
        if (_state.value.allPods == all) return
        _state.value = _state.value.copy(allPods = all, lines = emptyList())
        restart()
    }

    fun setSelectedPod(name: String) {
        if (_state.value.selectedPod == name && !_state.value.allPods) return
        _state.value = _state.value.copy(allPods = false, selectedPod = name, lines = emptyList())
        loadContainers(name)
        restart()
    }

    fun setContainer(container: String) {
        savedState[KEY_CONTAINER] = container
        _state.value = _state.value.copy(container = container, lines = emptyList())
        restart()
    }

    fun setTail(tail: Int) {
        savedState[KEY_TAIL] = tail
        _state.value = _state.value.copy(tailLines = tail, lines = emptyList())
        restart()
    }

    /** Display-only: no restart, the transcript is kept. */
    fun setWrap(value: Boolean) {
        savedState[KEY_WRAP] = value
        _state.value = _state.value.copy(wrap = value)
    }

    fun setSince(seconds: Long?) {
        savedState[KEY_SINCE] = seconds
        _state.value = _state.value.copy(sinceSeconds = seconds, lines = emptyList())
        restart()
    }

    fun setQuery(value: String) {
        _state.value = _state.value.copy(query = value)
    }

    fun setMinSeverity(value: Int) {
        _state.value = _state.value.copy(minSeverity = value)
    }

    /** Empties the transcript; a live stream keeps appending new lines after it. */
    fun clear() {
        _state.value = _state.value.copy(lines = emptyList())
    }

    fun setTimestamps(value: Boolean) {
        savedState[KEY_TIMESTAMPS] = value
        _state.value = _state.value.copy(timestamps = value, lines = emptyList())
        restart()
    }

    fun setFollow(value: Boolean) {
        savedState[KEY_FOLLOW] = value
        _state.value = _state.value.copy(follow = value, lines = emptyList())
        restart()
    }

    fun setPrevious(value: Boolean) {
        savedState[KEY_PREVIOUS] = value
        _state.value = _state.value.copy(previous = value, lines = emptyList())
        restart()
    }

    /** Disables auto-scroll when the reader scrolls back; the user re-enables it explicitly. */
    fun setFollowed(value: Boolean) {
        _state.value = _state.value.copy(followed = value)
    }

    /* ---------------------------------------------------------------------------------------- */
    /* Streaming                                                                                 */
    /* ---------------------------------------------------------------------------------------- */

    /** (Re)starts the stream for the current selection. */
    fun restart() {
        val session = app.session ?: run {
            _state.value = _state.value.copy(error = missingCluster())
            return
        }
        val current = _state.value
        val targets = when {
            !current.isWorkload -> listOf(podName)
            current.allPods -> current.pods.take(MAX_MERGED_PODS).map { it.name }
            else -> listOfNotNull(current.selectedPod)
        }
        streamJob?.cancel()
        if (targets.isEmpty()) {
            // A workload can legitimately own no pods; say so rather than opening a silent stream.
            _state.value = current.copy(streaming = false, lines = emptyList())
            return
        }
        _state.value = current.copy(
            streaming = true,
            error = null,
            followed = true,
            trackedPods = targets.size,
            tagged = targets.size > 1,
        )
        streamJob = viewModelScope.launch {
            val options = LogOptions(
                // A merged view always reads every container of each pod; picking one container
                // across different pods would silently drop the others.
                container = if (targets.size > 1) null else current.container,
                tailLines = current.tailLines,
                follow = current.follow,
                timestamps = current.timestamps,
                sinceSeconds = current.sinceSeconds,
                previous = current.previous,
            )
            val flow = if (targets.size > 1) {
                Logs.streamMerged(session.connection, podNamespace, targets, options, tagPodNames = true)
            } else {
                Logs.stream(session.connection, podNamespace, targets.first(), options)
            }
            runCatching {
                flow.collect { line ->
                    val next = _state.value.lines + line
                    _state.value = _state.value.copy(
                        lines = if (next.size > MAX_LINES) next.takeLast(MAX_LINES) else next,
                    )
                }
            }
                .onSuccess { _state.value = _state.value.copy(streaming = false) }
                .onFailure { error ->
                    if (error is kotlinx.coroutines.CancellationException) return@onFailure
                    _state.value = _state.value.copy(streaming = false, error = error.toUiError())
                }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _state.value = _state.value.copy(streaming = false)
    }

    fun fullText(): String = _state.value.lines.joinToString("\n")

    private fun missingCluster(): UiError {
        val text = app.getApplication<android.app.Application>()
            .getString(dev.rafa.kubemobile.R.string.error_missing_cluster)
        return UiError(dev.rafa.kubemobile.R.string.error_missing_cluster, text, null)
    }

    override fun onCleared() {
        resolveJob?.cancel()
        stop()
        super.onCleared()
    }

    companion object {
        private const val KEY_CONTAINER = "logs.container"
        private const val KEY_TAIL = "logs.tail"
        private const val KEY_TIMESTAMPS = "logs.timestamps"
        private const val KEY_WRAP = "logs.wrap"
        private const val KEY_SINCE = "logs.since"
        private const val KEY_FOLLOW = "logs.follow"
        private const val KEY_PREVIOUS = "logs.previous"
    }
}

/** Tail options offered in the options sheet. */
val LOG_TAIL_OPTIONS = listOf(100, 500, 1000, 5000)

/** Time windows offered in the options sheet, as (label, seconds); null means no window. */
val LOG_SINCE_OPTIONS: List<Pair<String, Long?>> = listOf(
    "All" to null,
    "5m" to 300L,
    "15m" to 900L,
    "1h" to 3_600L,
    "6h" to 21_600L,
    "24h" to 86_400L,
)

private val JSON_LEVEL = Regex(""""(?:level|severity|lvl)"\s*:\s*"([A-Za-z]+)"""")

/**
 * 2 for an error, 1 for a warning, 0 otherwise. A structured `"level"` field wins over keyword
 * matching, so a JSON info line that merely mentions "error" in a message is not painted red.
 */
fun logSeverity(line: String): Int {
    JSON_LEVEL.find(line)?.groupValues?.get(1)?.lowercase()?.let { level ->
        return when (level) {
            "error", "err", "fatal", "panic", "critical", "crit", "alert", "emerg" -> 2
            "warn", "warning" -> 1
            else -> 0
        }
    }
    return when {
        line.contains("ERROR", true) ||
            line.contains("FATAL", true) ||
            line.contains("panic", true) ||
            line.contains("Exception", true) ||
            line.contains("TypeError") -> 2

        line.contains("WARN", true) -> 1
        else -> 0
    }
}
