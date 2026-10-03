package dev.rafa.kubemobile.ops

import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.data.KubeRepository
import dev.rafa.kubemobile.k8s.KubeConnection
import dev.rafa.kubemobile.k8s.KubeJson
import dev.rafa.kubemobile.k8s.KubeList
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.bool
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.k8s.stringMap
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** One Helm release revision, decoded from its release secret. */
data class HelmRelease(
    val name: String,
    val namespace: String,
    val revision: Int,
    val status: String,
    val chartName: String,
    val chartVersion: String,
    val appVersion: String,
    val description: String,
    val updated: String,
    val notes: String,
    val manifest: String,
    val values: String,
    val userValues: String,
    val secretName: String,
    val isV2: Boolean,
) {
    val id: String get() = "$namespace/$name"
}

/**
 * Helm 3 releases are secrets labelled `owner=helm`; Helm 2 (Tiller) releases are configmaps
 * labelled `OWNER=TILLER` (upper-case keys, as the v2 storage driver writes them).
 *
 * The two drivers do **not** share a payload format. Helm 3 stores `base64(gzip(json))`; Helm 2
 * stores `base64(gzip(protobuf))` of the `hapi.release.Release` message. This client has no
 * protobuf decoder, so Helm 2 payloads are recognised and skipped rather than being misread as
 * JSON — see [decodeConfigMap].
 */
object HelmOps {

    const val HELM3_SECRET_LABEL = "owner=helm"

    /** Helm 2's storage driver writes `OWNER`, `NAME`, `STATUS` and `VERSION` in upper case. */
    const val HELM2_CONFIGMAP_LABEL = "OWNER=TILLER"

    suspend fun listAll(connection: KubeConnection, namespaces: List<String>): List<HelmRelease> {
        val scope = namespaces.ifEmpty { listOf("") }
        val releases = LinkedHashMap<String, HelmRelease>()
        scope.forEach { namespace ->
            runCatching { listFor(connection, namespace) }.getOrNull()
                ?.forEach { release -> releases[release.id] = release }
        }
        return releases.values.sortedWith(compareBy({ it.namespace }, { it.name }))
    }

    /** Latest revision per release within one namespace (empty string = all namespaces). */
    suspend fun listFor(connection: KubeConnection, namespace: String): List<HelmRelease> {
        val out = LinkedHashMap<String, HelmRelease>()
        secretReleases(connection, namespace).forEach { release ->
            val key = release.id
            val existing = out[key]
            if (existing == null || release.revision > existing.revision) out[key] = release
        }
        configMapReleases(connection, namespace).forEach { release ->
            val key = release.id
            val existing = out[key]
            if (existing == null || release.revision > existing.revision) out[key] = release
        }
        return out.values.sortedBy { it.name }
    }

    suspend fun history(connection: KubeConnection, release: HelmRelease): List<HelmRelease> {
        val all = if (release.isV2) {
            configMapReleases(connection, release.namespace, release.name)
        } else {
            secretReleases(connection, release.namespace, release.name)
        }
        return all.sortedByDescending { it.revision }
    }

    private suspend fun secretReleases(
        connection: KubeConnection,
        namespace: String,
        name: String? = null,
    ): List<HelmRelease> {
        val selector = if (name != null) "$HELM3_SECRET_LABEL,name=$name" else HELM3_SECRET_LABEL
        val list = KubeList.from(
            connection.get(
                "/api/v1" + namespaceSegment(namespace) + "/secrets",
                mapOf("labelSelector" to selector, "limit" to "500"),
            ),
        )
        return list.items.mapNotNull { decodeSecret(it) }
    }

    private suspend fun configMapReleases(
        connection: KubeConnection,
        namespace: String,
        name: String? = null,
    ): List<HelmRelease> {
        // Tiller labels the release name as `NAME`, not `name`; mixing Helm 3's lower-case key
        // here made every Helm 2 history lookup match zero configmaps.
        val selector = if (name != null) "$HELM2_CONFIGMAP_LABEL,NAME=$name" else HELM2_CONFIGMAP_LABEL
        val list = KubeList.from(
            connection.get(
                "/api/v1" + namespaceSegment(namespace) + "/configmaps",
                mapOf("labelSelector" to selector, "limit" to "500"),
            ),
        )
        return list.items.mapNotNull { decodeConfigMap(it) }
    }

    private fun namespaceSegment(namespace: String): String =
        if (namespace.isBlank()) "" else "/namespaces/$namespace"

    /**
     * Decodes a Helm 3 release secret. Helm 3 stores `base64(gzip(json))`.
     */
    private fun decodeSecret(item: JsonObject): HelmRelease? {
        val json = decodeReleaseField(item) ?: return null
        return parse(json, secretName = item.str("metadata/name").orEmpty(), isV2 = false)
            ?.withStorageNamespace(item)
    }

    /**
     * Helm 2 stores `base64(gzip(protobuf))` of `hapi.release.Release` — a binary wire format this
     * client has no decoder for. The payload is rejected here rather than handed to the JSON parser,
     * so a Tiller configmap can never be mislabelled as a decoded release. Consequence: on a
     * cluster with only Helm 2 releases the list is empty; the plumbing above (`isV2`, `history`,
     * `uninstall`) stays in place for the day a protobuf decoder lands.
     */
    private fun decodeConfigMap(item: JsonObject): HelmRelease? {
        val json = decodeReleaseField(item)?.takeIf { it.trimStart().startsWith("{") } ?: return null
        return parse(json, secretName = item.str("metadata/name").orEmpty(), isV2 = true)
            ?.withStorageNamespace(item)
    }

    /**
     * Older charts store a release payload with no `namespace` field at all (e.g. a bare
     * `helm create` chart), which would otherwise leave the release un-scoped and break
     * `listFor`/`history` lookups. The storage object's own namespace is authoritative.
     */
    private fun HelmRelease.withStorageNamespace(item: JsonObject): HelmRelease =
        if (namespace.isNotBlank()) this else copy(namespace = item.str("metadata/namespace").orEmpty())

    /** Base64 text pattern Helm emits; used only to decide whether to peel another layer. */
    private val BASE64_TEXT = Regex("^[A-Za-z0-9+/=\\s]+$")

    /**
     * Decodes the `data.release` field of a Helm storage object into the release JSON.
     *
     * Helm does not always store the release as `base64(gzip(json))`: once the payload crosses
     * Helm's internal base64 length threshold (roughly 1 MiB of encoded text) the already-base64
     * text is base64-encoded *again*, giving `base64(base64(gzip(json)))`. That is why small
     * releases (e.g. a one-file chart) decode fine while a large one yields the literal string
     * `H4sIAAAA...` instead of JSON. Both shapes occur in the same cluster, so peel until we reach
     * something gzipped or JSON-looking, and degrade to null on anything malformed.
     */
    private fun decodeReleaseField(item: JsonObject): String? {
        val text = item.str("data/release")?.takeIf { it.isNotBlank() } ?: return null
        return decodeReleasePayload(text)
    }

    /** Peels Helm's base64 layers, then gunzips, then falls back to raw UTF-8. */
    fun decodeReleasePayload(encoded: String): String? {
        var current = encoded.trim()
        // At most a couple of layers: base64-of-base64-of-gzip is the deepest Helm produces.
        repeat(3) {
            val bytes = runCatching { Base64.getDecoder().decode(current) }.getOrNull() ?: return null
            if (isGzip(bytes)) return gunzip(bytes)
            val nested = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
            when {
                nested.startsWith("{") -> return nested
                nested.matches(BASE64_TEXT) -> current = nested
                else -> return nested
            }
        }
        return null
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun gunzip(bytes: ByteArray): String? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { stream ->
            String(stream.readBytes(), Charsets.UTF_8)
        }
    }.getOrNull()

    /** Kept for callers/tests: decodes a gzip blob, tolerating an already-decompressed payload. */
    fun decodeGzip(bytes: ByteArray): String? =
        if (isGzip(bytes)) gunzip(bytes) else runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()

    fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun parse(json: String, secretName: String, isV2: Boolean): HelmRelease? {
        val root = runCatching { KubeJson.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val info = root.objAt("info") ?: JsonObject(emptyMap())
        val metadata = root.objAt("chart")?.objAt("metadata") ?: JsonObject(emptyMap())
        val chart = root.objAt("chart")
        return HelmRelease(
            name = root.str("name") ?: return null,
            namespace = root.str("namespace").orEmpty(),
            revision = root.long("version")?.toInt() ?: 1,
            status = info.str("status").orEmpty(),
            chartName = metadata.str("name") ?: chart?.str("name").orEmpty(),
            chartVersion = metadata.str("version").orEmpty(),
            appVersion = metadata.str("appVersion").orEmpty(),
            description = info.str("description").orEmpty(),
            updated = info.str("last_deployed") ?: info.str("last_deployed").orEmpty(),
            notes = info.str("notes").orEmpty(),
            manifest = root.str("manifest").orEmpty(),
            values = chart?.let { chartValues(it) }.orEmpty(),
            userValues = root.objAt("config")?.let { encodePretty(it) }.orEmpty(),
            secretName = secretName,
            isV2 = isV2,
        )
    }

    /** `chart.values` holds the chart defaults; `config` holds whatever the user supplied. */
    private fun chartValues(chart: JsonObject): String = encodePretty(chart.objAt("values") ?: JsonObject(emptyMap()))

    private fun encodePretty(obj: JsonObject): String =
        dev.rafa.kubemobile.k8s.YamlIo.toYaml(obj)

    /* --------------------------------------------------------------------------------------- */
    /* Mutating operations                                                                      */
    /* --------------------------------------------------------------------------------------- */

    /**
     * Deletes the release exactly like `helm uninstall`: first every object from the newest
     * revision's manifest, then every stored revision secret. Resource kinds are resolved through
     * API discovery, so CRDs shipped by the chart are removed too. Hooks are not re-run, which the
     * confirmation dialog states explicitly.
     */
    suspend fun uninstall(
        repository: KubeRepository,
        session: ClusterSession,
        release: HelmRelease,
        deleteResources: Boolean,
    ): List<String> {
        val problems = mutableListOf<String>()
        val revisions = history(session.connection, release)

        if (deleteResources) {
            val manifest = revisions.firstOrNull()?.manifest.orEmpty()
            dev.rafa.kubemobile.k8s.YamlIo.parseAll(manifest).forEach { document ->
                val obj = document as? JsonObject ?: return@forEach
                val kind = obj.str("kind").orEmpty()
                val name = obj.str("metadata/name").orEmpty()
                if (kind.isBlank() || name.isBlank()) return@forEach
                val group = obj.str("apiVersion").orEmpty().substringBefore('/', "")
                    .takeIf { obj.str("apiVersion").orEmpty().contains('/') }
                    ?: ""
                val resource = session.catalog.forKind(kind, group) ?: session.catalog.forKind(kind)
                if (resource == null) {
                    problems += "$kind/$name: unknown resource type, delete it manually"
                    return@forEach
                }
                if (!resource.supports("delete")) {
                    problems += "$kind/$name: not deletable with these permissions"
                    return@forEach
                }
                val namespace = obj.str("metadata/namespace")
                runCatching { repository.delete(session, resource, name, namespace) }
                    .onFailure { error ->
                        if (!error.message.orEmpty().contains("not found", ignoreCase = true)) {
                            problems += "$kind/$name: ${error.message}"
                        }
                    }
            }
        }

        revisions.forEach { revision ->
            val path = if (revision.isV2) {
                "/api/v1/namespaces/${revision.namespace}/configmaps/${revision.secretName}"
            } else {
                "/api/v1/namespaces/${revision.namespace}/secrets/${revision.secretName}"
            }
            runCatching { session.connection.delete(path) }
                .onFailure { problems += "release storage ${revision.secretName}: ${it.message}" }
        }
        return problems
    }
}

/** Structured render of the resources contained in a release manifest. */
data class ManifestEntry(val kind: String, val name: String, val namespace: String?, val apiVersion: String)

fun HelmOps.manifestEntries(release: HelmRelease): List<ManifestEntry> =
    dev.rafa.kubemobile.k8s.YamlIo.parseAll(release.manifest).mapNotNull { document ->
        val obj = document as? JsonObject ?: return@mapNotNull null
        ManifestEntry(
            kind = obj.str("kind").orEmpty(),
            name = obj.str("metadata/name").orEmpty(),
            namespace = obj.str("metadata/namespace"),
            apiVersion = obj.str("apiVersion").orEmpty(),
        )
    }
