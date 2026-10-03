package dev.rafa.kubemobile.k8s

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/* ---------------------------------------------------------------------------------------------- */
/* Logs                                                                                            */
/* ---------------------------------------------------------------------------------------------- */

data class LogOptions(
    val container: String? = null,
    val tailLines: Int = 500,
    val follow: Boolean = true,
    val timestamps: Boolean = false,
    val previous: Boolean = false,
    val sinceSeconds: Long? = null,
)

object Logs {

    fun request(connection: KubeConnection, namespace: String, pod: String, options: LogOptions): Request {
        val query = buildMap {
            options.container?.takeIf { it.isNotBlank() }?.let { put("container", it) }
            put("tailLines", options.tailLines.toString())
            put("follow", options.follow.toString())
            put("timestamps", options.timestamps.toString())
            if (options.previous) put("previous", "true")
            options.sinceSeconds?.let { put("sinceSeconds", it.toString()) }
        }
        // No `Accept` header: the API server rejects `text/plain` here with
        //   406 "only the following media types are accepted: application/json,
        //        application/yaml, application/vnd.kubernetes.protobuf"
        // and answers 200 + `Content-Type: text/plain` only when the client simply does not ask.
        // The response body is already a plain-text line stream, which `stream` reads via
        // `body.source().readUtf8Line()` below. Do not re-add an Accept header.
        return connection.newCall("GET", "/api/v1/namespaces/$namespace/pods/$pod/log", query).request()
    }

    /** Streams log lines as they arrive. Cancelling the flow closes the connection. */
    fun stream(connection: KubeConnection, namespace: String, pod: String, options: LogOptions): Flow<String> = flow {
        val call = connection.client.newCall(request(connection, namespace, pod, options))
        val response = call.execute()
        response.use { res ->
            if (!res.isSuccessful) {
                val text = runCatching { res.body.string() }.getOrDefault("")
                throw connection.toException(res.code, res.message, text)
            }
            val source = res.body.source()
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    emit(line)
                }
            } finally {
                runCatching { call.cancel() }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Merges the log streams of several pods into one flow, optionally prefixing every line with
     * its pod name so a combined view stays readable.
     *
     * Each pod is followed independently: a pod whose stream ends — a container restart, a deleted
     * pod — simply contributes no further lines and never terminates the merge. A pod that fails
     * mid-stream is dropped as well; its exception is retained and rethrown only when not a single
     * line was ever produced, so an unreachable API server still surfaces its own message instead
     * of an empty pane. Cancelling the returned flow closes every underlying connection.
     */
    fun streamMerged(
        connection: KubeConnection,
        namespace: String,
        pods: List<String>,
        options: LogOptions,
        tagPodNames: Boolean,
    ): Flow<String> = channelFlow {
        val out = this
        val emitted = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val jobs = pods.map { pod ->
            launch {
                stream(connection, namespace, pod, options)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        failure.compareAndSet(null, error)
                    }
                    .collect { line ->
                        emitted.set(true)
                        out.send(if (tagPodNames) "$pod | $line" else line)
                    }
            }
        }
        jobs.joinAll()
        if (!emitted.get()) failure.get()?.let { throw it }
    }.flowOn(Dispatchers.IO)
}

/* ---------------------------------------------------------------------------------------------- */
/* Exec (container shell)                                                                          */
/* ---------------------------------------------------------------------------------------------- */

sealed interface ExecOutput {
    data class Stdout(val text: String) : ExecOutput
    data class Stderr(val text: String) : ExecOutput
    data class Failure(val message: String) : ExecOutput
    data object Closed : ExecOutput
}

/**
 * `pods/exec` over the WebSocket channel protocol (v4.channel.k8s.io), as used by every API server
 * since 1.18. Channel 0 is stdin, 1 stdout, 2 stderr, 3 error, 4 resize, 255 close.
 */
class ExecSession internal constructor(
    private val socket: WebSocket,
    private val command: List<String>,
    private val tty: Boolean,
) {
    // The WebSocket listener starts delivering before the caller can subscribe (the server answers
    // the upgrade, then immediately sends the ERROR Status for a container with no shell, or the
    // initial prompt for a good one). With replay 0 those first frames are dropped, so a real exec
    // failure was only ever reported as a later read timeout. A small replay window keeps them.
    private val sink = MutableSharedFlow<ExecOutput>(
        replay = 512,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val output: SharedFlow<ExecOutput> = sink.asSharedFlow()

    val banner: String
        get() = "$ ${command.joinToString(" ")}\n"

    fun write(text: String) = write(bytes = text.toByteArray(Charsets.UTF_8))

    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        socket.send((byteArrayOf(STDIN) + bytes).toByteString())
    }

    fun resize(columns: Int, rows: Int) {
        if (!tty) return
        val payload = buildJsonObject {
            put("Width", JsonPrimitive(columns))
            put("Height", JsonPrimitive(rows))
        }.toString()
        socket.send((byteArrayOf(RESIZE) + payload.toByteArray(Charsets.UTF_8)).toByteString())
    }

    fun close() {
        runCatching { socket.send(byteArrayOf(CLOSE).toByteString()) }
        runCatching { socket.close(1000, "client closed") }
    }

    internal fun emit(output: ExecOutput) {
        sink.tryEmit(output)
    }

    internal companion object {
        const val STDIN: Byte = 0
        const val STDOUT: Byte = 1
        const val STDERR: Byte = 2
        const val ERROR: Byte = 3
        const val RESIZE: Byte = 4
        const val CLOSE: Byte = -1
    }
}

object Exec {

    private const val SUBPROTOCOL = "v4.channel.k8s.io"

    /**
     * Opens an interactive session. [onReady] fires once the server accepts the upgrade, which is
     * also when the UI should start forwarding keystrokes.
     */
    suspend fun start(
        connection: KubeConnection,
        namespace: String,
        pod: String,
        container: String?,
        command: List<String> = listOf("/bin/sh"),
        tty: Boolean = true,
        stdin: Boolean = true,
        stdout: Boolean = true,
        stderr: Boolean = true,
        onReady: () -> Unit = {},
    ): ExecSession {
        val query = buildMap {
            container?.takeIf { it.isNotBlank() }?.let { put("container", it) }
            put("stdin", stdin.toString())
            put("stdout", stdout.toString())
            put("stderr", stderr.toString())
            put("tty", tty.toString())
            command.forEach { put("command", it) }
        }
        val request = connection.newCall("GET", "/api/v1/namespaces/$namespace/pods/$pod/exec", query)
            .request()
            .newBuilder()
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()

        var session: ExecSession? = null
        val opened = java.util.concurrent.CompletableFuture<Unit>()
        val failure = java.util.concurrent.CompletableFuture<Throwable>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                opened.complete(Unit)
                onReady()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.isEmpty()) return
                val channel = data[0]
                val payload = data.copyOfRange(1, data.size)
                when (channel) {
                    ExecSession.STDOUT -> session?.emit(ExecOutput.Stdout(String(payload, Charsets.UTF_8)))
                    ExecSession.STDERR -> session?.emit(ExecOutput.Stderr(String(payload, Charsets.UTF_8)))
                    ExecSession.ERROR -> {
                        val error = execStatusException(String(payload, Charsets.UTF_8).trim())
                        session?.emit(ExecOutput.Failure(error.message ?: "exec failed"))
                        failure.complete(error)
                    }
                    // The close channel doubles as a resize echo; ignore it.
                    else -> Unit
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                session?.emit(ExecOutput.Stdout(text))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                session?.emit(ExecOutput.Closed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session?.emit(ExecOutput.Closed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                // A rejected upgrade (404 route, 403 RBAC, 400 bad container) arrives here with the
                // API server's Status body. Wrapping it in a bare IOException made every such
                // failure render as "Cannot reach the API server"; keep the status instead.
                val error: IOException = if (response != null) {
                    val text = runCatching { response.body.string() }.getOrDefault("")
                    connection.toException(response.code, response.message, text)
                } else {
                    IOException(t.message ?: "exec connection failed", t)
                }
                session?.emit(ExecOutput.Failure(error.message ?: "exec failed"))
                failure.complete(error)
            }
        }

        val webSocket = connection.streamingClient.newWebSocket(request, listener)
        session = ExecSession(webSocket, command, tty)
        // The server answers the upgrade only after it accepted the exec; a failure (forbidden,
        // pod not running, bad container) arrives first and must surface to the caller.
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val settled = java.util.concurrent.CompletableFuture.anyOf(opened, failure)
            runCatching { settled.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            failure.getNow(null)?.let { throw it }
        }
        return session
    }

    /**
     * The server's `ERROR` channel carries a Kubernetes `Status` object. Keep its code and reason so
     * a failed exec (no such binary, container not running) maps to an accurate message instead of
     * reading as a lost connection.
     */
    private fun execStatusException(text: String): KubeApiException {
        val obj = runCatching { KubeJson.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = (obj?.get("code") as? JsonPrimitive)?.content?.toIntOrNull() ?: 500
        val reason = (obj?.get("reason") as? JsonPrimitive)?.content.orEmpty()
        return KubeApiException(code, reason, text)
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Watch                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

data class WatchEvent(val type: String, val obj: JsonObject)

object Watches {

    /** Server-side watch: `?watch=true`, one JSON document per line. */
    fun stream(
        connection: KubeConnection,
        resource: ApiResource,
        namespace: String?,
        resourceVersion: String? = null,
        labelSelector: String? = null,
    ): Flow<WatchEvent> = flow {
        val query = buildMap {
            put("watch", "true")
            put("allowWatchBookmarks", "true")
            resourceVersion?.takeIf { it.isNotBlank() }?.let { put("resourceVersion", it) }
            labelSelector?.takeIf { it.isNotBlank() }?.let { put("labelSelector", it) }
        }
        val call = connection.newCall("GET", resource.basePath(namespace), query)
        val response = call.execute()
        response.use { res ->
            if (!res.isSuccessful) {
                throw connection.toException(res.code, res.message, res.body.string())
            }
            val source = res.body.source()
            try {
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = runCatching { KubeJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
                        ?: continue
                    val type = obj.str("type") ?: continue
                    val payload = obj.objAt("object") ?: continue
                    emit(WatchEvent(type, payload))
                }
            } finally {
                runCatching { call.cancel() }
            }
        }
    }.flowOn(Dispatchers.IO)
}

/* ---------------------------------------------------------------------------------------------- */
/* Metrics (metrics-server)                                                                        */
/* ---------------------------------------------------------------------------------------------- */

data class ResourceUsage(
    val name: String,
    val namespace: String?,
    val cpuMillis: Long?,
    val memoryBytes: Long?,
)

object Metrics {

    private val CPU_UNITS = listOf("n" to 1L, "u" to 1_000L, "m" to 1_000_000L, "" to 1_000_000_000L)

    fun parseQuantity(value: String?, isCpu: Boolean): Long? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        if (isCpu) {
            for ((suffix, factor) in CPU_UNITS) {
                if (suffix.isNotEmpty() && !text.endsWith(suffix)) continue
                val number = text.removeSuffix(suffix).toDoubleOrNull() ?: continue
                return ((number * factor) / 1_000_000L).toLong()
            }
            return null
        }
        val factors = listOf(
            "Ki" to 1024L,
            "Mi" to 1024L * 1024,
            "Gi" to 1024L * 1024 * 1024,
            "Ti" to 1024L * 1024 * 1024 * 1024,
            "K" to 1000L,
            "M" to 1_000_000L,
            "G" to 1_000_000_000L,
            "T" to 1_000_000_000_000L,
        )
        for ((suffix, factor) in factors) {
            if (!text.endsWith(suffix)) continue
            val number = text.removeSuffix(suffix).toDoubleOrNull() ?: continue
            return (number * factor).toLong()
        }
        return text.toDoubleOrNull()?.toLong()
    }

    /** Returns pod metrics keyed by `namespace/name`. */
    suspend fun podMetrics(connection: KubeConnection, catalog: ApiCatalog): Map<String, ResourceUsage> {
        val resource = catalog.forResource("pods", "metrics.k8s.io") ?: return emptyMap()
        val list = KubeList.from(connection.get(resource.basePath(null)))
        return list.items.mapNotNull { item ->
            val name = item.str("metadata/name") ?: return@mapNotNull null
            val namespace = item.str("metadata/namespace")
            val containers = item.arrayAt("containers")?.mapNotNull { it as? JsonObject }.orEmpty()
            var cpu = 0L
            var memory = 0L
            containers.forEach { container ->
                parseQuantity(container.str("usage/cpu"), isCpu = true)?.let { cpu += it }
                parseQuantity(container.str("usage/memory"), isCpu = false)?.let { memory += it }
            }
            (namespace?.let { "$it/$name" } ?: name) to ResourceUsage(name, namespace, cpu, memory)
        }.toMap()
    }

    /**
     * Returns node metrics keyed by node name, read from `metrics.k8s.io/v1beta1/nodes`.
     *
     * Unlike a pod metric, a node metric carries its single `usage` block at the top level rather
     * than under `containers[]`, which is why this cannot reuse [podMetrics]'s shape. Empty when
     * metrics-server is absent, so callers can distinguish "no metrics" from "zero usage".
     */
    suspend fun nodeMetrics(connection: KubeConnection, catalog: ApiCatalog): Map<String, ResourceUsage> {
        val resource = catalog.forResource("nodes", "metrics.k8s.io") ?: return emptyMap()
        val list = KubeList.from(connection.get(resource.basePath(null)))
        return list.items.mapNotNull { item ->
            val name = item.str("metadata/name") ?: return@mapNotNull null
            name to ResourceUsage(
                name = name,
                namespace = null,
                cpuMillis = parseQuantity(item.str("usage/cpu"), isCpu = true),
                memoryBytes = parseQuantity(item.str("usage/memory"), isCpu = false),
            )
        }.toMap()
    }

    /**
     * Sums a set of usage readings into one total. A metric that is missing on every member stays
     * null rather than collapsing to 0, so a partial roll-up can never be mistaken for "nothing".
     */
    fun sumUsage(readings: Iterable<ResourceUsage>): ResourceUsage {
        var cpu: Long? = null
        var memory: Long? = null
        readings.forEach { usage ->
            usage.cpuMillis?.let { cpu = (cpu ?: 0L) + it }
            usage.memoryBytes?.let { memory = (memory ?: 0L) + it }
        }
        return ResourceUsage(name = "", namespace = null, cpuMillis = cpu, memoryBytes = memory)
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Generic single-object JSON access                                                               */
/* ---------------------------------------------------------------------------------------------- */

internal fun JsonObject.base64Field(path: String): ByteArray? =
    str(path)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
