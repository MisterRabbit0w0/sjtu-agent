package edu.sjtu.agent.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val LenientJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

fun JsonObject.string(key: String, default: String = ""): String =
    this[key]?.jsonPrimitive?.contentOrNull ?: default

fun JsonObject.int(key: String, default: Int = 0): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: default

fun JsonObject.long(key: String, default: Long = 0L): Long =
    this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: default

fun JsonObject.double(key: String, default: Double = 0.0): Double =
    this[key]?.jsonPrimitive?.doubleOrNull ?: default

fun JsonObject.boolean(key: String, default: Boolean = false): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: default

fun JsonObject.obj(key: String): JsonObject =
    this[key]?.let { it as? JsonObject } ?: JsonObject(emptyMap())

fun JsonObject.array(key: String): JsonArray =
    this[key]?.let { it as? JsonArray } ?: JsonArray(emptyList())

fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

fun JsonElement.asArrayOrEmpty(): JsonArray = runCatching { jsonArray }.getOrElse { JsonArray(emptyList()) }

fun Map<String, String>.toJsonObject(): JsonObject =
    JsonObject(mapValues { JsonPrimitive(it.value) })

fun JsonObject.toStringMap(): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        if (value == JsonNull) null else key to value.jsonPrimitive.content
    }.toMap()
