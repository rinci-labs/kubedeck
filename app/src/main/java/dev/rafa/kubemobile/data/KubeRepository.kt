package dev.rafa.kubemobile.data

import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.k8s.ApiCatalog
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.ClientFactory
import dev.rafa.kubemobile.k8s.Discovery
import dev.rafa.kubemobile.k8s.KubeConnection
import dev.rafa.kubemobile.k8s.KubeJson
import dev.rafa.kubemobile.k8s.KubeList
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** A live connection plus its discovery snapshot and the namespaces it can see. */
class ClusterSession(
    val profile: ClusterProfile,
    val connection: KubeConnection,
    var catalog: ApiCatalog = ApiCatalog.EMPTY,
) {
    var namespaces: List<String> = emptyList()
        private set

    suspend fun loadNamespaces(): List<String> {
        val list = NamespacedRepository(connection).list("namespaces", null)
        namespaces = list.items.mapNotNull { it.str("metadata/name") }.sorted()
        return namespaces
    }

    fun rememberNamespaces(values: List<String>) {
        namespaces = values
    }
}

/** Thin wrapper so the session can prefetch namespaces before the repository is constructed. */
internal class NamespacedRepository(private val connection: KubeConnection) {
    suspend fun list(resource: String, namespace: String?): KubeList =
        KubeList.from(connection.get("/api/v1" + (namespace?.let { "/namespaces/$it" } ?: "") + "/$resource"))
}

/**
 * Central access point for every cluster operation the app performs. Owns the connection cache,
 * discovery snapshots and list caches so navigation stays instant and requests stay bounded.
 */
class KubeRepository(
    private val context: android.content.Context,
    private val clientFactory: ClientFactory = ClientFactory.shared,
) {

    private val connectionLock = Mutex()
    private val connections = HashMap<String, KubeConnection>()
    private val sessions = HashMap<String, ClusterSession>()
    private val catalogFiles = File(context.filesDir, "discovery").apply { mkdirs() }

    private class ListCacheEntry(val list: KubeList, val fetchedAt: Long)
    private val listCache = HashMap<String, ListCacheEntry>()
    private val listCacheLock = Mutex()

    /** Returns (or creates) the session for a profile, loading discovery on first use. */
    suspend fun session(profile: ClusterProfile, forceDiscovery: Boolean = false): ClusterSession =
        withContext(Dispatchers.IO) {
            val existing = connectionLock.withLock { sessions[profile.id] }
            if (existing != null && !forceDiscovery && existing.catalog !== ApiCatalog.EMPTY) return@withContext existing

            val session = connectionLock.withLock {
                sessions.getOrPut(profile.id) {
                    val connection = connections.getOrPut(profile.id) { KubeConnection(profile, clientFactory) }
                    ClusterSession(profile, connection)
                }
            }

            if (forceDiscovery || session.catalog === ApiCatalog.EMPTY) {
                session.catalog = loadCatalog(session, forceDiscovery)
            }
            session
        }

    private suspend fun loadCatalog(session: ClusterSession, force: Boolean): ApiCatalog {
        val file = File(catalogFiles, "${session.profile.id}.json")
        if (!force && file.isFile && System.currentTimeMillis() - file.lastModified() < CATALOG_TTL_MS) {
            runCatching { decodeCatalog(file.readText()) }.getOrNull()?.let { return it }
        }
        val catalog = try {
            Discovery.load(session.connection)
        } catch (e: Exception) {
            // Offline or forbidden discovery: fall back to a cached snapshot if one exists.
            if (file.isFile) {
                runCatching { decodeCatalog(file.readText()) }.getOrNull()?.let { return it }
            }
            throw e
        }
        runCatching { file.writeText(encodeCatalog(catalog)) }
        return catalog
    }

    private fun encodeCatalog(catalog: ApiCatalog): String = buildJsonObject {
        put("coreVersions", JsonArray(catalog.coreVersions.map { JsonPrimitive(it) }))
        put(
            "groups",
            JsonArray(
                catalog.groups.map { group ->
                    buildJsonObject {
                        put("name", JsonPrimitive(group.name))
                        put("preferredVersion", JsonPrimitive(group.preferredVersion))
                        put("versions", JsonArray(group.versions.map { JsonPrimitive(it) }))
                    }
                },
            ),
        )
        put(
            "resources",
            JsonArray(
                catalog.resources.map { resource ->
                    buildJsonObject {
                        put("group", JsonPrimitive(resource.group))
                        put("version", JsonPrimitive(resource.version))
                        put("name", JsonPrimitive(resource.name))
                        put("kind", JsonPrimitive(resource.kind))
                        put("namespaced", JsonPrimitive(resource.namespaced))
                        resource.singularName?.let { put("singularName", JsonPrimitive(it)) }
                        put("verbs", JsonArray(resource.verbs.map { JsonPrimitive(it) }))
                        put("shortNames", JsonArray(resource.shortNames.map { JsonPrimitive(it) }))
                        put("categories", JsonArray(resource.categories.map { JsonPrimitive(it) }))
                    }
                },
            ),
        )
    }.toString()

    private fun decodeCatalog(text: String): ApiCatalog {
        val root = KubeJson.parseToJsonElement(text) as JsonObject
        val coreVersions = root.arrayAt("coreVersions")?.mapNotNull { it.toString().trim('"') }.orEmpty()
        val groups = root.arrayAt("groups")?.mapNotNull { it as? JsonObject }?.mapNotNull { group ->
            val name = group.str("name") ?: return@mapNotNull null
            dev.rafa.kubemobile.k8s.ApiGroup(
                name = name,
                preferredVersion = group.str("preferredVersion") ?: return@mapNotNull null,
                versions = group.arrayAt("versions")?.mapNotNull { it.toString().trim('"') }.orEmpty(),
            )
        }.orEmpty()
        val resources = root.arrayAt("resources")?.mapNotNull { it as? JsonObject }?.mapNotNull { r ->
            val name = r.str("name") ?: return@mapNotNull null
            val kind = r.str("kind") ?: return@mapNotNull null
            ApiResource(
                group = r.str("group").orEmpty(),
                version = r.str("version").orEmpty(),
                name = name,
                singularName = r.str("singularName"),
                kind = kind,
                namespaced = r["namespaced"].toString() == "true",
                verbs = r.arrayAt("verbs")?.mapNotNull { it.toString().trim('"') }?.toSet().orEmpty(),
                shortNames = r.arrayAt("shortNames")?.mapNotNull { it.toString().trim('"') }.orEmpty(),
                categories = r.arrayAt("categories")?.mapNotNull { it.toString().trim('"') }.orEmpty(),
            )
        }.orEmpty()
        return ApiCatalog(coreVersions, groups, resources)
    }

    fun invalidate(clusterId: String? = null) {
        synchronized(listCache) {
            if (clusterId == null) listCache.clear()
            else listCache.keys.filter { it.startsWith("$clusterId|") }.forEach { listCache.remove(it) }
        }
        clientFactory.invalidate()
        if (clusterId == null) {
            synchronized(connections) {
                connections.clear()
                sessions.clear()
            }
        } else {
            synchronized(connections) {
                connections.remove(clusterId)
                sessions.remove(clusterId)
            }
            File(catalogFiles, "$clusterId.json").delete()
        }
    }

    /* --------------------------------------------------------------------------------------- */
    /* Resource operations                                                                      */
    /* --------------------------------------------------------------------------------------- */

    suspend fun list(
        session: ClusterSession,
        resource: ApiResource,
        namespace: String? = null,
        labelSelector: String? = null,
        fieldSelector: String? = null,
        limit: Int = 500,
        continueToken: String? = null,
        useCache: Boolean = false,
    ): KubeList = withContext(Dispatchers.IO) {
        val query = buildMap {
            limit.takeIf { it > 0 }?.let { put("limit", it.toString()) }
            labelSelector?.takeIf { it.isNotBlank() }?.let { put("labelSelector", it) }
            fieldSelector?.takeIf { it.isNotBlank() }?.let { put("fieldSelector", it) }
            continueToken?.let { put("continue", it) }
        }
        val cacheKey = "${session.profile.id}|${resource.qualified}|${namespace.orEmpty()}|${labelSelector.orEmpty()}|${fieldSelector.orEmpty()}"
        if (useCache && continueToken == null) {
            val cached = listCacheLock.withLock { listCache[cacheKey] }
            if (cached != null && System.currentTimeMillis() - cached.fetchedAt < LIST_TTL_MS) return@withContext cached.list
        }

        val result = KubeList.from(session.connection.get(resource.basePath(namespace), query))
        if (continueToken == null) {
            listCacheLock.withLock { listCache[cacheKey] = ListCacheEntry(result, System.currentTimeMillis()) }
        }
        result
    }

    suspend fun get(session: ClusterSession, resource: ApiResource, name: String, namespace: String? = null): JsonObject =
        session.connection.get("${resource.basePath(namespace)}/$name") as? JsonObject
            ?: error("Empty response for ${resource.kind}/$name")

    suspend fun rawYaml(session: ClusterSession, resource: ApiResource, name: String, namespace: String? = null): String {
        val obj = get(session, resource, name, namespace)
        return dev.rafa.kubemobile.k8s.YamlIo.toYaml(obj)
    }

    suspend fun create(session: ClusterSession, obj: JsonObject, namespace: String? = null): JsonObject {
        val kind = obj.str("kind").orEmpty()
        val resource = session.catalog.forKind(kind) ?: error("Unknown kind $kind")
        val effectiveNamespace = namespace ?: obj.str("metadata/namespace")
        invalidate(session.profile.id)
        return session.connection.post(resource.collectionPath(effectiveNamespace), obj) as? JsonObject
            ?: JsonObject(emptyMap())
    }

    suspend fun apply(session: ClusterSession, obj: JsonObject): ApplyOutcome {
        val apiVersion = obj.str("apiVersion").orEmpty()
        val kind = obj.str("kind").orEmpty()
        val name = obj.str("metadata/name") ?: error("metadata.name is required")
        val resource = session.catalog.forKind(kind) ?: error("Unknown kind $kind")
        val namespace = obj.str("metadata/namespace")
        invalidate(session.profile.id)
        return try {
            val created = session.connection.post(
                resource.collectionPath(namespace),
                obj,
            )
            ApplyOutcome.Created(created as? JsonObject ?: obj)
        } catch (e: dev.rafa.kubemobile.k8s.KubeApiException) {
            if (!e.isConflict) throw e
            val updated = session.connection.put("${resource.basePath(namespace)}/$name", obj)
            ApplyOutcome.Updated(updated as? JsonObject ?: obj)
        }
    }

    suspend fun replace(session: ClusterSession, obj: JsonObject): JsonObject {
        val kind = obj.str("kind").orEmpty()
        val name = obj.str("metadata/name") ?: error("metadata.name is required")
        val namespace = obj.str("metadata/namespace")
        val resource = session.catalog.forKind(kind) ?: error("Unknown kind $kind")
        invalidate(session.profile.id)
        return session.connection.put("${resource.basePath(namespace)}/$name", obj) as? JsonObject ?: obj
    }

    suspend fun delete(
        session: ClusterSession,
        resource: ApiResource,
        name: String,
        namespace: String? = null,
        gracePeriodSeconds: Long? = null,
    ) {
        val body = gracePeriodSeconds?.let {
            buildJsonObject {
                put("apiVersion", JsonPrimitive("v1"))
                put("kind", JsonPrimitive("DeleteOptions"))
                put("gracePeriodSeconds", JsonPrimitive(it))
            }
        }
        invalidate(session.profile.id)
        session.connection.delete("${resource.basePath(namespace)}/$name", body)
    }

    /** Merge-patches a single field path on an object. */
    suspend fun patchField(
        session: ClusterSession,
        resource: ApiResource,
        name: String,
        namespace: String?,
        field: String,
        value: JsonElement,
    ): JsonObject {
        val body = buildJsonObject { put(field, value) }
        invalidate(session.profile.id)
        return session.connection.patch(
            "${resource.basePath(namespace)}/$name",
            body,
            KubeConnection.MERGE_PATCH,
        ) as? JsonObject ?: JsonObject(emptyMap())
    }

    /** Sets `spec.replicas` through the `scale` subresource so HPA-managed workloads stay sane. */
    suspend fun scale(session: ClusterSession, resource: ApiResource, name: String, namespace: String?, replicas: Int) {
        invalidate(session.profile.id)
        val body = buildJsonObject {
            put("apiVersion", JsonPrimitive("autoscaling/v1"))
            put("kind", JsonPrimitive("Scale"))
            put("metadata", buildJsonObject {
                put("name", JsonPrimitive(name))
                namespace?.let { put("namespace", JsonPrimitive(it)) }
            })
            put("spec", buildJsonObject { put("replicas", JsonPrimitive(replicas)) })
        }
        session.connection.put("${resource.basePath(namespace)}/$name/scale", body)
    }

    /** `kubectl rollout restart` — stamps the pod template so a new ReplicaSet is created. */
    suspend fun rolloutRestart(session: ClusterSession, resource: ApiResource, name: String, namespace: String?) {
        val stamp = java.time.Instant.now().toString()
        val body = buildJsonObject {
            put("spec", buildJsonObject {
                put("template", buildJsonObject {
                    put("metadata", buildJsonObject {
                        put("annotations", buildJsonObject {
                            put(
                                "kubectl.kubernetes.io/restartedAt",
                                JsonPrimitive(stamp),
                            )
                        })
                    })
                })
            })
        }
        invalidate(session.profile.id)
        session.connection.patch(
            "${resource.basePath(namespace)}/$name",
            body,
            KubeConnection.STRATEGIC_MERGE_PATCH,
        )
    }

    suspend fun eventsFor(
        session: ClusterSession,
        namespace: String?,
        kind: String?,
        name: String?,
        limit: Int = 200,
    ): KubeList {
        val events = session.catalog.forResource("events", "") ?: session.catalog.forResource("events")
            ?: return KubeList.EMPTY
        val selectors = buildList {
            if (!kind.isNullOrBlank()) add("involvedObject.kind=$kind")
            if (!name.isNullOrBlank()) add("involvedObject.name=$name")
        }.joinToString(",")
        return list(session, events, namespace, fieldSelector = selectors.ifBlank { null }, limit = limit)
    }

    companion object {
        private const val CATALOG_TTL_MS = 10 * 60 * 1000L
        private const val LIST_TTL_MS = 20_000L
    }
}

sealed interface ApplyOutcome {
    data class Created(val objectBody: JsonObject) : ApplyOutcome
    data class Updated(val objectBody: JsonObject) : ApplyOutcome
}
