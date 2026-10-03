package dev.rafa.kubemobile.k8s

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.Base64
import java.util.Date

/**
 * YAML <-> JSON bridging. Kubernetes manifests are YAML while the API speaks JSON, and both
 * directions must round-trip arbitrary CRD payloads without loss.
 */
object YamlIo {

    private fun loader(): Yaml {
        val options = LoaderOptions().apply {
            isAllowDuplicateKeys = true
            maxAliasesForCollections = 200
            codePointLimit = 32 * 1024 * 1024
        }
        return Yaml(SafeConstructor(options))
    }

    private fun dumper(): Yaml {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            defaultScalarStyle = DumperOptions.ScalarStyle.PLAIN
            indent = 2
            indicatorIndent = 0
            isPrettyFlow = false
            splitLines = false
            width = Int.MAX_VALUE
        }
        return Yaml(options)
    }

    /** Parses every document in a multi-document YAML stream. */
    fun parseAll(text: String): List<JsonElement> =
        loader().loadAll(text).mapNotNull { it.toJsonElement() }

    fun parse(text: String): JsonElement? = parseAll(text).firstOrNull()

    fun toYaml(element: JsonElement): String = dumper().dump(element.toPlain())

    fun toYamlAll(elements: List<JsonElement>): String =
        elements.joinToString("\n---\n") { toYaml(it) }.trimStart()

    private fun Any?.toJsonElement(): JsonElement? = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Long -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Float -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Date -> JsonPrimitive(java.time.Instant.ofEpochMilli(time).toString())
        is ByteArray -> JsonPrimitive(Base64.getEncoder().encodeToString(this))
        is Map<*, *> -> {
            val out = LinkedHashMap<String, JsonElement>(size)
            for ((k, v) in this) out[k.toString()] = v.toJsonElement() ?: JsonNull
            JsonObject(out)
        }

        is Iterable<*> -> JsonArray(mapNotNull { it.toJsonElement() })
        is Array<*> -> JsonArray(mapNotNull { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }

    private fun JsonElement.toPlain(): Any? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            isString -> content
            content == "true" -> true
            content == "false" -> false
            else -> {
                val asLong = content.toLongOrNull()
                if (asLong != null) asLong else content.toDoubleOrNull() ?: content
            }
        }

        is JsonObject -> entries.associateTo(LinkedHashMap(entries.size)) { (k, v) -> k to v.toPlain() }
        is JsonArray -> map { it.toPlain() }
    }
}
