package dev.rafa.kubemobile.k8s

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Shared JSON configuration. Lenient so partial/malformed server payloads never kill a screen. */
val KubeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    allowSpecialFloatingPointValues = true
}

/* ---------------------------------------------------------------------------------------------- */
/* Convenience accessors over untyped Kubernetes objects.                                          */
/* ---------------------------------------------------------------------------------------------- */

val JsonElement.obj: JsonObject get() = this as JsonObject

/**
 * Resolves a `/`-separated path against nested objects and arrays: `metadata/name`,
 * `status/conditions/0/type`, `spec/containers/0/ports/0/containerPort`.
 *
 * This is deliberately a plainly-named function rather than an `operator fun get(path: String)`.
 * `JsonObject` is a `Map<String, JsonElement>`, so its *member* `get(key: String)` always wins
 * overload resolution over an extension with the same signature; an operator extension here would
 * silently degrade every call site to a single literal-key lookup.
 *
 * Returns null (never throws) when any segment is missing, an array index is out of range or
 * non-numeric, or a scalar is indexed further.
 */
fun JsonObject.path(path: String): JsonElement? {
    var current: JsonElement = this
    for (segment in path.split('/')) {
        if (segment.isEmpty()) continue
        current = when (current) {
            is JsonObject -> current[segment] ?: return null
            is JsonArray -> current.getOrNull(segment.toIntOrNull() ?: return null) ?: return null
            else -> return null
        }
    }
    return current
}

/*
 * Values are read through `as? JsonPrimitive`, never `JsonElement.jsonPrimitive`, which throws on
 * objects and arrays. That keeps a malformed or unexpected payload a null instead of a crash.
 */

fun JsonObject.str(path: String): String? = (this.path(path) as? JsonPrimitive)?.contentOrNull

fun JsonObject.bool(path: String): Boolean? = (this.path(path) as? JsonPrimitive)?.booleanOrNull

fun JsonObject.long(path: String): Long? = (this.path(path) as? JsonPrimitive)?.longOrNull

fun JsonObject.objAt(path: String): JsonObject? = this.path(path) as? JsonObject

fun JsonObject.arrayAt(path: String): JsonArray? = this.path(path) as? JsonArray

fun JsonObject.stringMap(path: String): Map<String, String> =
    objAt(path)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" } ?: emptyMap()

fun jsonOf(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(pairs.toMap())

fun jsonString(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

fun jsonBool(value: Boolean?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

fun jsonLong(value: Long?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

fun jsonInt(value: Int?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
