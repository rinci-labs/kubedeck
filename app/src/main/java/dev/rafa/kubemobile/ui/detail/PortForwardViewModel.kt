package dev.rafa.kubemobile.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.k8s.PortForward
import dev.rafa.kubemobile.k8s.PortForwardEvent
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ui.AppViewModel
import dev.rafa.kubemobile.ui.UiError
import dev.rafa.kubemobile.ui.shortMessage
import dev.rafa.kubemobile.ui.toUiError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** One forwarded port as shown on a card. */
data class ForwardCard(
    val id: String,
    val localPort: Int,
    val remotePort: Int,
    val containerName: String?,
    val protocol: String,
    val target: String,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val error: String? = null,
)

/** A port declared by the pod, offered as a "Forward" button. */
data class PodPort(
    val containerName: String,
    val name: String?,
    val port: Int,
    val protocol: String,
)

data class PortForwardUiState(
    val ports: List<PodPort> = emptyList(),
    val forwards: List<ForwardCard> = emptyList(),
    val loading: Boolean = true,
    val error: UiError? = null,
)

class PortForwardViewModel(
    private val app: AppViewModel,
) : ViewModel() {

    private val _state = MutableStateFlow(PortForwardUiState())
    val state: StateFlow<PortForwardUiState> = _state.asStateFlow()

    private val sessions = HashMap<String, PortForward.Session>()
    private val observers = HashMap<String, Job>()
    private var namespace: String = ""
    private var pod: String = ""

    fun start(namespace: String, pod: String) {
        if (this.namespace == namespace && this.pod == pod && _state.value.ports.isNotEmpty()) return
        this.namespace = namespace
        this.pod = pod
        loadPorts()
    }

    fun loadPorts() {
        val session = app.session ?: run {
            _state.value = _state.value.copy(
                loading = false,
                error = UiError(
                    R.string.error_missing_cluster,
                    app.getApplication<android.app.Application>()
                        .getString(R.string.error_missing_cluster),
                    null,
                ),
            )
            return
        }
        val pods = app.catalog.forResource("pods", "") ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { app.repository.get(session, pods, pod, namespace) }
                .onSuccess { body ->
                    _state.value = _state.value.copy(loading = false, ports = portsOf(body))
                }
                .onFailure {
                    _state.value = _state.value.copy(loading = false, error = it.toUiError())
                }
        }
    }

    private fun portsOf(pod: JsonObject): List<PodPort> =
        pod.arrayAt("spec/containers")?.mapNotNull { it as? JsonObject }.orEmpty()
            .flatMap { container ->
                val containerName = container.str("name").orEmpty()
                container.arrayAt("ports")?.mapNotNull { it as? JsonObject }.orEmpty().mapNotNull { port ->
                    val number = port.long("containerPort")?.toInt() ?: return@mapNotNull null
                    PodPort(
                        containerName = containerName,
                        name = port.str("name"),
                        port = number,
                        protocol = port.str("protocol") ?: "TCP",
                    )
                }
            }

    /**
     * Binds a loopback listener in the pod's network namespace. The returned local port is the one
     * the user pastes into a local client, so it is surfaced in the card immediately.
     */
    fun forward(remotePort: Int, containerName: String? = null, protocol: String = "TCP") {
        val session = app.session ?: return
        val id = "$remotePort/$containerName"
        if (sessions.containsKey(id)) {
            app.notify(
                app.getApplication<android.app.Application>()
                    .getString(R.string.pf_in_use, remotePort),
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                PortForward.start(session.connection, namespace, pod, remotePort, 0)
            }
                .onSuccess { forward ->
                    sessions[id] = forward
                    val card = ForwardCard(
                        id = id,
                        localPort = forward.localPort,
                        remotePort = forward.target.remotePort,
                        containerName = containerName,
                        protocol = protocol,
                        target = pod,
                    )
                    _state.value = _state.value.copy(forwards = _state.value.forwards + card)
                    app.notify(
                        app.getApplication<android.app.Application>().getString(
                            R.string.pf_started,
                            forward.localPort,
                            "$pod:",
                            remotePort,
                        ),
                    )
                    observe(id, forward)
                }
                .onFailure { app.notify(it.shortMessage()) }
        }
    }

    /** Byte counters and failures come from the forward's own event stream. */
    private fun observe(id: String, forward: PortForward.Session) {
        observers[id]?.cancel()
        observers[id] = viewModelScope.launch {
            launch {
                while (true) {
                    delay(1000)
                    val up = forward.transferredUp
                    val down = forward.transferredDown
                    _state.value = _state.value.copy(
                        forwards = _state.value.forwards.map {
                            if (it.id == id) it.copy(bytesUp = up, bytesDown = down) else it
                        },
                    )
                }
            }
            forward.events.collect { event ->
                when (event) {
                    is PortForwardEvent.Started -> Unit
                    is PortForwardEvent.Data -> Unit
                    is PortForwardEvent.Failure -> {
                        _state.value = _state.value.copy(
                            forwards = _state.value.forwards.map {
                                if (it.id == id) it.copy(error = event.message) else it
                            },
                        )
                    }

                    PortForwardEvent.Stopped -> removeLocal(id)
                }
            }
        }
    }

    fun stop(id: String) {
        sessions.remove(id)?.let { runCatching { it.close() } }
        observers.remove(id)?.cancel()
        removeLocal(id)
    }

    private fun removeLocal(id: String) {
        _state.value = _state.value.copy(forwards = _state.value.forwards.filterNot { it.id == id })
    }

    fun stopAll() {
        sessions.keys.toList().forEach(::stop)
    }

    override fun onCleared() {
        stopAll()
        super.onCleared()
    }
}
