package dev.rafa.kubemobile.ops

import dev.rafa.kubemobile.k8s.arrayAt
import dev.rafa.kubemobile.k8s.bool
import dev.rafa.kubemobile.k8s.long
import dev.rafa.kubemobile.k8s.objAt
import dev.rafa.kubemobile.k8s.str
import dev.rafa.kubemobile.k8s.stringMap
import kotlinx.serialization.json.JsonObject

/** A `status.conditions[]` entry, shared by every controller that follows the metav1 convention. */
data class Condition(
    val type: String,
    val status: String,
    val reason: String,
    val message: String,
    val lastTransitionTime: String,
    val observedGeneration: Long?,
) {
    val isTrue: Boolean get() = status.equals("True", ignoreCase = true)
    val isFalse: Boolean get() = status.equals("False", ignoreCase = true)
}

/** How a resource looks at a glance in a list row. */
data class ResourceHealth(
    val label: String,
    val detail: String? = null,
    val tone: Tone = Tone.NEUTRAL,
    val progress: Float? = null,
) {
    enum class Tone { OK, WARN, BAD, PROGRESS, NEUTRAL }
}

data class StatusRow(val label: String, val value: String)

/**
 * Interprets `status` for the built-in workload kinds and for controllers that follow the
 * conditions convention (Flux, Argo CD, cert-manager, ...). The generic path is what lets every
 * CRD render a meaningful badge without any per-operator code.
 */
object Status {

    fun conditions(obj: JsonObject): List<Condition> =
        obj.arrayAt("status/conditions")?.mapNotNull { entry ->
            val node = entry as? JsonObject ?: return@mapNotNull null
            Condition(
                type = node.str("type").orEmpty(),
                status = node.str("status").orEmpty(),
                reason = node.str("reason").orEmpty(),
                message = node.str("message").orEmpty(),
                lastTransitionTime = node.str("lastTransitionTime").orEmpty(),
                observedGeneration = node.long("observedGeneration"),
            )
        }.orEmpty()

    fun readyCondition(obj: JsonObject): Condition? {
        val all = conditions(obj)
        return all.firstOrNull { it.type == "Ready" }
            ?: all.firstOrNull { it.type == "Synced" }
            ?: all.firstOrNull { it.type == "Available" && it.isTrue }
            ?: all.firstOrNull { it.isFalse }
    }

    /** True when the controller has not yet observed the current generation. */
    fun isStale(obj: JsonObject): Boolean {
        val generation = obj.long("metadata/generation") ?: return false
        val observed = obj.long("status/observedGeneration") ?: return false
        return generation > observed
    }

    fun health(obj: JsonObject, kind: String): ResourceHealth = when (kind) {
        "Pod" -> podHealth(obj)
        "Deployment", "ReplicaSet", "StatefulSet", "DaemonSet", "ReplicationController" -> workloadHealth(obj, kind)
        "Job" -> jobHealth(obj)
        "CronJob" -> cronJobHealth(obj)
        "PersistentVolumeClaim" -> pvcHealth(obj)
        "Node" -> nodeHealth(obj)
        "Namespace" -> namespaceHealth(obj)
        "Service" -> ResourceHealth(serviceType(obj) ?: "Service")
        "HelmRelease" -> helmReleaseHealth(obj)
        "Application", "ApplicationSet" -> argoHealth(obj)
        "Event" -> ResourceHealth(obj.str("type") ?: "Normal")
        else -> conditionHealth(obj)
    }

    private fun podHealth(pod: JsonObject): ResourceHealth {
        val phase = pod.str("status/phase") ?: "Unknown"
        val containers = pod.arrayAt("status/containerStatuses")?.mapNotNull { it as? JsonObject }.orEmpty()
        val ready = containers.count { it.bool("ready") == true }
        val total = containers.size
        val restarts = containers.sumOf { it.long("restartCount") ?: 0L }
        val reasons = containers.mapNotNull { status ->
            val waiting = status.objAt("state/waiting/")
            val reason = status.str("state/waiting/reason")
                ?: status.str("state/terminated/reason")
            reason
        }.distinct()

        val deleting = pod.str("metadata/deletionTimestamp") != null
        val succeeded = phase == "Succeeded"
        val tone = when {
            deleting -> ResourceHealth.Tone.WARN
            phase == "Failed" -> ResourceHealth.Tone.BAD
            succeeded -> ResourceHealth.Tone.OK
            phase == "Running" && ready == total && total > 0 -> ResourceHealth.Tone.OK
            phase == "Pending" -> ResourceHealth.Tone.PROGRESS
            else -> ResourceHealth.Tone.WARN
        }
        val label = when {
            deleting -> "Terminating"
            reasons.isNotEmpty() && tone != ResourceHealth.Tone.OK -> reasons.first()
            else -> phase
        }
        val detail = buildList {
            if (total > 0) add("$ready/$total ready")
            if (restarts > 0) add("$restarts restarts")
            pod.str("status/podIP")?.let { add(it) }
        }.joinToString(" · ").ifBlank { null }
        return ResourceHealth(label, detail, tone)
    }

    private fun workloadHealth(obj: JsonObject, kind: String): ResourceHealth {
        val desired = obj.long("spec/replicas") ?: obj.long("status/desiredNumberScheduled") ?: 1L
        val ready = when (kind) {
            "DaemonSet" -> obj.long("status/numberReady") ?: 0L
            else -> obj.long("status/readyReplicas") ?: 0L
        }
        val available = obj.long("status/availableReplicas")
            ?: obj.long("status/numberAvailable")
            ?: ready
        val updated = obj.long("status/updatedReplicas") ?: obj.long("status/updatedNumberScheduled")
        val stale = isStale(obj)
        val conditions = conditions(obj)

        val progressing = conditions.firstOrNull { it.type == "Progressing" }
        val availableCond = conditions.firstOrNull { it.type == "Available" }
        val replicaFailure = conditions.firstOrNull { it.type == "ReplicaFailure" && it.isTrue }

        val tone = when {
            replicaFailure != null -> ResourceHealth.Tone.BAD
            desired == 0L -> ResourceHealth.Tone.NEUTRAL
            ready >= desired && !stale -> ResourceHealth.Tone.OK
            ready == 0L -> ResourceHealth.Tone.BAD
            else -> ResourceHealth.Tone.WARN
        }
        val label = when {
            replicaFailure != null -> "Failed"
            desired == 0L -> "Scaled to 0"
            stale -> "Updating"
            ready >= desired -> "Ready"
            availableCond?.isFalse == true -> "Unavailable"
            progressing?.reason.orEmpty().isNotBlank() -> progressing!!.reason
            else -> "Progressing"
        }
        val detail = buildList {
            add("$ready/$desired ready")
            available.takeIf { it != ready }?.let { add("$it available") }
            updated?.takeIf { it != desired }?.let { add("$it updated") }
        }.joinToString(" · ")
        val progress = if (desired > 0) (ready.toFloat() / desired.toFloat()).coerceIn(0f, 1f) else null
        return ResourceHealth(label, detail, tone, progress)
    }

    private fun jobHealth(obj: JsonObject): ResourceHealth {
        val succeeded = obj.long("status/succeeded") ?: 0L
        val failed = obj.long("status/failed") ?: 0L
        val completions = obj.long("spec/completions") ?: 1L
        val active = obj.long("status/active") ?: 0L
        val condition = conditions(obj).firstOrNull { it.type == "Failed" && it.isTrue }
        val tone = when {
            condition != null -> ResourceHealth.Tone.BAD
            succeeded >= completions -> ResourceHealth.Tone.OK
            active > 0L -> ResourceHealth.Tone.PROGRESS
            failed > 0L -> ResourceHealth.Tone.WARN
            else -> ResourceHealth.Tone.NEUTRAL
        }
        val label = when {
            condition != null -> "Failed"
            succeeded >= completions -> "Complete"
            active > 0L -> "Running"
            else -> "Pending"
        }
        return ResourceHealth(label, "$succeeded/$completions succeeded", tone)
    }

    private fun cronJobHealth(obj: JsonObject): ResourceHealth {
        val suspended = obj.bool("spec/suspend") == true
        val active = obj.arrayAt("status/active")?.size ?: 0
        val last = obj.str("status/lastScheduleTime")
        return ResourceHealth(
            label = if (suspended) "Suspended" else if (active > 0) "Active" else "Idle",
            detail = last?.let { "last $it" },
            tone = if (suspended) ResourceHealth.Tone.WARN else ResourceHealth.Tone.OK,
        )
    }

    private fun pvcHealth(obj: JsonObject): ResourceHealth {
        val phase = obj.str("status/phase") ?: "Unknown"
        val capacity = obj.str("status/capacity/storage")
        val tone = when (phase) {
            "Bound" -> ResourceHealth.Tone.OK
            "Lost" -> ResourceHealth.Tone.BAD
            else -> ResourceHealth.Tone.WARN
        }
        return ResourceHealth(phase, capacity, tone)
    }

    private fun nodeHealth(obj: JsonObject): ResourceHealth {
        val readyCond = conditions(obj).firstOrNull { it.type == "Ready" }
        val unschedulable = obj.bool("spec/unschedulable") == true
        val tone = when {
            readyCond == null -> ResourceHealth.Tone.NEUTRAL
            readyCond.isTrue && unschedulable -> ResourceHealth.Tone.WARN
            readyCond.isTrue -> ResourceHealth.Tone.OK
            else -> ResourceHealth.Tone.BAD
        }
        val label = when {
            readyCond?.isTrue == true && unschedulable -> "Ready,SchedulingDisabled"
            readyCond?.isTrue == true -> "Ready"
            readyCond != null -> "NotReady"
            else -> "Unknown"
        }
        val roles = obj.stringMap("metadata/labels")
            .keys.filter { it.startsWith("node-role.kubernetes.io/") }
            .joinToString(",") { it.removePrefix("node-role.kubernetes.io/") }
        return ResourceHealth(label, roles.ifBlank { null }, tone)
    }

    private fun namespaceHealth(obj: JsonObject): ResourceHealth {
        val phase = obj.str("status/phase") ?: "Active"
        return ResourceHealth(
            phase,
            tone = if (phase == "Active") ResourceHealth.Tone.OK else ResourceHealth.Tone.WARN,
        )
    }

    private fun serviceType(obj: JsonObject): String? = obj.str("spec/type")

    /** Flux HelmRelease roll-up: Ready condition, revision and suspend state. */
    private fun helmReleaseHealth(obj: JsonObject): ResourceHealth {
        val suspended = obj.bool("spec/suspend") == true
        val ready = readyCondition(obj)
        val failing = obj.long("status/failures")
        val stale = isStale(obj)
        val tone = when {
            suspended -> ResourceHealth.Tone.WARN
            ready == null -> ResourceHealth.Tone.NEUTRAL
            ready.isTrue -> ResourceHealth.Tone.OK
            ready.reason.contains("Progressing", true) -> ResourceHealth.Tone.PROGRESS
            ready.reason.contains("Retry", true) || ready.reason.contains("Upgrade", true) -> ResourceHealth.Tone.WARN
            else -> ResourceHealth.Tone.BAD
        }
        val label = when {
            suspended -> "Suspended"
            ready == null -> "Unknown"
            stale -> "Reconciling"
            ready.reason.isNotBlank() -> ready.reason
            ready.isTrue -> "Ready"
            else -> "NotReady"
        }
        val revision = obj.str("status/lastAppliedRevision") ?: obj.str("status/lastAttemptedRevision")
        val detail = buildList {
            revision?.let { add("rev $it") }
            failing?.takeIf { it > 0 }?.let { add("$it failures") }
        }.joinToString(" · ").ifBlank { null }
        return ResourceHealth(label, detail, tone)
    }

    /** Argo CD Application roll-up: sync status x health status. */
    private fun argoHealth(obj: JsonObject): ResourceHealth {
        val sync = obj.str("status/sync/status")
        val health = obj.str("status/health/status")
        val revision = obj.str("status/sync/revision")?.take(7)
        val progressing = obj.objAt("status/operationState/phase") != null &&
            obj.str("status/operationState/phase").orEmpty().let { it == "Running" || it == "Terminating" }
        val tone = when {
            progressing -> ResourceHealth.Tone.PROGRESS
            health.equals("Healthy", true) && sync.equals("Synced", true) -> ResourceHealth.Tone.OK
            health.equals("Degraded", true) || health.equals("Missing", true) -> ResourceHealth.Tone.BAD
            health.equals("Progressing", true) || sync.equals("OutOfSync", true) -> ResourceHealth.Tone.WARN
            else -> ResourceHealth.Tone.NEUTRAL
        }
        val label = buildList {
            health?.let { add(it) }
            sync?.let { add(it) }
        }.joinToString(" · ").ifBlank { "Unknown" }
        return ResourceHealth(label, revision?.let { "rev $it" }, tone)
    }

    /** Generic fallback: first Ready/Synced/Available condition, else nothing. */
    private fun conditionHealth(obj: JsonObject): ResourceHealth {
        val ready = readyCondition(obj) ?: return ResourceHealth("—", null, ResourceHealth.Tone.NEUTRAL)
        val tone = when {
            ready.isTrue -> ResourceHealth.Tone.OK
            ready.reason.contains("Progressing", true) || ready.reason.contains("Reconciling", true) ->
                ResourceHealth.Tone.PROGRESS

            else -> ResourceHealth.Tone.BAD
        }
        return ResourceHealth(
            label = ready.reason.ifBlank { if (ready.isTrue) "Ready" else "NotReady" },
            detail = ready.message.take(80).ifBlank { null },
            tone = tone,
        )
    }

    /** Detail rows shown on the resource detail screen, tailored to the kind. */
    fun detailRows(obj: JsonObject, kind: String): List<StatusRow> = buildList {
        when (kind) {
            "Pod" -> {
                obj.str("status/phase")?.let { add(StatusRow("Phase", it)) }
                obj.str("spec/nodeName")?.let { add(StatusRow("Node", it)) }
                obj.str("status/podIP")?.let { add(StatusRow("Pod IP", it)) }
                obj.str("status/hostIP")?.let { add(StatusRow("Host IP", it)) }
                obj.str("spec/serviceAccountName")?.let { add(StatusRow("Service account", it)) }
                obj.str("status/qosClass")?.let { add(StatusRow("QoS", it)) }
                obj.str("spec/restartPolicy")?.let { add(StatusRow("Restart policy", it)) }
                obj.str("status/startTime")?.let { add(StatusRow("Started", it)) }
            }

            "Deployment", "ReplicaSet", "StatefulSet" -> {
                obj.long("spec/replicas")?.let { add(StatusRow("Replicas", it.toString())) }
                obj.long("status/readyReplicas")?.let { add(StatusRow("Ready", it.toString())) }
                obj.long("status/updatedReplicas")?.let { add(StatusRow("Updated", it.toString())) }
                obj.long("status/availableReplicas")?.let { add(StatusRow("Available", it.toString())) }
                obj.str("spec/strategy/type")?.let { add(StatusRow("Strategy", it)) }
                obj.str("spec/serviceName")?.let { add(StatusRow("Service", it)) }
            }

            "DaemonSet" -> {
                obj.long("status/desiredNumberScheduled")?.let { add(StatusRow("Desired", it.toString())) }
                obj.long("status/numberReady")?.let { add(StatusRow("Ready", it.toString())) }
                obj.long("status/numberAvailable")?.let { add(StatusRow("Available", it.toString())) }
                obj.long("status/numberMisscheduled")?.let { add(StatusRow("Misscheduled", it.toString())) }
            }

            "Service" -> {
                obj.str("spec/type")?.let { add(StatusRow("Type", it)) }
                obj.str("spec/clusterIP")?.let { add(StatusRow("Cluster IP", it)) }
                obj.arrayAt("spec/ports")?.mapNotNull { it as? JsonObject }?.forEach { port ->
                    add(
                        StatusRow(
                            "Port",
                            buildString {
                                append(port.long("port") ?: 0)
                                port.str("name")?.let { append(" ($it)") }
                                port.long("nodePort")?.let { append(" nodePort=$it") }
                                port.str("protocol")?.let { append(" $it") }
                            },
                        ),
                    )
                }
                obj.arrayAt("status/loadBalancer/ingress")?.mapNotNull { it as? JsonObject }?.forEach { ingress ->
                    add(StatusRow("Load balancer", ingress.str("ip") ?: ingress.str("hostname") ?: ""))
                }
            }

            "Ingress" -> {
                obj.str("spec/ingressClassName")?.let { add(StatusRow("Class", it)) }
                obj.arrayAt("spec/rules")?.mapNotNull { it as? JsonObject }?.forEach { rule ->
                    val host = rule.str("host").orEmpty()
                    rule.objAt("http")?.arrayAt("paths")?.mapNotNull { it as? JsonObject }?.forEach { path ->
                        val service = path.objAt("backend/service")
                        val name = path.str("backend/service/name") ?: service?.str("name")
                        val port = path.str("backend/service/port/number")
                            ?: path.str("backend/service/port/name")
                            ?: service?.str("port/number")
                            ?: service?.str("port/name")
                        add(StatusRow(host.ifBlank { "*" }, "${path.str("path").orEmpty()} -> $name:$port"))
                    }
                }
            }

            "PersistentVolumeClaim" -> {
                obj.str("status/phase")?.let { add(StatusRow("Phase", it)) }
                obj.str("spec/storageClassName")?.let { add(StatusRow("Storage class", it)) }
                obj.str("status/capacity/storage")?.let { add(StatusRow("Capacity", it)) }
                obj.arrayAt("spec/accessModes")?.let {
                    add(StatusRow("Access modes", it.joinToString(", ") { m -> m.toString().trim('"') }))
                }

                obj.str("status/volumeName")?.let { add(StatusRow("Volume", it)) }
            }

            "Node" -> {
                obj.str("status/nodeInfo/kubeletVersion")?.let { add(StatusRow("Kubelet", it)) }
                obj.str("status/nodeInfo/osImage")?.let { add(StatusRow("OS", it)) }
                obj.str("status/nodeInfo/containerRuntimeVersion")?.let { add(StatusRow("Runtime", it)) }
                obj.str("status/nodeInfo/architecture")?.let { add(StatusRow("Arch", it)) }
                obj.bool("spec/unschedulable")?.takeIf { it }?.let { add(StatusRow("Scheduling", "Disabled")) }
            }

            "HelmRelease" -> {
                obj.str("spec/chart/spec/chart")?.let { add(StatusRow("Chart", it)) }
                obj.str("spec/chart/spec/version")?.let { add(StatusRow("Chart version", it)) }
                obj.str("spec/chart/spec/sourceRef/name")?.let { add(StatusRow("Source", it)) }
                obj.str("spec/interval")?.let { add(StatusRow("Interval", it)) }
                obj.str("spec/targetNamespace")?.let { add(StatusRow("Target namespace", it)) }
                obj.str("status/lastAppliedRevision")?.let { add(StatusRow("Applied revision", it)) }
                obj.str("status/lastAttemptedRevision")?.let { add(StatusRow("Attempted revision", it)) }
                obj.bool("spec/suspend")?.takeIf { it }?.let { add(StatusRow("Suspended", "yes")) }
            }

            "Kustomization" -> {
                obj.str("spec/path")?.let { add(StatusRow("Path", it)) }
                obj.str("spec/sourceRef/name")?.let { add(StatusRow("Source", it)) }
                obj.str("spec/interval")?.let { add(StatusRow("Interval", it)) }
                obj.bool("spec/prune")?.let { add(StatusRow("Prune", it.toString())) }
                obj.str("status/lastAppliedRevision")?.let { add(StatusRow("Applied revision", it)) }
            }

            "Application" -> {
                obj.str("spec/project")?.let { add(StatusRow("Project", it)) }
                obj.objAt("spec/source")?.let { source ->
                    source.str("repoURL")?.let { add(StatusRow("Repository", it)) }
                    source.str("targetRevision")?.let { add(StatusRow("Target revision", it)) }
                    source.str("path")?.let { add(StatusRow("Path", it)) }
                    source.str("chart")?.let { add(StatusRow("Chart", it)) }
                }
                obj.arrayAt("spec/sources")?.size?.takeIf { it > 1 }?.let { add(StatusRow("Sources", it.toString())) }
                obj.str("spec/destination/server")?.let { add(StatusRow("Destination", it)) }
                obj.str("spec/destination/namespace")?.let { add(StatusRow("Namespace", it)) }
                obj.str("status/sync/status")?.let { add(StatusRow("Sync", it)) }
                obj.str("status/health/status")?.let { add(StatusRow("Health", it)) }
                obj.str("status/sync/revision")?.let { add(StatusRow("Revision", it)) }
                obj.str("status/operationState/phase")?.let { add(StatusRow("Operation", it)) }
                obj.str("spec/syncPolicy/automated/selfHeal")?.let { add(StatusRow("Auto-heal", it)) }
            }
        }

        obj.str("metadata/creationTimestamp")?.let { add(StatusRow("Created", it)) }
        obj.long("metadata/generation")?.let { add(StatusRow("Generation", it.toString())) }
    }

}
