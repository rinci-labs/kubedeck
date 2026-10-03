package dev.rafa.kubemobile.k8s

import dev.rafa.kubemobile.config.ClusterProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ConnectionPool
import java.io.IOException
import java.net.Proxy
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/** Non-2xx response from the API server, carrying the Kubernetes `Status` body when present. */
class KubeApiException(
    val code: Int,
    val reason: String,
    val bodyText: String,
) : IOException(buildMessage(code, reason, bodyText)) {
    val isForbidden: Boolean get() = code == 403
    val isUnauthorized: Boolean get() = code == 401
    val isNotFound: Boolean get() = code == 404
    val isConflict: Boolean get() = code == 409

    companion object {
        private fun buildMessage(code: Int, reason: String, body: String): String {
            val detail = runCatching {
                val parsed = KubeJson.parseToJsonElement(body)
                val obj = parsed as? JsonObject
                obj?.get("message")?.jsonPrimitive?.content
                    ?: obj?.get("reason")?.jsonPrimitive?.content
            }.getOrNull()
            val status = reason.ifBlank { "HTTP $code" }
            return if (detail.isNullOrBlank()) status else "$status: $detail"
        }
    }
}

/**
 * One authenticated, TLS-configured channel to a single API server. Instances are cheap to hold
 * but share an OkHttp connection pool per cluster, so switching screens never re-handshakes.
 */
class KubeConnection(val profile: ClusterProfile, clientFactory: ClientFactory) {

    val client: OkHttpClient = clientFactory.build(profile, streaming = false)
    val streamingClient: OkHttpClient = clientFactory.build(profile, streaming = true)

    private val base: HttpUrl = requireNotNull(profile.baseUrl.toHttpUrlOrNull()) {
        "Invalid server URL: ${profile.server}"
    }

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = base.newBuilder()
        // Path is appended verbatim; kubeconfigs never carry a path prefix in practice, but the
        // builder handles one if present.
        path.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    private fun authenticated(request: Request.Builder): Request.Builder = when {
        !profile.token.isNullOrBlank() -> request.header("Authorization", "Bearer ${profile.token}")
        !profile.username.isNullOrBlank() ->
            request.header("Authorization", Credentials.basic(profile.username, profile.password ?: ""))

        else -> request
    }

    /**
     * Executes a request and returns the decoded JSON body. `JsonNull` for empty (204) responses.
     */
    suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        contentType: String = JSON_PATCH,
        streaming: Boolean = false,
    ): JsonElement = withContext(Dispatchers.IO) {
        val payload = body?.let { KubeJson.encodeToString(JsonElement.serializer(), it) }
        val builder = authenticated(Request.Builder().url(url(path, query)))
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
        val requestBody = when {
            payload == null -> null
            method == "PATCH" -> payload.toRequestBody(contentType.toMediaType())
            else -> payload.toRequestBody("application/json".toMediaType())
        }
        builder.method(method, requestBody)
        val target = if (streaming) streamingClient else client
        target.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) throw toException(response.code, response.message, text)
            if (text.isBlank()) JsonNull else runCatching { KubeJson.parseToJsonElement(text) }
                .getOrElse { throw KubeApiException(response.code, "Unparsable response", text) }
        }
    }

    /** Raw call for endpoints that stream or upgrade (logs, watch, exec, port-forward). */
    fun newCall(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        streaming: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
    ): okhttp3.Call {
        val payload = body?.let { KubeJson.encodeToString(JsonElement.serializer(), it) }
        val builder = authenticated(Request.Builder().url(url(path, query)))
            .header("User-Agent", USER_AGENT)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, payload?.toRequestBody("application/json".toMediaType()))
        return (if (streaming) streamingClient else client).newCall(builder.build())
    }

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JsonElement =
        execute("GET", path, query)

    suspend fun post(
        path: String,
        body: JsonElement,
        query: Map<String, String> = emptyMap(),
        contentType: String = "application/json",
    ): JsonElement = execute("POST", path, query, body, contentType)

    suspend fun put(path: String, body: JsonElement): JsonElement =
        execute("PUT", path, emptyMap(), body)

    suspend fun patch(path: String, body: JsonElement, type: String = JSON_PATCH): JsonElement =
        execute("PATCH", path, emptyMap(), body, type)

    suspend fun delete(
        path: String,
        body: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonElement = execute("DELETE", path, query, body)

    fun toException(code: Int, message: String, text: String): KubeApiException =
        KubeApiException(code, message, text)

    companion object {
        const val JSON_PATCH = "application/json-patch+json"
        const val MERGE_PATCH = "application/merge-patch+json"
        const val STRATEGIC_MERGE_PATCH = "application/strategic-merge-patch+json"
        const val USER_AGENT = "kubemobile/1.0 (android)"
    }
}

/** Builds TLS-configured OkHttp clients and caches them per cluster. */
class ClientFactory {

    private val pool = ConnectionPool(8, 5, TimeUnit.MINUTES)
    private val dispatcher = Dispatcher().apply { maxRequestsPerHost = 12 }

    private val cache = HashMap<String, OkHttpClient>()
    private val streamingCache = HashMap<String, OkHttpClient>()

    private fun cacheKey(profile: ClusterProfile): String = buildString {
        append(profile.id).append('|')
        append(profile.server).append('|')
        append(profile.insecureSkipTlsVerify).append('|')
        append(profile.caCertPem?.hashCode() ?: 0).append('|')
        append(profile.clientCertPem?.hashCode() ?: 0).append('|')
        append(profile.clientKeyPem?.hashCode() ?: 0).append('|')
        append(profile.proxyUrl.orEmpty()).append('|')
        append(profile.tlsServerName.orEmpty())
    }

    @Synchronized
    fun build(profile: ClusterProfile, streaming: Boolean): OkHttpClient {
        val key = cacheKey(profile) + if (streaming) "|s" else "|n"
        val cacheMap = if (streaming) streamingCache else cache
        cacheMap[key]?.let { return it }

        val builder = OkHttpClient.Builder()
            .connectionPool(pool)
            .dispatcher(dispatcher)
            .retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .readTimeout(if (streaming) 0 else 90, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .pingInterval(if (streaming) 30 else 0, TimeUnit.SECONDS)

        val trustManager = when {
            profile.insecureSkipTlsVerify -> Pem.insecureTrustManager()
            else -> Pem.trustManager(profile.caCertPem)
        }
        val keyManagers = Pem.keyManagers(profile.clientCertPem, profile.clientKeyPem)
        val context = SSLContext.getInstance("TLS").apply {
            init(keyManagers, Pem.trustManagers(trustManager), SecureRandom())
        }
        builder.sslSocketFactory(context.socketFactory, trustManager)
        if (profile.insecureSkipTlsVerify) {
            builder.hostnameVerifier { _, _ -> true }
        } else if (!profile.tlsServerName.isNullOrBlank()) {
            val expected = profile.tlsServerName
            builder.hostnameVerifier { _, session -> verifyHostname(expected, session.peerCertificates) }
        }

        profile.proxyUrl?.let { proxyUrl ->
            runCatching {
                val parsed = proxyUrl.toHttpUrlOrNull() ?: return@runCatching
                builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(parsed.host, parsed.port)))
            }
        }

        val client = builder.build()
        cacheMap[key] = client
        return client
    }

    @Synchronized
    fun invalidate() {
        cache.clear()
        streamingCache.clear()
    }

    /** Verifies the presented certificate against `tls-server-name` instead of the URL host. */
    private fun verifyHostname(serverName: String, certificates: Array<java.security.cert.Certificate>): Boolean =
        runCatching {
            val certificate = certificates.firstOrNull() as? java.security.cert.X509Certificate
                ?: return@runCatching false
            okhttp3.internal.tls.OkHostnameVerifier.verify(serverName, certificate)
        }.getOrDefault(false)

    companion object {
        val shared = ClientFactory()
    }
}

/** Reads a `Response` body as text. */
fun Response.textOrEmpty(): String = runCatching { body.string() }.getOrDefault("")
