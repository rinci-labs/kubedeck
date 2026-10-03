package dev.rafa.kubemobile.ops

import dev.rafa.kubemobile.data.ClusterSession
import dev.rafa.kubemobile.data.KubeRepository
import dev.rafa.kubemobile.k8s.KubeConnection
import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.bool
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Controller-specific operations. Everything here is a plain API-server call — no Flux, Argo CD or
 * Helm binaries involved — so it works from a phone on any RBAC-permitted cluster.
 */
object GitOps {

    /* -------------------------------------- Flux -------------------------------------- */

    /**
     * `flux reconcile <kind> <name>` == annotate the object with the reconcile request.
     *
     * `withSource` additionally annotates the source object the workload references (`flux` does
     * the same for `--with-source`). The referenced kind and name come from the reconciled object's
     * own `spec.sourceRef`/`spec.chartRef`, never from the reconciled resource's plural.
     */
    suspend fun reconcile(
        connection: KubeConnection,
        resource: dev.rafa.kubemobile.k8s.ApiResource,
        name: String,
        namespace: String,
        withSource: Boolean = false,
    ) {
        val requestAt = java.time.Instant.now().toString()
        val body = reconcileAnnotation(requestAt)
        connection.patch("${resource.basePath(namespace)}/$name", body, KubeConnection.STRATEGIC_MERGE_PATCH)
        if (!withSource) return

        // `--with-source` only makes sense for the Flux kinds that point at a source object.
        if (resource.group != "kustomize.toolkit.fluxcd.io" && resource.group != "helm.toolkit.fluxcd.io") return
        val target = sourceTarget(connection, resource, name, namespace) ?: return
        connection.patch(target, body, KubeConnection.STRATEGIC_MERGE_PATCH)
    }

    /**
     * Flux treats the annotation value as an opaque token: any change triggers a reconciliation, so
     * a fresh timestamp is what actually fires it.
     */
    private fun reconcileAnnotation(requestAt: String): JsonObject = buildJsonObject {
        put("metadata", buildJsonObject {
            put("annotations", buildJsonObject {
                put("reconcile.fluxcd.io/requestedAt", JsonPrimitive(requestAt))
            })
        })
    }

    /** Plural for the source kinds a Flux workload may reference. */
    private val SOURCE_PLURALS = mapOf(
        "GitRepository" to "gitrepositories",
        "OCIRepository" to "ocirepositories",
        "HelmRepository" to "helmrepositories",
        "Bucket" to "buckets",
    )

    /**
     * The URL of the source object a Flux `Kustomization`/`HelmRelease` refers to, read from the
     * live object's own `spec.sourceRef`/`spec.chartRef`. Returns null when the object references
     * no source we can address, so the caller skips the second patch instead of guessing a path
     * that would 404.
     */
    private suspend fun sourceTarget(
        connection: KubeConnection,
        resource: dev.rafa.kubemobile.k8s.ApiResource,
        name: String,
        namespace: String,
    ): String? {
        val object_ = runCatching {
            connection.get("${resource.basePath(namespace)}/$name") as? JsonObject
        }.getOrNull() ?: return null

        // HelmRelease v2 uses `spec.chartRef`; the older shape nests `sourceRef` under
        // `spec.chart.spec`. Both name the source object directly, so the ref's own kind and name
        // are what gets annotated — no synthetic HelmChart name is invented here.
        val ref = object_.objAt("spec/chartRef")
            ?: object_.objAt("spec/sourceRef")
            ?: object_.objAt("spec/chart/spec/sourceRef")
            ?: return null
        val kind = ref.str("kind") ?: return null
        val plural = SOURCE_PLURALS[kind] ?: return null
        val sourceName = ref.str("name")?.takeIf { it.isNotBlank() } ?: return null
        val sourceNamespace = ref.str("namespace")?.takeIf { it.isNotBlank() } ?: namespace
        val version = sourceApiVersion(connection) ?: return null
        return "/apis/source.toolkit.fluxcd.io/$version/namespaces/$sourceNamespace/$plural/$sourceName"
    }

    /**
     * The served version of `source.toolkit.fluxcd.io`, asked of the API server rather than assumed:
     * Flux ships these kinds under `v1beta2` on older releases and `v1` on current ones.
     */
    private suspend fun sourceApiVersion(connection: KubeConnection): String? {
        val group = runCatching { connection.get("/apis/source.toolkit.fluxcd.io") as? JsonObject }.getOrNull()
        return group?.objAt("preferredVersion")?.str("version")
            ?: group?.arrayAt("versions")?.firstOrNull()?.let { (it as? JsonObject)?.str("version") }
    }

    /** Flux suspend/resume is a boolean toggle on `spec.suspend`. */
    suspend fun setSuspended(
        connection: KubeConnection,
        resource: dev.rafa.kubemobile.k8s.ApiResource,
        name: String,
        namespace: String,
        suspended: Boolean,
    ) {
        val body = buildJsonObject {
            put("spec", buildJsonObject { put("suspend", JsonPrimitive(suspended)) })
        }
        connection.patch("${resource.basePath(namespace)}/$name", body, KubeConnection.STRATEGIC_MERGE_PATCH)
    }

    /* -------------------------------------- Argo CD ----------------------------------- */

    /**
     * Annotates an Application to trigger a refresh (`argocd app get --refresh`).
     *
     * Argo CD reads the annotation *value*: `normal` re-reads the rendered revision from the repo,
     * `hard` additionally invalidates the manifest cache. One merge patch carries the correct value.
     */
    suspend fun argocdRefresh(connection: KubeConnection, name: String, namespace: String, hard: Boolean) {
        val body = buildJsonObject {
            put("metadata", buildJsonObject {
                put("annotations", buildJsonObject {
                    put("argocd.argoproj.io/refresh", JsonPrimitive(if (hard) "hard" else "normal"))
                })
            })
        }
        connection.patch(
            "/apis/argoproj.io/v1alpha1/namespaces/$namespace/applications/$name",
            body,
            KubeConnection.MERGE_PATCH,
        )
    }

    /**
     * A sync is performed by patching `operation` on the Application, exactly what
     * `argocd app sync` does through the Kubernetes API.
     *
     * `syncStrategy` is omitted: the UI exposes no force-apply switch, and Argo CD's default
     * (hook-based apply) is what `argocd app sync` uses. Sending `apply: {}` would be an empty
     * object where the schema expects the `Apply` object — semantically "no force", i.e. a no-op
     * at best. `info` entries are `{name, value}` objects, not bare strings.
     */
    suspend fun argocdSync(
        connection: KubeConnection,
        name: String,
        namespace: String,
        revision: String? = null,
        prune: Boolean = false,
        dryRun: Boolean = false,
    ) {
        val operation = buildJsonObject {
            put("initiatedBy", buildJsonObject {
                put("username", JsonPrimitive("kubemobile"))
            })
            put("info", buildJsonArray {
                add(buildJsonObject {
                    put("name", JsonPrimitive("kubemobile"))
                    put("value", JsonPrimitive("Sync from Kube Mobile"))
                })
            })
            put("sync", buildJsonObject {
                revision?.takeIf { it.isNotBlank() }?.let { put("revision", JsonPrimitive(it)) }
                put("prune", JsonPrimitive(prune))
                put("dryRun", JsonPrimitive(dryRun))
            })
        }
        connection.patch(
            "/apis/argoproj.io/v1alpha1/namespaces/$namespace/applications/$name",
            buildJsonObject { put("operation", operation) },
            KubeConnection.MERGE_PATCH,
        )
    }

    /**
     * Stops a running sync.
     *
     * Argo CD's own `TerminateOperation` RPC sets `status.operationState.phase = Terminating` and
     * lets its controller pick the change up; the Applications CRD declares no `status` subresource
     * (verified against the cluster: `spec.versions[0].subresources` is empty), so `status` is an
     * ordinary field of the main resource and a client write to it is accepted and honoured by the
     * controller. Verified live: the phase persisted and the controller kept reporting it.
     *
     * What the original code got wrong is that it had no precondition. A patch on an Application
     * with no operation in flight just creates a stray `status.operationState.phase`, which the
     * controller ignores because `spec.operation == nil` — a silent no-op that still looked like
     * success. The phase is therefore checked first and the call fails loudly when there is nothing
     * to terminate.
     */
    suspend fun argocdTerminateOperation(connection: KubeConnection, name: String, namespace: String) {
        val path = "/apis/argoproj.io/v1alpha1/namespaces/$namespace/applications/$name"
        val current = connection.get(path) as? JsonObject
            ?: error("Application $name returned no body")
        val phase = current.str("status/operationState/phase")
        check(phase != null && phase !in TERMINAL_OPERATION_PHASES) {
            "No operation is in progress for $name"
        }
        // A merge patch of one leaf merges into the existing `operationState`; it cannot clobber the
        // controller's concurrent updates to the rest of the object.
        connection.patch(
            path,
            buildJsonObject {
                put("status", buildJsonObject {
                    put("operationState", buildJsonObject {
                        put("phase", JsonPrimitive("Terminating"))
                    })
                })
            },
            KubeConnection.MERGE_PATCH,
        )
    }

    /** Phases after which the controller has already cleared `spec.operation`. */
    private val TERMINAL_OPERATION_PHASES = setOf("Succeeded", "Failed", "Error")

    /* --------------------------------- Workload lifecycle ----------------------------- */

    /**
     * `kubectl rollout undo` — finds the target ReplicaSet from the current pod template hash and
     * re-points the Deployment at it.
     */
    suspend fun rolloutUndo(
        repository: KubeRepository,
        session: ClusterSession,
        resource: dev.rafa.kubemobile.k8s.ApiResource,
        name: String,
        namespace: String,
        toRevision: Long?,
    ): String {
        val deployment = repository.get(session, resource, name, namespace)
        val replicasets = session.catalog.forResource("replicasets", "apps") ?: error("ReplicaSets unavailable")
        val labelSelector = deployment.objAt("spec/selector/matchLabels")
            ?.entries?.joinToString(",") { (key, value) -> "$key=$value" }
            ?: "app=$name"
            ?: error("Rollout undo needs a matching ReplicaSet")
        val list = repository.list(session, replicasets, namespace, labelSelector = labelSelector, limit = 200)
        val revisions = list.items.mapNotNull { rs ->
            val revision = rs.objAt("metadata/annotations")
                ?.get("deployment.kubernetes.io/revision")
                ?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?.toLongOrNull()
                ?: return@mapNotNull null
            Triple(revision, rs.str("metadata/name").orEmpty(), rs.objAt("spec/template"))
        }.sortedByDescending { it.first }

        if (revisions.isEmpty()) error("No ReplicaSet history found for $name")
        val target = if (toRevision != null) {
            revisions.firstOrNull { it.first == toRevision }
                ?: error("Revision $toRevision is not available for rollback")
        } else {
            revisions.drop(1).firstOrNull() ?: error("$name has only one revision; nothing to roll back to")
        }

        val template = target.third ?: error("Revision ${target.first} has no pod template")
        repository.patchField(
            session,
            resource,
            name,
            namespace,
            "spec",
            buildJsonObject {
                put("template", template)
            },
        )
        return "${target.second} (revision ${target.first})"
    }

    /** `kubectl cordon` / `uncordon`. */
    suspend fun setNodeSchedulable(
        repository: KubeRepository,
        session: ClusterSession,
        nodeName: String,
        schedulable: Boolean,
    ) {
        val nodes = session.catalog.forResource("nodes") ?: error("Nodes unavailable")
        repository.patchField(session, nodes, nodeName, null, "spec", buildJsonObject {
            put("unschedulable", JsonPrimitive(!schedulable))
        })
    }

    /**
     * Evicts every pod on a node (`kubectl drain`), using the eviction subresource so
     * PodDisruptionBudgets are honoured. Returns the number of pods requested.
     */
    suspend fun drainNode(
        repository: KubeRepository,
        session: ClusterSession,
        nodeName: String,
        includeDaemonSets: Boolean = false,
    ): Int {
        val pods = session.catalog.forResource("pods", "") ?: error("Pods unavailable")
        val list = repository.list(session, pods, namespace = null, fieldSelector = "spec.nodeName=$nodeName", limit = 1000)
        var count = 0
        list.items.forEach { pod ->
            val name = pod.str("metadata/name") ?: return@forEach
            val namespace = pod.str("metadata/namespace") ?: return@forEach
            val owners = pod.arrayAt("metadata/ownerReferences")
                ?.mapNotNull { it as? JsonObject }
                .orEmpty()
            val controlledByNode = owners.any { it.str("kind") == "Node" }
            val isDaemonSet = owners.any { it.str("kind") == "DaemonSet" }
            if (controlledByNode) return@forEach
            if (isDaemonSet && !includeDaemonSets) return@forEach
            val payload = buildJsonObject {
                put("apiVersion", JsonPrimitive("policy/v1"))
                put("kind", JsonPrimitive("Eviction"))
                put("metadata", buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("namespace", JsonPrimitive(namespace))
                })
            }
            runCatching {
                session.connection.post(
                    "/api/v1/namespaces/$namespace/pods/$name/eviction",
                    payload,
                )
                count++
            }.onFailure {
                if (controlledByNode) return@onFailure
                // PDB-protected pods reject eviction; surface them so the operator can decide.
                throw it
            }
        }
        return count
    }

    /* -------------------------------------- CRDs -------------------------------------- */

    suspend fun listCrdVersions(connection: KubeConnection): List<JsonObject> =
        dev.rafa.kubemobile.k8s.KubeList.from(
            connection.get("/apis/apiextensions.k8s.io/v1/customresourcedefinitions", mapOf("limit" to "500")),
        ).items

    /** A CRD's stored versions plus which ones are served. */
    fun crdVersions(crd: JsonObject): List<Pair<String, Boolean>> =
        crd.arrayAt("spec/versions")?.mapNotNull { it as? JsonObject }?.map { version ->
            (version.str("name") ?: "") to (version.bool("served") == true)
        }.orEmpty()

    fun crdGroup(crd: JsonObject): String? = crd.str("spec/group")

    fun crdNames(crd: JsonObject): Pair<String?, String?> =
        crd.str("spec/names/kind") to crd.str("spec/names/plural")

    fun crdInstances(crd: JsonObject): Long? = crd.long("status/storedVersions").let { null }
}
