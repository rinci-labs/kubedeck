package dev.rafa.kubemobile.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.k8s.Exec
import dev.rafa.kubemobile.k8s.ExecOutput
import dev.rafa.kubemobile.k8s.ExecSession
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

/** Scrollback kept in memory; beyond this the oldest lines are dropped. */
const val TERMINAL_SCROLLBACK = 2000

val SHELL_OPTIONS = listOf("/bin/sh", "/bin/bash", "/bin/ash")

enum class TerminalStatus { IDLE, CONNECTING, OPEN, CLOSED, FAILED }

data class TerminalUiState(
    val status: TerminalStatus = TerminalStatus.IDLE,
    val lines: List<String> = emptyList(),
    val pendingInput: String = "",
    val container: String? = null,
    val containers: List<String> = emptyList(),
    val shell: String = SHELL_OPTIONS.first(),
    val tty: Boolean = true,
    val error: UiError? = null,
    val banner: String = "",
)

class TerminalViewModel(
    private val app: AppViewModel,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var session: ExecSession? = null
    private var collectJob: Job? = null
    private var namespace: String = ""
    private var pod: String = ""

    fun start(namespace: String, pod: String, initialContainer: String?) {
        if (this.namespace == namespace && this.pod == pod && session != null) return
        this.namespace = namespace
        this.pod = pod
        _state.value = _state.value.copy(
            container = savedState.get<String>(KEY_CONTAINER) ?: initialContainer,
            shell = savedState.get<String>(KEY_SHELL) ?: SHELL_OPTIONS.first(),
            tty = savedState.get<Boolean>(KEY_TTY) ?: true,
        )
        loadContainers()
        connect()
    }

    private fun loadContainers() {
        val current = app.session ?: return
        val pods = app.catalog.forResource("pods", "") ?: return
        viewModelScope.launch {
            runCatching { app.repository.get(current, pods, pod, namespace) }
                .onSuccess { body ->
                    val names = body.arrayAt("spec/containers")
                        ?.mapNotNull { (it as? JsonObject)?.str("name") }
                        .orEmpty()
                    _state.value = _state.value.copy(
                        containers = names,
                        container = _state.value.container?.takeIf { it in names } ?: names.firstOrNull(),
                    )
                }
        }
    }

    fun connect() {
        val current = app.session ?: run {
            _state.value = _state.value.copy(status = TerminalStatus.FAILED, error = missingCluster())
            return
        }
        close(reason = null)
        _state.value = _state.value.copy(
            status = TerminalStatus.CONNECTING,
            error = null,
            lines = emptyList(),
        )
        val container = _state.value.container
        val shell = _state.value.shell
        val tty = _state.value.tty
        viewModelScope.launch {
            runCatching {
                Exec.start(
                    connection = current.connection,
                    namespace = namespace,
                    pod = pod,
                    container = container,
                    command = listOf(shell),
                    tty = tty,
                    stdin = true,
                    stdout = true,
                    stderr = true,
                )
            }
                .onSuccess { created ->
                    session = created
                    _state.value = _state.value.copy(
                        status = TerminalStatus.OPEN,
                        banner = created.banner,
                    )
                    collect(created)
                }
                .onFailure { error ->
                    _state.value = _state.value.copy(
                        status = TerminalStatus.FAILED,
                        error = error.toUiError(),
                    )
                }
        }
    }

    private fun collect(created: ExecSession) {
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            created.output.collect { output ->
                when (output) {
                    is ExecOutput.Stdout -> append(output.text)
                    is ExecOutput.Stderr -> append(output.text)
                    is ExecOutput.Failure -> _state.value = _state.value.copy(
                        status = TerminalStatus.FAILED,
                        error = UiError(
                            R.string.error_generic_title,
                            output.message,
                            output.message,
                        ),
                        lines = appendText(_state.value.lines, output.message),
                    )

                    ExecOutput.Closed -> if (_state.value.status == TerminalStatus.OPEN) {
                        _state.value = _state.value.copy(status = TerminalStatus.CLOSED)
                    }
                }
            }
        }
    }

    private fun append(text: String) {
        if (text.isEmpty()) return
        _state.value = _state.value.copy(lines = appendText(_state.value.lines, text))
    }

    /** Terminal output arrives as arbitrary chunks, so it is split on newlines for the list. */
    private fun appendText(existing: List<String>, chunk: String): List<String> {
        val parts = (existing.lastOrNull().orEmpty() + chunk).split("\n")
        val head = existing.dropLast(if (existing.isEmpty()) 0 else 1)
        val combined = head + parts
        return if (combined.size > TERMINAL_SCROLLBACK) {
            combined.takeLast(TERMINAL_SCROLLBACK)
        } else {
            combined
        }
    }

    fun setInput(value: String) {
        _state.value = _state.value.copy(pendingInput = value)
        savedState[KEY_INPUT] = value
    }

    /** Sends the typed command with a newline, exactly like pressing Enter in a shell. */
    fun submit() {
        val text = _state.value.pendingInput
        if (text.isEmpty()) {
            send("\n")
        } else {
            send("$text\n")
        }
        setInput("")
    }

    fun send(text: String) {
        val active = session ?: return
        runCatching { active.write(text) }
            .onFailure { app.notify(it.message ?: "Send failed") }
    }

    fun sendControl(control: Char) {
        send(control.toString())
    }

    fun setContainer(container: String) {
        savedState[KEY_CONTAINER] = container
        _state.value = _state.value.copy(container = container)
        connect()
    }

    fun setShell(shell: String) {
        savedState[KEY_SHELL] = shell
        _state.value = _state.value.copy(shell = shell)
        connect()
    }

    fun setTty(value: Boolean) {
        savedState[KEY_TTY] = value
        _state.value = _state.value.copy(tty = value)
        connect()
    }

    fun onResize(columns: Int, rows: Int) {
        session?.resize(columns, rows)
    }

    fun disconnect() {
        close(reason = "Session closed by the user")
    }

    private fun close(reason: String?) {
        runCatching { session?.close() }
        session = null
        collectJob?.cancel()
        collectJob = null
        if (reason != null) {
            _state.value = _state.value.copy(
                status = TerminalStatus.CLOSED,
                lines = appendText(_state.value.lines, "\n$reason\n"),
            )
        }
    }

    private fun missingCluster(): UiError {
        val text = app.getApplication<android.app.Application>().getString(R.string.error_missing_cluster)
        return UiError(R.string.error_missing_cluster, text, null)
    }

    override fun onCleared() {
        close(reason = null)
        super.onCleared()
    }

    companion object {
        private const val KEY_CONTAINER = "terminal.container"
        private const val KEY_SHELL = "terminal.shell"
        private const val KEY_TTY = "terminal.tty"
        private const val KEY_INPUT = "terminal.input"
    }
}
