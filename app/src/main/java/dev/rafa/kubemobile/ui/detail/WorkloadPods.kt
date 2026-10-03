package dev.rafa.kubemobile.ui.detail

import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.resourceName
import dev.rafa.kubemobile.k8s.resourceNamespace
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.ui.AppViewModel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Resolves the pods a workload owns, shared by the detail screen's Pods tab and the merged log
 * view so the two can never disagree about what a workload contains.
 *
 * The workload's own `spec.selector.matchLabels` is applied as a **server-side** `labelSelector`,
 * so a large namespace is never walked client-side. A workload with no `matchLabels` — a Job made
 * by `kubectl run`, for instance — falls back to following `ownerReferences`, and a Deployment or
 * CronJob is followed one level of indirection through its ReplicaSets or Jobs.
 */
internal suspend fun resolveWorkloadPods(
    app: AppViewModel,
    kind: String,
    name: String,
    namespace: String?,
): List<JsonObject> {
    val session: ClusterSession = app.session ?: error("No cluster is connected")
    val podsResource = app.catalog.forResource("pods", "") ?: error("Pods are not served by this cluster")
    // The object's own body is optional: a caller that deep-links to a workload it cannot read still
    // deserves the ownership fallback rather than a hard failure.
    val objectBody = app.catalog.forKind(kind)?.let { app.repository.get(session, it, name, namespace) }
    val ownerUid = objectBody?.str("metadata/uid")

    val selector = objectBody?.let { selectorOf(it) }
    if (selector != null) {
        val matched = app.repository.list(
            session,
            podsResource,
            namespace,
            labelSelector = selector,
            limit = 500,
        ).items
        if (matched.isNotEmpty()) return orderForStreaming(matched)
    }
    return orderForStreaming(podsByOwnership(app, session, podsResource, kind, name, ownerUid, namespace))
}

/**
 * The API-server label-selector syntax for a workload's `spec.selector`.
 *
 * Covers both halves of a Kubernetes selector: `matchLabels` and every `matchExpressions` operator.
 * An operator this cannot express is reported as an error rather than dropped — silently omitting an
 * expression would *broaden* the selector and pull in pods that do not belong to the workload.
 */
internal fun selectorOf(body: JsonObject): String? {
    val selector = body.objAt("spec/selector") ?: return null
    val parts = mutableListOf<String>()
    selector.objAt("matchLabels")?.entries?.forEach { (key, value) ->
        parts += "$key=${(value as? JsonPrimitive)?.contentOrNull.orEmpty()}"
    }
    val expressions = selector.arrayAt("matchExpressions")?.mapNotNull { it as? JsonObject }.orEmpty()
    expressions.forEach { expression ->
        val key = expression.str("key") ?: error("A selector expression is missing its key")
        val operator = expression.str("operator").orEmpty()
        val values = expression.arrayAt("values")
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()
        when (operator) {
            "In" -> if (values.isEmpty()) {
                error("Selector In for \"$key\" has no values")
            } else {
                parts += "$key in (${values.joinToString(",")})"
            }

            "NotIn" -> if (values.isEmpty()) {
                error("Selector NotIn for \"$key\" has no values")
            } else {
                parts += "$key notin (${values.joinToString(",")})"
            }

            "Exists" -> parts += key
            "DoesNotExist" -> parts += "!$key"
            else -> error("Unsupported selector operator \"$operator\" for \"$key\"")
        }
    }
    return parts.joinToString(",").takeIf { it.isNotBlank() }
}

/** The Deployment revision stamp, the only annotation the ReplicaSets tab and undo picker need. */
internal const val REVISION_ANNOTATION = "deployment.kubernetes.io/revision"

/**
 * Reads one annotation by its literal key.
 *
 * `JsonObject.str("metadata/annotations/<key>")` cannot reach a namespaced annotation: the `/` in
 * keys such as `deployment.kubernetes.io/revision` is the same character `path()` splits on, so the
 * segment after the first slash is looked up as a nested object and the read comes back null.
 */
internal fun JsonObject.annotation(key: String): String? =
    objAt("metadata/annotations")?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }

private suspend fun podsByOwnership(
    app: AppViewModel,
    session: ClusterSession,
    podsResource: ApiResource,
    kind: String,
    name: String,
    ownerUid: String?,
    namespace: String?,
): List<JsonObject> {
    val holderKind = when (kind) {
        "Deployment" -> "ReplicaSet"
        "CronJob" -> "Job"
        else -> null
    }
    val ownerNames: Set<String> = if (holderKind != null) {
        val holder = app.catalog.forKind(holderKind) ?: return emptyList()
        app.repository.list(session, holder, namespace, limit = 500)
            .items
            .filter { ownedBy(it, name, kind, ownerUid) }
            .map { it.resourceName() }
            .toSet()
    } else {
        setOf(name)
    }
    if (ownerNames.isEmpty()) return emptyList()
    // Pods identify the intermediate owner kind as well as its name; the same name may exist in
    // a different controller kind, so do not attribute those pods to this workload.
    return app.repository.list(session, podsResource, namespace, limit = 500)
        .items
        .filter { pod -> ownedByAny(pod, ownerNames, holderKind) }
}

/**
 * True when `ownerReferences` names this owner. The UID and kind are matched when the caller has
 * them, so a deleted-then-recreated object with the same name is not credited with the old owner's
 * children; name alone stays the fallback for the intermediate ReplicaSet/Job hop, where only the
 * holder name is available.
 */
internal fun ownedBy(obj: JsonObject, name: String, kind: String? = null, uid: String? = null): Boolean =
    obj.arrayAt("metadata/ownerReferences")
        ?.mapNotNull { it as? JsonObject }
        ?.any { ref ->
            ref.str("name") == name &&
                (kind == null || ref.str("kind") == kind) &&
                (uid == null || ref.str("uid") == uid)
        } == true

private fun ownedByAny(obj: JsonObject, names: Set<String>, kind: String?): Boolean =
    obj.arrayAt("metadata/ownerReferences")
        ?.any { ref ->
            val owner = ref as? JsonObject ?: return@any false
            owner.str("name") in names && (kind == null || owner.str("kind") == kind)
        } == true

/**
 * Running and completed pods first, so a merge cap or a Pods tab never spends its budget on a pod
 * that cannot currently produce anything.
 */
internal fun orderForStreaming(pods: List<JsonObject>): List<JsonObject> = pods.sortedWith(
    compareBy(
        { (it.str("status/phase") ?: "") !in setOf("Running", "Succeeded") },
        { it.resourceName() },
    ),
)

/** Readiness, restarts, phase and node of one pod, as rendered by the Pods tab. */
internal fun podInfo(pod: JsonObject): PodInfo {
    val statuses = pod.arrayAt("status/containerStatuses")?.mapNotNull { it as? JsonObject }.orEmpty()
    val total = pod.arrayAt("spec/containers")?.size ?: statuses.size
    return PodInfo(
        name = pod.resourceName(),
        namespace = pod.resourceNamespace(),
        ready = statuses.count { it["ready"].toString() == "true" },
        total = total,
        restarts = statuses.sumOf { it.long("restartCount") ?: 0L },
        phase = pod.str("status/phase").orEmpty(),
        node = pod.str("spec/nodeName"),
        age = pod.str("metadata/creationTimestamp"),
    )
}

/** One pod owned by the object being viewed. */
data class PodInfo(
    val name: String,
    val namespace: String?,
    val ready: Int,
    val total: Int,
    val restarts: Long,
    val phase: String,
    val node: String?,
    val age: String?,
) {
    /** True when no container has reported a status yet, so "0/0 ready" is not shown as unhealthy. */
    val hasStatuses: Boolean get() = total > 0
}
