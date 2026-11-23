package tech.fonia.features.speaking

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Small readers for the draft content files of the speaking exercises (`assets/drafts/emphasis.json`,
// `assets/drafts/bac_oral.json`): a missing key is an error, so a broken file shows up in the tests.

internal fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: error("Missing \"$key\"")

internal fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.obj(key: String): JsonObject = (this[key] as? JsonObject) ?: error("Missing \"$key\"")

internal fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: error("Missing \"$key\"")
