package dev.rafa.kubemobile.ui.events

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.k8s.long
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

/** One event with the fields the feed renders, resolved once per fetch. */
data class EventRow(
    val uid: String,
    val type: String,
    val reason: String,
    val message: String,
    val involvedKind: String,
    val involvedName: String,
    val involvedNamespace: String?,
    val count: Long,
    val lastTimestamp: String?,
    val source: String?,
    val object_: JsonObject,
) {
    val isWarning: Boolean get() = type.equals("Warning", true)

    /** The involved object this event was grouped under, matching `kubectl get events` output. */
    val groupLabel: String
        get() = listOfNotNull(involvedKind.ifBlank { null }, involvedName).joinToString("/")
            .ifBlank { "—" }
}

data class EventsUiState(
    val rows: List<EventRow> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: UiError? = null,
    val scope: String? = null,
    val warningsOnly: Boolean = false,
    val grouped: Boolean = true,
)

class EventsViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state: StateFlow<EventsUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    fun start() {
        if (_state.value.rows.isEmpty()) load(initial = true)
    }

    fun setScope(namespace: String?) {
        _state.value = _state.value.copy(scope = namespace)
        load(initial = true)
    }

    fun setWarningsOnly(value: Boolean) {
        _state.value = _state.value.copy(warningsOnly = value)
    }

    fun setGrouped(value: Boolean) {
        _state.value = _state.value.copy(grouped = value)
    }

    fun refresh() = load(initial = false)

    fun retry() = load(initial = true)

    /** Newest first: `metadata.creationTimestamp` then the event's own `lastTimestamp`. */
    fun visible(): List<EventRow> {
        val query = _state.value.scope
        val rows = _state.value.rows
        val filtered = if (_state.value.warningsOnly) rows.filter { it.isWarning } else rows
        return if (query == null) filtered else filtered.filter { it.involvedNamespace == query }
    }

    /** Events grouped by involved object, most eventful objects first. */
    fun grouped(): List<Pair<String, List<EventRow>>> =
        visible().groupBy { it.groupLabel }.toList().sortedByDescending { (_, list) -> list.size }

    private fun load(initial: Boolean) {
        val session = app.session ?: return
        val eventsResource = app.catalog.forResource("events", "")
            ?: app.catalog.forResource("events")
            ?: run {
                _state.value = _state.value.copy(
                    loading = false,
                    error = UiError(
                        dev.rafa.kubemobile.R.string.error_generic_title,
                        "Events are not exposed by this API server",
                        null,
                    ),
                )
                return
            }
        loadJob?.cancel()
        _state.value = _state.value.copy(
            loading = initial && _state.value.rows.isEmpty(),
            refreshing = !initial,
            error = null,
        )
        loadJob = viewModelScope.launch {
            runCatching {
                app.repository.list(session, eventsResource, null, limit = 500)
            }
                .onSuccess { list ->
                    val rows = list.items.map { it.toRow() }
                        .sortedByDescending { it.lastTimestamp ?: "" }
                    _state.value = _state.value.copy(
                        rows = rows,
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

    private fun JsonObject.toRow(): EventRow {
        val involvedKind = str("involvedObject/kind").orEmpty()
        val involvedName = str("involvedObject/name").orEmpty()
        return EventRow(
            uid = str("metadata/uid")
                ?: listOfNotNull(
                    str("metadata/namespace"),
                    involvedKind,
                    involvedName,
                    str("reason"),
                    str("lastTimestamp"),
                ).joinToString("|"),
            type = str("type") ?: "Normal",
            reason = str("reason").orEmpty(),
            message = str("message").orEmpty(),
            involvedKind = involvedKind,
            involvedName = involvedName,
            involvedNamespace = str("involvedObject/namespace") ?: str("metadata/namespace"),
            count = long("count") ?: 1L,
            lastTimestamp = str("lastTimestamp")
                ?: str("eventTime")
                ?: str("metadata/creationTimestamp"),
            source = str("source/component") ?: str("reportingComponent"),
            object_ = this,
        )
    }

    fun copyText(): String = visible().joinToString("\n") { row ->
        "${row.lastTimestamp.orEmpty()} ${row.type} ${row.involvedKind}/${row.involvedName}: ${row.reason} ${row.message}"
    }

    /** Namespaces seen in the feed, used for the scope chips (no extra API call). */
    fun namespacesInFeed(): List<String> =
        _state.value.rows.mapNotNull { it.involvedNamespace }.distinct().sorted()
}
