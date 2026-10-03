package dev.rafa.kubemobile.k8s

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A minimal but correct SPDY/3.1 multiplexer, used to carry the Kubernetes `pods/portforward`
 * tunnel over the WebSocket subprotocol `SPDY/3.1+portforward.k8s.io`.
 *
 * The tunnel is one continuous byte stream: the peer may split a single SPDY frame across several
 * WebSocket binary messages, or pack several frames into one. [onFrameReceived] therefore accepts
 * raw bytes and reassembles frames itself.
 *
 * Frame layout (all integers big-endian):
 *
 * ```
 * data     [streamId:4, high bit clear][flags<<24 | length:4][length bytes]
 * control  [0x8000 | version=3 :2][type:2][flags<<24 | length:4][length bytes]
 * ```
 *
 * Header blocks are `[pairCount:4]` followed by `[nameLen:4][name][valueLen:4][value]` pairs,
 * lower-cased, compressed with zlib (RFC1950) against the fixed SPDY/3.1 preset dictionary. One
 * compressor is shared per write direction and one decompressor per read direction, each flushed
 * after every header block, exactly as the Go `moby/spdystream` peer expects.
 *
 * Flow control is not implemented, matching `moby/spdystream` itself: inbound
 * SETTINGS/WINDOW_UPDATE are accepted and ignored, and data is never written before the peer's
 * SYN_REPLY established the stream.
 */
class SpdyMultiplexer(private val send: (ByteArray) -> Boolean) {

    /**
     * One multiplexed stream. Callbacks are invoked on the reader thread; stream state changes are
     * guarded by [lock].
     */
    private class Stream(
        val id: Int,
        val onData: (ByteArray) -> Unit,
        val onReset: (Int) -> Unit,
        val onEstablished: (Int) -> Unit,
        val onClose: (() -> Unit)?,
    ) {
        val lock = Any()
        var established = false
        var finSent = false
        var removed = false
    }

    private val streams = ConcurrentHashMap<Int, Stream>()
    private val nextStreamId = AtomicInteger(1)
    private val writeLock = Any()
    private val inflaterLock = Any()

    private var deflater: Deflater? = null
    private var inflater: Inflater? = null

    @Volatile
    private var closed = false

    /* ------------------------------ frame reassembly buffers ------------------------------ */

    private var acc = ByteArray(16 * 1024)
    private var accRead = 0
    private var accWrite = 0

    private var payload = ByteArray(MAX_DATA_FRAME)
    private var payloadNeed = 0
    private var payloadHave = 0

    // Fields of the frame currently being assembled, captured when its header was parsed.
    private var pendingControl = false
    private var pendingType = 0
    private var pendingFlags = 0
    private var pendingStreamId = 0

    /** Number of live streams (diagnostics). */
    val streamCount: Int get() = streams.size

    /**
     * Feeds raw tunnel bytes. Safe to call with a WebSocket binary message of any size; partial
     * frames are buffered until complete.
     */
    fun onFrameReceived(bytes: ByteArray) = onFrameReceived(bytes, 0, bytes.size)

    fun onFrameReceived(bytes: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        if (accWrite + length > acc.size) {
            if (accRead > 0) {
                System.arraycopy(acc, accRead, acc, 0, accWrite - accRead)
                accWrite -= accRead
                accRead = 0
            }
            if (accWrite + length > acc.size) acc = acc.copyOf(maxOf(acc.size * 2, accWrite + length))
        }
        System.arraycopy(bytes, offset, acc, accWrite, length)
        accWrite += length
        drain()
    }

    /**
     * Opens a stream. [finish] sets the FIN flag on the SYN_STREAM (used by the write-closed error
     * stream). Callbacks fire when the peer replies, sends data, or resets the stream.
     */
    fun createStream(
        headers: Map<String, String>,
        finish: Boolean,
        onData: (ByteArray) -> Unit,
        onReset: (Int) -> Unit,
        onEstablished: (Int) -> Unit,
        onClose: (() -> Unit)? = null,
    ): Int {
        val id = nextStreamId.getAndAdd(2)
        check(id > 0) { "SPDY stream ids exhausted" }
        val stream = Stream(id, onData, onReset, onEstablished, onClose)
        synchronized(stream.lock) { stream.finSent = finish }
        streams[id] = stream
        val block = compress(encodeHeaderBlock(headers))
        val payload = ByteArray(HEADER_SIZE_SYN_STREAM + block.size)
        putInt(payload, 0, id)
        putInt(payload, 4, 0) // associated stream id (no parent)
        payload[8] = 0 // priority 0 << 5
        payload[9] = 0 // credential slot
        System.arraycopy(block, 0, payload, 10, block.size)
        writeControl(TYPE_SYN_STREAM, if (finish) FLAG_FIN else 0, payload)
        return id
    }

    /** Writes TCP payload on [streamId], chunked into 16 KiB data frames. */
    fun writeData(streamId: Int, data: ByteArray, offset: Int, length: Int) {
        if (closed || length <= 0) return
        val stream = streams[streamId] ?: return
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, MAX_DATA_FRAME)
            synchronized(stream.lock) {
                if (stream.finSent || stream.removed || !stream.established) return
            }
            writeDataFrame(streamId, data, position, chunk, fin = false)
            position += chunk
            remaining -= chunk
        }
    }

    /** Half-closes the write side of [streamId] with an empty FIN data frame. */
    fun closeStream(streamId: Int) {
        val stream = streams[streamId] ?: return
        synchronized(stream.lock) {
            if (stream.finSent || stream.removed) return
            stream.finSent = true
        }
        writeDataFrame(streamId, EMPTY, 0, 0, fin = true)
    }

    /** Sends RST_STREAM (CANCEL) and drops the stream. */
    fun resetStream(streamId: Int) {
        val stream = streams.remove(streamId) ?: return
        stream.removed = true
        val payload = ByteArray(8)
        putInt(payload, 0, streamId)
        putInt(payload, 4, RST_CANCEL)
        writeControl(TYPE_RST_STREAM, 0, payload)
    }

    /** Sends PING with [id]. */
    fun ping(id: Int) {
        if (closed) return
        val payload = ByteArray(4)
        putInt(payload, 0, id)
        writeControl(TYPE_PING, 0, payload)
    }

    /** Tears down the multiplexer; further frames are ignored. */
    fun shutdown() {
        if (closed) return
        closed = true
        streams.clear()
        synchronized(inflaterLock) {
            runCatching { inflater?.end() }
            inflater = null
        }
        synchronized(writeLock) {
            runCatching { deflater?.end() }
            deflater = null
        }
    }

    /* ------------------------------------ frame writing ----------------------------------- */

    private fun writeControl(type: Int, flags: Int, payloadBytes: ByteArray) {
        if (closed) return
        val frame = ByteArray(8 + payloadBytes.size)
        frame[0] = 0x80.toByte()
        frame[1] = SPDY_VERSION.toByte()
        frame[2] = (type ushr 8).toByte()
        frame[3] = type.toByte()
        frame[4] = flags.toByte()
        frame[5] = (payloadBytes.size ushr 16).toByte()
        frame[6] = (payloadBytes.size ushr 8).toByte()
        frame[7] = payloadBytes.size.toByte()
        System.arraycopy(payloadBytes, 0, frame, 8, payloadBytes.size)
        emit(frame)
    }

    private fun writeDataFrame(streamId: Int, data: ByteArray, offset: Int, length: Int, fin: Boolean) {
        if (closed) return
        synchronized(writeLock) {
            if (closed) return
            // A WebSocket binary message is one contiguous byte array, so the 8-byte SPDY header and
            // the payload must live together in an exactly sized frame (no trailing slack).
            val frame = ByteArray(8 + length)
            frame[0] = (streamId ushr 24).toByte()
            frame[1] = (streamId ushr 16).toByte()
            frame[2] = (streamId ushr 8).toByte()
            frame[3] = streamId.toByte()
            frame[4] = (if (fin) FLAG_FIN else 0).toByte()
            frame[5] = (length ushr 16).toByte()
            frame[6] = (length ushr 8).toByte()
            frame[7] = length.toByte()
            if (length > 0) System.arraycopy(data, offset, frame, 8, length)
            send(frame)
        }
    }

    private fun emit(frame: ByteArray) {
        synchronized(writeLock) {
            if (closed) return
            send(frame)
        }
    }

    /* ----------------------------------- frame reading ------------------------------------ */

    private fun drain() {
        while (true) {
            if (payloadNeed == 0) {
                if (accWrite - accRead < 8) break
                val base = accRead
                val firstWord =
                    ((acc[base].toInt() and 0xFF) shl 24) or
                        ((acc[base + 1].toInt() and 0xFF) shl 16) or
                        ((acc[base + 2].toInt() and 0xFF) shl 8) or
                        (acc[base + 3].toInt() and 0xFF)
                val control = firstWord < 0
                val length = ((acc[base + 5].toInt() and 0xFF) shl 16) or
                    ((acc[base + 6].toInt() and 0xFF) shl 8) or
                    (acc[base + 7].toInt() and 0xFF)
                accRead += 8
                if (length > MAX_FRAME_PAYLOAD) {
                    // Bogus length: drop what we have rather than buffer forever.
                    accRead = accWrite
                    break
                }
                pendingControl = control
                pendingType = if (control) ((acc[base + 2].toInt() and 0xFF) shl 8) or (acc[base + 3].toInt() and 0xFF) else 0
                pendingFlags = acc[base + 4].toInt() and 0xFF
                // Data frames carry the full 31-bit stream id in their first four bytes.
                pendingStreamId = if (control) 0 else firstWord and 0x7FFFFFFF
                if (payload.size < length) payload = ByteArray(length)
                payloadNeed = length
                payloadHave = 0
                if (length == 0) {
                    dispatch(payload, 0, 0)
                    payloadNeed = 0
                }
                continue
            }

            val available = accWrite - accRead
            if (available <= 0) break
            val take = minOf(available, payloadNeed - payloadHave)
            System.arraycopy(acc, accRead, payload, payloadHave, take)
            accRead += take
            payloadHave += take
            if (payloadHave == payloadNeed) {
                val length = payloadNeed
                payloadNeed = 0
                payloadHave = 0
                dispatch(payload, 0, length)
            }
        }
        if (accRead == accWrite) {
            accRead = 0
            accWrite = 0
        }
    }

    private fun dispatch(data: ByteArray, offset: Int, length: Int) {
        if (pendingControl) {
            handleControl(pendingType, pendingFlags, data, offset, length)
        } else {
            handleData(pendingStreamId, pendingFlags, data, offset, length)
        }
    }

    private fun handleData(streamId: Int, flags: Int, data: ByteArray, offset: Int, length: Int) {
        val stream = streams[streamId] ?: return
        if (length > 0) {
            val copy = data.copyOfRange(offset, offset + length)
            runCatching { stream.onData(copy) }
        }
        if (flags and FLAG_FIN != 0) {
            streams.remove(streamId)
            runCatching { stream.onClose?.invoke() }
        }
    }

    private fun handleControl(type: Int, flags: Int, data: ByteArray, offset: Int, length: Int) {
        when (type) {
            TYPE_SYN_REPLY -> {
                if (length < 4) return
                val streamId = getInt(data, offset)
                val stream = streams[streamId] ?: return
                synchronized(stream.lock) { stream.established = true }
                runCatching { stream.onEstablished(streamId) }
            }

            TYPE_RST_STREAM -> {
                if (length < 4) return
                val streamId = getInt(data, offset)
                val stream = streams.remove(streamId) ?: return
                stream.removed = true
                runCatching { stream.onReset(streamId) }
            }

            TYPE_GOAWAY -> failAll()

            TYPE_PING -> {
                if (length < 4) return
                // Never initiate pings ourselves; echo the peer's id. The Go peer's parity check
                // treats a same-id reply as acknowledgement, so this cannot ping-pong.
                ping(getInt(data, offset))
            }

            // SETTINGS / HEADERS / WINDOW_UPDATE / SYN_STREAM need no handling: flow control is
            // not implemented (matching moby/spdystream) and the peer never opens streams to us.
            else -> Unit
        }
    }

    /** Fails every live stream: used for GOAWAY and connection teardown. */
    private fun failAll() {
        for (id in streams.keys.toList()) {
            val stream = streams.remove(id) ?: continue
            stream.removed = true
            runCatching { stream.onReset(id) }
        }
    }

    /* ------------------------------------- compression ------------------------------------ */

    private fun encodeHeaderBlock(headers: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream(64)
        writeInt(out, headers.size)
        for ((name, value) in headers) {
            val key = name.lowercase().toByteArray(Charsets.ISO_8859_1)
            val content = value.toByteArray(Charsets.ISO_8859_1)
            writeInt(out, key.size)
            out.write(key, 0, key.size)
            writeInt(out, content.size)
            out.write(content, 0, content.size)
        }
        return out.toByteArray()
    }

    private fun compress(input: ByteArray): ByteArray = synchronized(writeLock) {
        val def = deflater ?: Deflater(Deflater.DEFAULT_COMPRESSION).apply {
            setDictionary(SpdyDictionary.BYTES)
        }.also { deflater = it }
        def.setInput(input)
        val out = ByteArrayOutputStream(input.size + 32)
        val buffer = ByteArray(1024)
        while (!def.needsInput()) {
            val n = def.deflate(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        var flushed: Int
        do {
            flushed = def.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
            if (flushed > 0) out.write(buffer, 0, flushed)
        } while (flushed == buffer.size)
        return out.toByteArray()
    }

    /**
     * Decompresses one header block. The block is self-delimiting (the peer flushes after every
     * block), so the shared inflater is drained until it needs more input.
     */
    private fun decompress(input: ByteArray, offset: Int, length: Int): ByteArray = synchronized(inflaterLock) {
        val inf = inflater ?: Inflater().apply { setDictionary(SpdyDictionary.BYTES) }.also { inflater = it }
        inf.setInput(input, offset, length)
        val out = ByteArrayOutputStream(length * 2 + 16)
        val buffer = ByteArray(4096)
        while (!inf.needsInput()) {
            val n = try {
                inf.inflate(buffer)
            } catch (_: DataFormatException) {
                break
            }
            if (n <= 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_HEADER_BLOCK) break
        }
        return out.toByteArray()
    }

    /** Parses a compressed header block; exposed for tests and diagnostics. */
    fun decodeHeaders(block: ByteArray): Map<String, String> {
        val raw = decompress(block, 0, block.size)
        if (raw.size < 4) return emptyMap()
        var cursor = 4
        val count = getInt(raw, 0)
        val headers = LinkedHashMap<String, String>(count)
        var index = 0
        while (index < count && cursor + 4 <= raw.size) {
            val nameLength = getInt(raw, cursor)
            cursor += 4
            if (nameLength < 0 || cursor + nameLength > raw.size) break
            val name = String(raw, cursor, nameLength, Charsets.ISO_8859_1)
            cursor += nameLength
            if (cursor + 4 > raw.size) break
            val valueLength = getInt(raw, cursor)
            cursor += 4
            if (valueLength < 0 || cursor + valueLength > raw.size) break
            headers[name] = String(raw, cursor, valueLength, Charsets.ISO_8859_1)
            cursor += valueLength
            index++
        }
        return headers
    }

    /* --------------------------------------- helpers -------------------------------------- */

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }

    private fun getInt(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xFF) shl 24) or
            ((source[offset + 1].toInt() and 0xFF) shl 16) or
            ((source[offset + 2].toInt() and 0xFF) shl 8) or
            (source[offset + 3].toInt() and 0xFF)

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    companion object {
        const val SUBPROTOCOL = "SPDY/3.1+portforward.k8s.io"

        const val SPDY_VERSION = 3

        const val TYPE_SYN_STREAM = 1
        const val TYPE_SYN_REPLY = 2
        const val TYPE_RST_STREAM = 3
        const val TYPE_SETTINGS = 4
        const val TYPE_PING = 6
        const val TYPE_GOAWAY = 7
        const val TYPE_HEADERS = 8
        const val TYPE_WINDOW_UPDATE = 9

        const val FLAG_FIN = 0x01
        const val FLAG_SETTINGS_CLEAR_SETTINGS = 0x01
        const val FLAG_UNIDIRECTIONAL = 0x02

        const val RST_PROTOCOL_ERROR = 1
        const val RST_INVALID_STREAM = 2
        const val RST_REFUSED_STREAM = 3
        const val RST_UNSUPPORTED_VERSION = 4
        const val RST_CANCEL = 5
        const val RST_INTERNAL_ERROR = 6

        const val SETTING_INITIAL_WINDOW_SIZE = 7

        /** 16 KiB keeps every frame well under the 24-bit length field and typical socket sizes. */
        const val MAX_DATA_FRAME = 16 * 1024

        /** SPDY's payload length is 24 bits, so this is the hard ceiling. */
        const val MAX_FRAME_PAYLOAD = (1 shl 24) - 1

        const val MAX_HEADER_BLOCK = 256 * 1024

        const val HEADER_SIZE_SYN_STREAM = 10

        private val EMPTY = ByteArray(0)
    }
}
