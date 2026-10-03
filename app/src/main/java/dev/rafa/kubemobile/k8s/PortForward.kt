package dev.rafa.kubemobile.k8s

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** State of one port-forward session, surfaced to the UI. */
data class PortForwardTarget(
    val namespace: String,
    val pod: String,
    val remotePort: Int,
    val localPort: Int,
)

sealed interface PortForwardEvent {
    data class Started(val localPort: Int) : PortForwardEvent
    data class Data(val direction: String, val bytes: Long) : PortForwardEvent
    data class Failure(val message: String) : PortForwardEvent
    data object Stopped : PortForwardEvent
}

/**
 * `kubectl port-forward` over `pods/portforward`.
 *
 * Modern API servers answer this endpoint with the WebSocket subprotocol
 * `SPDY/3.1+portforward.k8s.io`, carrying SPDY/3.1 frames inside WebSocket binary messages.
 * [SpdyMultiplexer] owns the framing; this class owns the local TCP listener and `kubectl`'s
 * per-connection stream semantics:
 *
 *  - a loopback [ServerSocket] is bound *before* the tunnel opens, so the real port is known;
 *  - every accepted TCP connection gets a fresh `error` + `data` stream pair sharing one
 *    `requestID`, created as `error` first then `data`, following client-go;
 *  - the error stream is write-closed immediately; whatever it returns (text or RST) is the
 *    human-readable failure reason for that connection.
 */
object PortForward {

    private const val STREAM_TYPE = "streamType"
    private const val PORT = "port"
    private const val REQUEST_ID = "requestID"
    private const val STREAM_ERROR = "error"
    private const val STREAM_DATA = "data"

    private const val UPSTREAM_BUFFER = 32 * 1024
    private const val ESTABLISH_TIMEOUT_SECONDS = 30L

    private val EMPTY = ByteArray(0)

    /** Local listener that proxies TCP traffic into the pod through the forward tunnel. */
    class Session internal constructor(
        private val socket: WebSocket,
        private val serverSocket: ServerSocket,
        private val mux: SpdyMultiplexer,
        val target: PortForwardTarget,
    ) {
        private val sink = MutableSharedFlow<PortForwardEvent>(
            replay = 1,
            extraBufferCapacity = 256,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

        /** Activity and failures for this forward, for the UI to observe. */
        val events: SharedFlow<PortForwardEvent> = sink.asSharedFlow()

        private val connections = CopyOnWriteArrayList<Link>()
        private val nextRequestId = AtomicInteger(0)
        private val bytesUp = AtomicLong()
        private val bytesDown = AtomicLong()
        private val closed = AtomicBoolean(false)
        private val adoptLock = Any()

        /** Reserved stream pair for the next accepted connection (see [probe]). */
        @Volatile
        private var pending: Forward? = null

        val localPort: Int get() = serverSocket.localPort
        val transferredUp: Long get() = bytesUp.get()
        val transferredDown: Long get() = bytesDown.get()

        /* ------------------------------------ setup ------------------------------------ */

        /**
         * Opens the first `error`+`data` stream pair (`requestID` 0) and blocks until the server
         * establishes the data stream. This is what proves the tunnel is usable, and it doubles as
         * the pair the first accepted TCP connection will use.
         */
        internal fun probe() {
            val forward = Forward(nextRequestId.getAndIncrement())
            pending = forward
            openForward(forward)
            if (!forward.established.await(ESTABLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                forward.fail("Timed out waiting for the server to establish the port-forward stream")
                throw IOException("Timed out waiting for the server to establish the port-forward stream")
            }
            // A failure on the error stream here (e.g. an unforwardable port) is reported as an
            // event, not a startup error: kubectl likewise only complains once a connection is used.
            sink.tryEmit(PortForwardEvent.Started(serverSocket.localPort))
        }

        /** Accepts local connections and pumps each one through its own stream pair. */
        internal fun serve() {
            Thread({
                while (!closed.get() && !serverSocket.isClosed) {
                    val client = try {
                        serverSocket.accept()
                    } catch (_: IOException) {
                        break
                    }
                    val adopted = synchronized(adoptLock) { pending.also { pending = null } }
                    Thread(
                        { runConnection(client, adopted) },
                        "pf-conn-${target.remotePort}",
                    ).apply { isDaemon = true }.start()
                }
            }, "pf-accept-${target.remotePort}").apply { isDaemon = true }.start()
        }

        private fun runConnection(client: Socket, adopted: Forward?) {
            val forward = adopted?.takeIf { it.failure.get() == null }
                ?: Forward(nextRequestId.getAndIncrement()).also { openForward(it) }
            val link = Link(client, forward)
            forward.link = link
            connections += link
            try {
                if (!forward.established.await(ESTABLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    forward.fail("Timed out waiting for the server to establish the port-forward stream")
                    return
                }
                link.startDownstream()
                link.pumpUpstream()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                link.close()
                connections -= link
                forward.retire()
            }
        }

        private fun openForward(forward: Forward) {
            val port = target.remotePort.toString()
            val requestId = forward.requestId.toString()
            forward.errorId = mux.createStream(
                headers = mapOf(
                    STREAM_TYPE to STREAM_ERROR,
                    PORT to port,
                    REQUEST_ID to requestId,
                ),
                // The error stream is write-closed as soon as it is created.
                finish = true,
                onData = forward::onErrorData,
                onReset = { forward.onServerReset() },
                onEstablished = { },
                onClose = { },
            )
            forward.dataId = mux.createStream(
                headers = mapOf(
                    STREAM_TYPE to STREAM_DATA,
                    PORT to port,
                    REQUEST_ID to requestId,
                ),
                finish = false,
                onData = forward::onData,
                onReset = { forward.onServerReset() },
                onEstablished = { forward.established.countDown() },
                onClose = { forward.onRemoteFinished() },
            )
        }

        /* ----------------------------------- teardown ---------------------------------- */

        /** Releases the local port, all streams and the WebSocket. Idempotent. */
        fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { serverSocket.close() }
            runCatching { socket.close(1000, "client closed") }
            mux.shutdown()
            val snapshot = connections.toList()
            connections.clear()
            snapshot.forEach { it.close() }
            sink.tryEmit(PortForwardEvent.Stopped)
        }

        /* ------------------------------- WebSocket events ------------------------------ */

        internal fun onBytes(bytes: ByteString) {
            if (closed.get()) return
            val array = bytes.toByteArray()
            mux.onFrameReceived(array, 0, array.size)
        }

        internal fun onSocketClosed() {
            if (!closed.get()) close()
        }

        internal fun onSocketFailure(t: Throwable, response: Response?) {
            if (closed.get()) return
            val body = response?.let { runCatching { it.body.string() }.getOrNull() }
            val message = body?.takeIf { it.isNotBlank() }?.let { extractMessage(it) }
                ?: t.message
                ?: "port-forward connection failed"
            sink.tryEmit(PortForwardEvent.Failure(message))
            close()
        }

        /* ------------------------------ internal plumbing ------------------------------ */

        /** One `error`+`data` stream pair, identified by [requestId]. */
        private inner class Forward(val requestId: Int) {
            var errorId = 0
            var dataId = 0
            val established = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)

            @Volatile
            var link: Link? = null

            fun onData(bytes: ByteArray) {
                link?.deliver(bytes)
            }

            fun onErrorData(bytes: ByteArray) {
                val message = String(bytes, Charsets.UTF_8).trim()
                if (message.isNotEmpty()) fail(message)
            }

            fun onServerReset() {
                fail("the server reset the port-forward stream")
            }

            fun onRemoteFinished() {
                link?.remoteFinished()
            }

            fun fail(message: String) {
                if (failure.compareAndSet(null, message)) {
                    sink.tryEmit(PortForwardEvent.Failure(message))
                    link?.fail()
                }
            }

            fun retire() {
                mux.resetStream(errorId)
                mux.resetStream(dataId)
            }
        }

        /** The local TCP connection paired with one [Forward]. */
        private inner class Link(private val client: Socket, private val forward: Forward) {
            private val closed = AtomicBoolean(false)
            private val down = LinkedBlockingQueue<ByteArray>()

            fun startDownstream() {
                Thread({
                    try {
                        val out = client.getOutputStream()
                        while (true) {
                            val chunk = down.take()
                            if (chunk.isEmpty()) break
                            out.write(chunk)
                            out.flush()
                            bytesDown.addAndGet(chunk.size.toLong())
                            sink.tryEmit(PortForwardEvent.Data("down", chunk.size.toLong()))
                        }
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } catch (_: IOException) {
                        // Local client went away.
                    } finally {
                        close()
                    }
                }, "pf-down-${forward.requestId}").apply { isDaemon = true }.start()
            }

            /** Queues pod bytes for the local socket, without blocking the tunnel reader. */
            fun deliver(bytes: ByteArray) {
                if (!closed.get()) runCatching { down.put(bytes) }
            }

            /** The pod half-closed the connection; let the local socket drain and end. */
            fun remoteFinished() {
                down.offer(EMPTY)
            }

            /** Reads the local socket and forwards it into the data stream until EOF. */
            fun pumpUpstream() {
                val buffer = ByteArray(UPSTREAM_BUFFER)
                try {
                    val input = client.getInputStream()
                    while (!closed.get()) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        bytesUp.addAndGet(read.toLong())
                        sink.tryEmit(PortForwardEvent.Data("up", read.toLong()))
                        mux.writeData(forward.dataId, buffer, 0, read)
                    }
                } catch (_: IOException) {
                    // Local client went away.
                }
                if (!closed.get()) mux.closeStream(forward.dataId)
            }

            fun fail() = close()

            fun close() {
                if (!closed.compareAndSet(false, true)) return
                runCatching { client.close() }
                down.offer(EMPTY)
            }
        }
    }

    /**
     * Starts a forward and returns once the API server accepted the upgrade *and* established the
     * first stream pair, so the caller can show a working bound port.
     */
    suspend fun start(
        connection: KubeConnection,
        namespace: String,
        pod: String,
        remotePort: Int,
        localPort: Int = 0,
    ): Session {
        val serverSocket = ServerSocket(localPort, 8, InetAddress.getByName("127.0.0.1"))
        val target = PortForwardTarget(namespace, pod, remotePort, serverSocket.localPort)

        var session: Session? = null
        try {
            val request = connection.newCall(
                "GET",
                "/api/v1/namespaces/$namespace/pods/$pod/portforward",
                mapOf("ports" to remotePort.toString()),
            ).request()
                .newBuilder()
                .header("Sec-WebSocket-Protocol", SpdyMultiplexer.SUBPROTOCOL)
                .build()

            val opened = CompletableFuture<Response>()
            val failed = CompletableFuture<Throwable>()

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.complete(response)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    session?.onBytes(bytes)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = Unit

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    session?.onSocketClosed()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isDone) failed.complete(t) else session?.onSocketFailure(t, response)
                }
            }

            val webSocket = connection.streamingClient.newWebSocket(request, listener)
            val mux = SpdyMultiplexer { frame -> webSocket.send(frame.toByteString()) }
            val created = Session(webSocket, serverSocket, mux, target)
            session = created

            withContext(Dispatchers.IO) {
                val settled = CompletableFuture.anyOf(opened, failed)
                runCatching { settled.get(ESTABLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                failed.getNow(null)?.let {
                    throw IOException("port-forward upgrade failed: ${it.message ?: failureDetail(it)}", it)
                }
                if (!opened.isDone) {
                    throw IOException("Timed out waiting for the API server to accept the port-forward")
                }
                val negotiated = opened.getNow(null)?.header("Sec-WebSocket-Protocol")
                if (negotiated != SpdyMultiplexer.SUBPROTOCOL) {
                    throw IOException(
                        "The API server did not negotiate ${SpdyMultiplexer.SUBPROTOCOL} (got ${negotiated ?: "none"})",
                    )
                }
                created.probe()
            }
            created.serve()
            return created
        } catch (t: Throwable) {
            session?.close()
            runCatching { serverSocket.close() }
            throw t
        }
    }

    private fun extractMessage(text: String): String =
        runCatching {
            val obj = KubeJson.parseToJsonElement(text) as? JsonObject
            obj?.str("message") ?: text
        }.getOrDefault(text.ifBlank { "port-forward failed" })

    /**
     * A readable stand-in for a throwable that carries no message.
     *
     * Deliberately not `::class.java.simpleName`: R8 renames app classes, so a minified build would
     * surface a token such as `pd` inside the message this screen shows the user. The category is
     * stable across builds and more useful than an internal class name.
     */
    private fun failureDetail(error: Throwable): String = when (error) {
        is IOException -> "no details reported (I/O error)"
        is IllegalStateException -> "no details reported (invalid state)"
        is IllegalArgumentException -> "no details reported (invalid input)"
        else -> "no details reported"
    }
}
