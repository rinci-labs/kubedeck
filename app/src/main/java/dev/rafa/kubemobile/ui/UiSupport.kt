package dev.rafa.kubemobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.core.content.getSystemService
import dev.rafa.kubemobile.R
import dev.rafa.kubemobile.config.AuthKind
import dev.rafa.kubemobile.config.ClusterProfile
import dev.rafa.kubemobile.k8s.ApiResource
import dev.rafa.kubemobile.k8s.KubeApiException
import dev.rafa.kubemobile.k8s.YamlIo
import dev.rafa.kubemobile.ops.ResourceHealth
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `host:port` without the scheme. The one field that reliably tells two clusters apart, even when
 * their kubeconfig context names are identical.
 */
val ClusterProfile.hostPort: String
    get() = runCatching { java.net.URI(baseUrl) }.getOrNull()?.let { parsed ->
        parsed.host?.takeIf { it.isNotBlank() }?.let { host ->
            if (parsed.port > 0) "$host:${parsed.port}" else host
        }
    } ?: baseUrl.removePrefix("https://").removePrefix("http://")

/** A one-word auth badge; the full `AuthKind.label` is still used on the detail/settings screens. */
val AuthKind.shortLabel: String
    get() = when (this) {
        AuthKind.CLIENT_CERT -> "Cert"
        AuthKind.TOKEN -> "Token"
        AuthKind.BASIC -> "Basic"
        AuthKind.NONE -> "None"
    }

/**
 * Scrollable space reserved after the last list row.
 *
 * The bottom bar floats over the content, so a list needs enough tail padding that its final row
 * can be scrolled clear of the bar rather than ending flush against it — and at a large system font
 * scale the rows are taller, which is why this is deliberately generous rather than exact.
 */
val ListBottomPadding = androidx.compose.ui.unit.Dp(120f)

/* -------------------------------------------------------------------------------------------- */
/* Spacing scale                                                                                 */
/* -------------------------------------------------------------------------------------------- */

/**
 * The single source of truth for spacing, named by role rather than by size.
 *
 * Every screen previously carried its own literals — horizontal insets of 4, 8, 10, 12, 16 and 24 dp
 * coexisted across the app, so the same construct (a list row, a section header, an error block)
 * sat at a different distance from the edge depending on which screen it was drawn on. Screens now
 * reference these roles, which keeps one row looking like every other row.
 */
object Spacing {
    /** Horizontal edge inset for screen content: the standard 16 dp gutter. */
    val ScreenPadding = androidx.compose.ui.unit.Dp(16f)

    /** Horizontal inset inside a list row, so row content lines up with screen content. */
    val RowPadding = androidx.compose.ui.unit.Dp(16f)

    /** Vertical breathing room above and below a row card, giving 8 dp between stacked rows. */
    val RowVertical = androidx.compose.ui.unit.Dp(4f)

    /** Padding inside a card or panel. */
    val CardPadding = androidx.compose.ui.unit.Dp(16f)

    /**
     * Between a top app bar (or a header strip) and the first block of content.
     *
     * This is the one vertical rhythm every screen opens with. It used to be 8 on Browse, 24 on
     * the resource list and 18 on Helm, so the first row of each screen sat at a different height.
     */
    val ContentInset = androidx.compose.ui.unit.Dp(16f)

    /** Between a top app bar (or a header strip) and the first block of content. */
    val TopBarToContent = androidx.compose.ui.unit.Dp(8f)

    /** Between two stacked sections of a screen. */
    val SectionGap = androidx.compose.ui.unit.Dp(24f)

    /** Between sibling elements inside one section: rows in a group, chips in a row. */
    val ItemGap = androidx.compose.ui.unit.Dp(8f)

    /** Between an icon and the text it accompanies. */
    val TightGap = androidx.compose.ui.unit.Dp(4f)

    /** Inside compact chips and buttons, where 16 dp would waste the row. */
    val ChipPadding = androidx.compose.ui.unit.Dp(12f)

    /**
     * Inner padding of a dialog or bottom sheet body. Sheets and dialogs sit on their own surface at
     * the screen edge, so their content keeps the full 16 dp gutter rather than a card's inset.
     */
    val SheetPadding = androidx.compose.ui.unit.Dp(16f)

    /** Breathing room around an empty or error state, which is centred and reads better open. */
    val EmptyStatePadding = androidx.compose.ui.unit.Dp(24f)

    /** The horizontal inset a list divider keeps on both sides, so it aligns with row content. */
    val DividerPadding = RowPadding
}

/**
 * The one gutter every standalone card sits in: the 16 dp screen inset on both sides and half the
 * 8 dp stacking rhythm above and below, so two cards in a column are always 8 dp apart.
 */
fun androidx.compose.ui.Modifier.cardGutter(): androidx.compose.ui.Modifier =
    this.then(
        androidx.compose.ui.Modifier.padding(
            horizontal = Spacing.ScreenPadding,
            vertical = Spacing.RowVertical,
        ),
    )

/* -------------------------------------------------------------------------------------------- */
/* Window insets                                                                                 */
/* -------------------------------------------------------------------------------------------- */

/**
 * Inset ownership:
 *
 * - The root `Scaffold` in [KubeApp] uses `WindowInsets(0)`, so it never injects the status bar
 *   into the content it hosts. Its only contribution is the measured height of the bottom
 *   `NavigationBar`, which it hands to the `NavHost` as bottom padding.
 * - Screens that *are* bottom-bar destinations therefore take [BottomBarScreenInsets]: they own the
 *   status bar (through their own `TopAppBar`) and the horizontal cutout, and the root owns the
 *   bottom edge. Consuming the status bar exactly once is what keeps a top app bar one normal
 *   Material gap below the status bar instead of two insets apart.
 * - Pushed screens (object detail, logs, terminal, port forward, Helm detail, Flux, Argo CD) are
 *   full-window: the root contributes no bottom padding to them, so they keep the default insets
 *   and own all four edges themselves.
 */
val BottomBarScreenInsets: WindowInsets
    @Composable
    get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)

/* -------------------------------------------------------------------------------------------- */
/* Resource keys                                                                                 */
/* -------------------------------------------------------------------------------------------- */

/** `resourceKey` route format: `group|version|plural|kind` (group is empty for the core API). */
const val RESOURCE_KEY_SEPARATOR = "|"

/** Sentinel namespace for cluster-scoped objects, which cannot legally be named this. */
const val NO_NAMESPACE = "_none_"

/** Sentinel route argument for "the whole cluster", used by list routes. */
const val ALL_NAMESPACES = "_all_"

fun ApiResource.routeKey(): String =
    listOf(group, version, name, kind).joinToString(RESOURCE_KEY_SEPARATOR)

/** A resource identity that survives a navigation round trip without the whole catalog. */
data class ResourceRef(
    val group: String,
    val version: String,
    val plural: String,
    val kind: String,
) {
    val key: String get() = listOf(group, version, plural, kind).joinToString(RESOURCE_KEY_SEPARATOR)
    val groupLabel: String get() = if (group.isEmpty()) "core" else group

    /** Re-resolves the concrete [ApiResource] against a freshly loaded catalog. */
    fun resolve(catalog: dev.rafa.kubemobile.k8s.ApiCatalog): ApiResource? =
        catalog.resources.firstOrNull {
            it.group == group && it.version == version && it.name == plural
        } ?: catalog.forKind(kind, group.takeIf { it.isNotEmpty() })
            ?: catalog.forResource(plural, group.takeIf { it.isNotEmpty() })
}

fun parseResourceKey(raw: String): ResourceRef? {
    val parts = raw.split(RESOURCE_KEY_SEPARATOR)
    if (parts.size != 4) return null
    val (group, version, plural, kind) = parts
    if (plural.isBlank() || kind.isBlank()) return null
    return ResourceRef(group, version, plural, kind)
}

/* -------------------------------------------------------------------------------------------- */
/* Formatting                                                                                    */
/* -------------------------------------------------------------------------------------------- */

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

fun instantOf(timestamp: String?): Instant? {
    if (timestamp.isNullOrBlank()) return null
    return runCatching { Instant.parse(timestamp) }.getOrNull()
}

/** `kubectl`-style compact age: `8s`, `12m`, `3h`, `9d`, `2y`. */
fun humanAge(timestamp: String?): String? = instantOf(timestamp)?.let { humanAgeFrom(it) }

fun humanAgeFrom(instant: Instant): String {
    val seconds = (System.currentTimeMillis() - instant.toEpochMilli()) / 1000
    if (seconds < 0) return "0s"
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        seconds < 86_400L * 365 -> "${seconds / 86_400}d"
        else -> "${seconds / (86_400L * 365)}y"
    }
}

fun fullTimestamp(timestamp: String?): String? =
    instantOf(timestamp)?.atZone(ZoneId.systemDefault())?.format(DATE_TIME_FORMAT)

/** Millicores rendered the way `kubectl top` does: `12m`, or cores once past a full core. */
fun formatCpu(millis: Long?): String = when {
    millis == null -> ""
    millis < 1000 -> "${millis}m"
    else -> String.format(java.util.Locale.US, "%.2f", millis / 1000.0)
}

/** `12m CPU · 34Mi` — the compact metrics pair used in list rows. */
fun usageLabel(cpuMillis: Long?, memoryBytes: Long?): String? {
    val cpu = formatCpu(cpuMillis).takeIf { cpuMillis != null }
    val mem = memoryBytes?.let { humanBytes(it) }
    return when {
        cpu != null && mem != null -> "$cpu CPU · $mem"
        cpu != null -> "$cpu CPU"
        mem != null -> mem
        else -> null
    }
}

fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}

/* -------------------------------------------------------------------------------------------- */
/* Errors                                                                                        */
/* -------------------------------------------------------------------------------------------- */

/** A user-facing rendering of a failed operation: a title resource plus the raw API detail. */
data class UiError(
    val titleRes: Int,
    val message: String,
    val detail: String?,
)

fun Throwable.toUiError(discovery: Boolean = false): UiError {
    val api = this as? KubeApiException
    val raw = message.orEmpty().ifBlank { this.fallbackLabel() }
    return when {
        api?.isForbidden == true -> UiError(R.string.error_forbidden_title, raw, raw)
        api?.isUnauthorized == true -> UiError(R.string.error_unauthorized_title, raw, raw)
        api?.isNotFound == true -> UiError(R.string.error_generic_title, raw, raw)
        api != null -> UiError(R.string.error_generic_title, raw, raw)
        discovery -> UiError(R.string.error_discovery_title, raw, raw)
        this is java.io.IOException -> UiError(R.string.error_network_title, raw, raw)
        else -> UiError(R.string.error_generic_title, raw, raw)
    }
}

/** One-line summary for snackbars; the API message already carries status plus detail. */
fun Throwable.shortMessage(): String = message?.takeIf { it.isNotBlank() } ?: fallbackLabel()

/**
 * A readable stand-in for a throwable that carries no message.
 *
 * Deliberately not `::class.java.simpleName`: R8 renames app classes, so in the release build that
 * would put an obfuscated token such as `pd` in front of the user. The broad category is both
 * stable and more useful than an internal class name.
 */
private fun Throwable.fallbackLabel(): String = when (this) {
    is java.io.IOException -> "I/O error"
    is IllegalStateException -> "Invalid state"
    is IllegalArgumentException -> "Invalid input"
    else -> "Unexpected error"
}

/* -------------------------------------------------------------------------------------------- */
/* Semantic colour palette for health tones                                                      */
/* -------------------------------------------------------------------------------------------- */

data class ToneColors(val container: Color, val content: Color)

@Composable
@ReadOnlyComposable
fun toneColors(tone: ResourceHealth.Tone): ToneColors {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        // Tones stay distinguishable for status, but sit on faint mint/amber/red washes rather
        // than saturated Material containers, so no card becomes a block of colour.
        ResourceHealth.Tone.OK -> if (dark) {
            ToneColors(Color(0x1F6FBF95), Color(0xFF8FD8AF))
        } else {
            ToneColors(Color(0xFFD8EDE0), Color(0xFF2F6B4F))
        }

        ResourceHealth.Tone.WARN -> if (dark) {
            ToneColors(Color(0x1FD9A441), Color(0xFFE5B45C))
        } else {
            ToneColors(Color(0xFFF6E7C7), Color(0xFF7A5200))
        }

        ResourceHealth.Tone.BAD -> if (dark) {
            ToneColors(Color(0x1FE5695F), Color(0xFFF0948C))
        } else {
            ToneColors(Color(0xFFFBE1DE), Color(0xFF9A2A22))
        }

        ResourceHealth.Tone.PROGRESS -> if (dark) {
            ToneColors(Color(0xFF15241C), Color(0xFF9FD9B8))
        } else {
            ToneColors(Color(0xFFD8EDE0), Color(0xFF2F6B4F))
        }

        ResourceHealth.Tone.NEUTRAL -> if (dark) {
            ToneColors(Color(0x141E211E), Color(0xFF9AA09A))
        } else {
            ToneColors(Color(0xFFECEEE9), Color(0xFF59625D))
        }
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Clipboard                                                                                     */
/* -------------------------------------------------------------------------------------------- */

fun Context.copyToClipboard(label: String, text: String) {
    val manager = getSystemService<ClipboardManager>() ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Opens the system share sheet with a text payload (log tail, YAML, manifest). */
fun shareText(context: Context, label: String, text: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, label)
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    runCatching {
        context.startActivity(
            android.content.Intent.createChooser(intent, null).addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            ),
        )
    }
}

/* -------------------------------------------------------------------------------------------- */
/* Typography                                                                                    */
/* -------------------------------------------------------------------------------------------- */

/* -------------------------------------------------------------------------------------------- */
/* Minimal create manifests                                                                      */
/* -------------------------------------------------------------------------------------------- */

private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
    buildJsonObject { pairs.forEach { (key, value) -> put(key, value) } }

private fun arr(vararg values: String): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

/**
 * A small but valid starting document for the Create action, tailored to the kind the user picked.
 * Anything unrecognised gets the universal `apiVersion`/`kind`/`metadata` skeleton, which is enough
 * for the API server to explain what is missing.
 */
fun minimalManifest(resource: ApiResource, namespace: String?): String {
    val ns = namespace?.takeIf { it.isNotBlank() && it != NO_NAMESPACE && it != ALL_NAMESPACES }
    val meta = buildJsonObject {
        put("name", JsonPrimitive(""))
        if (resource.namespaced) put("namespace", JsonPrimitive(ns.orEmpty()))
    }
    val body = when (resource.kind) {
        "Pod" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "containers" to JsonArray(
                    listOf(
                        obj(
                            "name" to JsonPrimitive("app"),
                            "image" to JsonPrimitive("nginx:latest"),
                        ),
                    ),
                ),
            ),
        )

        "Deployment" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "replicas" to JsonPrimitive(1),
                "selector" to obj("matchLabels" to obj("app" to JsonPrimitive(""))),
                "template" to obj(
                    "metadata" to obj("labels" to obj("app" to JsonPrimitive(""))),
                    "spec" to obj(
                        "containers" to JsonArray(
                            listOf(
                                obj(
                                    "name" to JsonPrimitive("app"),
                                    "image" to JsonPrimitive("nginx:latest"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        "StatefulSet" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "serviceName" to JsonPrimitive(""),
                "replicas" to JsonPrimitive(1),
                "selector" to obj("matchLabels" to obj("app" to JsonPrimitive(""))),
                "template" to obj(
                    "metadata" to obj("labels" to obj("app" to JsonPrimitive(""))),
                    "spec" to obj(
                        "containers" to JsonArray(
                            listOf(
                                obj(
                                    "name" to JsonPrimitive("app"),
                                    "image" to JsonPrimitive("nginx:latest"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        "DaemonSet" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "selector" to obj("matchLabels" to obj("app" to JsonPrimitive(""))),
                "template" to obj(
                    "metadata" to obj("labels" to obj("app" to JsonPrimitive(""))),
                    "spec" to obj(
                        "containers" to JsonArray(
                            listOf(
                                obj(
                                    "name" to JsonPrimitive("app"),
                                    "image" to JsonPrimitive("nginx:latest"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        "Job" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "template" to obj(
                    "spec" to obj(
                        "restartPolicy" to JsonPrimitive("Never"),
                        "containers" to JsonArray(
                            listOf(
                                obj(
                                    "name" to JsonPrimitive("job"),
                                    "image" to JsonPrimitive("busybox:latest"),
                                    "command" to arr("sh", "-c", "echo hello"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        "CronJob" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "schedule" to JsonPrimitive("*/5 * * * *"),
                "jobTemplate" to obj(
                    "spec" to obj(
                        "template" to obj(
                            "spec" to obj(
                                "restartPolicy" to JsonPrimitive("OnFailure"),
                                "containers" to JsonArray(
                                    listOf(
                                        obj(
                                            "name" to JsonPrimitive("job"),
                                            "image" to JsonPrimitive("busybox:latest"),
                                            "command" to arr("sh", "-c", "date"),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        "Service" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "selector" to obj("app" to JsonPrimitive("")),
                "ports" to JsonArray(
                    listOf(
                        obj(
                            "name" to JsonPrimitive("http"),
                            "port" to JsonPrimitive(80),
                            "targetPort" to JsonPrimitive(8080),
                        ),
                    ),
                ),
            ),
        )

        "Ingress" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "rules" to JsonArray(
                    listOf(
                        obj(
                            "host" to JsonPrimitive(""),
                            "http" to obj(
                                "paths" to JsonArray(
                                    listOf(
                                        obj(
                                            "path" to JsonPrimitive("/"),
                                            "pathType" to JsonPrimitive("Prefix"),
                                            "backend" to obj(
                                                "service" to obj(
                                                    "name" to JsonPrimitive(""),
                                                    "port" to obj("number" to JsonPrimitive(80)),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        "ConfigMap" -> obj("metadata" to meta, "data" to obj("key" to JsonPrimitive("value")))

        "Secret" -> obj(
            "metadata" to meta,
            "type" to JsonPrimitive("Opaque"),
            "stringData" to obj("key" to JsonPrimitive("value")),
        )

        "PersistentVolumeClaim" -> obj(
            "metadata" to meta,
            "spec" to obj(
                "accessModes" to arr("ReadWriteOnce"),
                "resources" to obj("requests" to obj("storage" to JsonPrimitive("1Gi"))),
            ),
        )

        "ServiceAccount" -> obj("metadata" to meta)
        "Namespace" -> obj("metadata" to obj("name" to JsonPrimitive("")))
        "Role", "ClusterRole" -> obj(
            "metadata" to meta,
            "rules" to JsonArray(
                listOf(
                    obj(
                        "apiGroups" to arr(""),
                        "resources" to arr("pods"),
                        "verbs" to arr("get", "list", "watch"),
                    ),
                ),
            ),
        )

        "RoleBinding", "ClusterRoleBinding" -> obj(
            "metadata" to meta,
            "roleRef" to obj(
                "apiGroup" to JsonPrimitive("rbac.authorization.k8s.io"),
                "kind" to JsonPrimitive("Role"),
                "name" to JsonPrimitive(""),
            ),
            "subjects" to JsonArray(
                listOf(
                    obj(
                        "kind" to JsonPrimitive("ServiceAccount"),
                        "name" to JsonPrimitive(""),
                        ns?.let { "namespace" to JsonPrimitive(it) }
                            ?: ("namespace" to JsonPrimitive("default")),
                    ),
                ),
            ),
        )

        else -> JsonObject(emptyMap())
    }

    val document = buildJsonObject {
        put("apiVersion", JsonPrimitive(resource.apiVersion))
        put("kind", JsonPrimitive(resource.kind))
        body.forEach { (key, value) -> put(key, value) }
    }
    return YamlIo.toYaml(document)
}

/* -------------------------------------------------------------------------------------------- */
/* Misc                                                                                          */
/* -------------------------------------------------------------------------------------------- */

