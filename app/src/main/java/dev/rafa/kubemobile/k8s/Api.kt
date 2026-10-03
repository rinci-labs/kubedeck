package dev.rafa.kubemobile.k8s

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * One entry of the API discovery document. Everything the UI browses is derived from this, which is
 * what makes CRDs (Flux, ArgoCD, Istio, cert-manager, ...) work without per-operator code.
 */
data class ApiResource(
    val group: String,
    val version: String,
    val name: String,
    val singularName: String?,
    val kind: String,
    val namespaced: Boolean,
    val verbs: Set<String>,
    val shortNames: List<String>,
    val categories: List<String>,
) {
    /** `apps/v1/Deployment`, `v1/Pod`, `argoproj.io/v1alpha1/Application`. */
    val qualified: String get() = if (group.isEmpty()) "$version/$kind" else "$group/$version/$kind"

    val apiVersion: String get() = if (group.isEmpty()) version else "$group/$version"

    val isCore: Boolean get() = group.isEmpty()

    fun supports(verb: String): Boolean = verbs.contains(verb)

    /**
     * Collection URL, e.g. `/api/v1/namespaces/default/pods` or `/apis/apps/v1/deployments`.
     *
     * This is the POST target for create/apply, so it MUST carry the plural resource name —
     * without it the request lands on the namespace document (`/api/v1/namespaces/default`), which
     * answers 404, or on the APIResourceList document (`/api/v1`), which answers 405.
     */
    fun collectionPath(namespace: String?): String {
        val prefix = when {
            group.isEmpty() -> "/api/$version"
            else -> "/apis/$group/$version"
        }
        val scoped = if (namespaced && !namespace.isNullOrBlank()) "/namespaces/$namespace" else ""
        return "$prefix$scoped/$name"
    }

    /** The collection URL for this resource; callers append `/$objectName` to address one object. */
    fun basePath(namespace: String?): String = collectionPath(namespace)
}

data class ApiGroup(
    val name: String,
    val preferredVersion: String,
    val versions: List<String>,
)

/** Full discovery snapshot for one cluster. */
data class ApiCatalog(
    val coreVersions: List<String>,
    val groups: List<ApiGroup>,
    val resources: List<ApiResource>,
) {
    private val byKindAndGroup: Map<Pair<String, String>, ApiResource> =
        resources.groupBy { it.kind.lowercase() to it.group }
            .mapValues { (_, candidates) -> candidates.first() }

    private val byPluralAndGroup: Map<Pair<String, String>, ApiResource> =
        resources.associateBy { it.name.lowercase() to it.group }

    fun forKind(kind: String, group: String? = null): ApiResource? {
        val key = kind.lowercase()
        if (group != null) return byKindAndGroup[key to group]
        return resources.firstOrNull { it.kind.equals(kind, ignoreCase = true) }
    }

    fun forResource(plural: String, group: String? = null): ApiResource? {
        val key = plural.lowercase()
        if (group != null) return byPluralAndGroup[key to group]
        return resources.firstOrNull { it.name.equals(plural, ignoreCase = true) }
    }

    /** Resources that carry a `status` and are worth showing as workload tiles. */
    fun workloads(): List<ApiResource> = resources.filter {
        it.categories.contains("all") && it.namespaced
    }

    companion object {
        val EMPTY = ApiCatalog(emptyList(), emptyList(), emptyList())
    }
}

object Discovery {

    /** `v2` > `v1` > `v1beta3` > `v1beta2` > `v1alpha1`; unparsable versions sort lowest. */
    private val VERSION_PATTERN = Regex("^v(\\d+)(?:(alpha|beta)(\\d+))?$")

    private fun versionRank(version: String): Long {
        val match = VERSION_PATTERN.find(version.trim()) ?: return -1L
        val major = match.groupValues[1].toLongOrNull() ?: return -1L
        val stageNo = match.groupValues[3].toLongOrNull() ?: 0L
        // Stability tier within a major: GA beats beta beats alpha.
        val tier = when (match.groupValues[2]) {
            "" -> 2L
            "beta" -> 1L
            else -> 0L
        }
        return major * 10_000_000L + tier * 1_000_000L + stageNo
    }

    /**
     * Collapses the version union to exactly one [ApiResource] per `(group, plural name)`.
     *
     * Every caller — `ApiCatalog.forKind`/`forResource`, `KubeRepository.create/apply/replace` and
     * the UI's route key — assumes a single addressable `ApiResource` per kind, so the catalog must
     * be a union of what the server *serves* while each resource resolves to one version: the
     * group's `preferredVersion` when it defines the resource, otherwise the most stable version.
     * Keying on the plural name (not the kind) keeps distinct resources such as
     * `replicationcontrollers` separate.
     */
    private fun dedupe(resources: List<ApiResource>): List<ApiResource> {
        val byKey = LinkedHashMap<Pair<String, String>, ApiResource>()
        resources.forEach { resource ->
            val key = resource.group to resource.name.lowercase()
            val existing = byKey[key]
            byKey[key] = when {
                existing == null -> resource
                existing.version == resource.version -> existing
                versionRank(resource.version) > versionRank(existing.version) -> resource
                else -> existing
            }
        }
        return byKey.values.toList()
    }

    /** Fetches `/api`, `/apis` and every served group version's resource list. */
    suspend fun load(connection: KubeConnection): ApiCatalog {
        val core = connection.get("/api") as? JsonObject
        val groupsRoot = connection.get("/apis") as? JsonObject

        val coreVersions = core?.arrayAt("versions")?.mapNotNull { it.toString().trim('"') }.orEmpty()

        data class GroupVersion(val group: String, val version: String)

        val groupVersions = mutableListOf<GroupVersion>()
        val groups = mutableListOf<ApiGroup>()
        // The preferred version is not special-cased during the fetch any more: every served
        // version is visited so kinds that exist only in a non-preferred version (e.g. Flux
        // Alert/Provider under notification.toolkit.fluxcd.io/v1beta3) are visible. `dedupe`
        // then collapses overlaps back to a single addressable version per resource.
        groupsRoot?.arrayAt("groups")?.mapNotNull { it as? JsonObject }?.forEach { group ->
            val name = group.str("name") ?: return@forEach
            val versions = group.arrayAt("versions")?.mapNotNull { it as? JsonObject } ?: emptyList()
            val versionNames = versions.mapNotNull { it.str("version") }
            val preferred = group.objAt("preferredVersion")?.str("version")
                ?: versionNames.firstOrNull()
            if (preferred != null) {
                groups += ApiGroup(name, preferred, versionNames)
                versionNames.forEach { version -> groupVersions += GroupVersion(name, version) }
            }
        }

        val resources = mutableListOf<ApiResource>()

        // Core group (v1) is served under /api/v1 and carries all the built-in primitives.
        coreVersions.forEach { version ->
            runCatching {
                val body = connection.get("/api/$version") as? JsonObject ?: return@runCatching
                parseResources(body, group = "", version = version)?.let { resources += it }
            }
        }

        // Each group version is attempted independently: a version that 404s, is forbidden or
        // fails to parse simply contributes nothing rather than breaking the whole snapshot.
        groupVersions.forEach { gv ->
            runCatching {
                val body = connection.get("/apis/${gv.group}/${gv.version}") as? JsonObject
                    ?: return@runCatching
                parseResources(body, group = gv.group, version = gv.version)?.let { resources += it }
            }
        }

        return ApiCatalog(coreVersions, groups, dedupe(resources))
    }

    private fun parseResources(body: JsonObject, group: String, version: String): List<ApiResource>? {
        val list = body.arrayAt("resources") ?: return null
        return list.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val name = obj.str("name") ?: return@mapNotNull null
            // Sub-resources such as pods/log or deployments/scale are not browsable collections.
            if (name.contains('/')) return@mapNotNull null
            val kind = obj.str("kind") ?: return@mapNotNull null
            val verbs = obj.arrayAt("verbs")?.mapNotNull { it.toString().trim('"') }?.toSet().orEmpty()
            if (!verbs.contains("list") && !verbs.contains("get")) return@mapNotNull null
            ApiResource(
                group = group,
                version = version,
                name = name,
                singularName = obj.str("singularName"),
                kind = kind,
                namespaced = obj["namespaced"].toString() == "true",
                verbs = verbs,
                shortNames = obj.arrayAt("shortNames")?.mapNotNull { it.toString().trim('"') }.orEmpty(),
                categories = obj.arrayAt("categories")?.mapNotNull { it.toString().trim('"') }.orEmpty(),
            )
        }
    }
}

/** A decoded list response. */
data class KubeList(
    val items: List<JsonObject>,
    val continueToken: String?,
    val resourceVersion: String?,
    val remainingItemCount: Long?,
) {
    companion object {
        fun from(element: kotlinx.serialization.json.JsonElement): KubeList {
            val obj = element as? JsonObject ?: return KubeList(emptyList(), null, null, null)
            val items = (obj["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val metadata = obj.objAt("metadata")
            return KubeList(
                items = items,
                continueToken = metadata?.str("continue")?.takeIf { it.isNotBlank() },
                resourceVersion = metadata?.str("resourceVersion"),
                remainingItemCount = metadata?.long("remainingItemCount"),
            )
        }

        val EMPTY = KubeList(emptyList(), null, null, null)
    }
}

/** Pulls `metadata.name`, tolerating `generateName`-only objects. */
fun JsonObject.resourceName(): String = str("metadata/name").orEmpty()

fun JsonObject.resourceNamespace(): String? = str("metadata/namespace")

fun JsonObject.resourceKind(): String? = str("kind")

fun JsonObject.resourceApiVersion(): String? = str("apiVersion")
