package org.basaltmc.quarry.tables

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

internal fun parseJson(text: String): JsonElement = Json.parseToJsonElement(text)

/** A JSON number, as Python would call it: an integer or a float, never a boolean or a string. */
internal fun JsonElement?.isNumber(): Boolean =
    this is JsonPrimitive && !isString && booleanOrNull == null && doubleOrNull != null

internal fun JsonElement?.asDouble(): Double = (this as JsonPrimitive).content.toDouble()

internal fun JsonElement?.asInt(): Int =
    (this as JsonPrimitive).content.let { it.toIntOrNull() ?: it.toDouble().toInt() }

internal fun JsonElement?.asText(): String? = if (this is JsonPrimitive && isString) content else null

internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonElement?.isTrue(): Boolean = this is JsonPrimitive && !isString && booleanOrNull == true

/** The `--reports` output of the jar's own data generator. */
internal class Reports(private val root: Path) {
    val blocks: JsonObject = parseJson(root.resolve("blocks.json").readText()).jsonObject
    private val registries: JsonObject = parseJson(root.resolve("registries.json").readText()).jsonObject

    /** The entries of a registry, by protocol id, as `(id, name without its namespace)`. */
    fun registry(name: String): List<Pair<Int, String>> {
        val entries = registries.getValue(name).jsonObject.getValue("entries").jsonObject
        return entries.map { (key, entry) ->
            entry.jsonObject.getValue("protocol_id").asInt() to key.substringAfter(':')
        }.sortedWith(compareBy({ it.first }, { it.second }))
    }

    /** What the item component reports say, one item file at a time in file name order. */
    fun itemComponents(): List<Pair<String, JsonObject>> {
        val folder = root.resolve("minecraft").resolve("components").resolve("item")
        return folder.listDirectoryEntries("*.json").sortedBy { it.name }.map { path ->
            val components = parseJson(path.readText()).jsonObject["components"]
            path.nameWithoutExtension to (components.objectOrNull() ?: JsonObject(emptyMap()))
        }
    }
}

/** Every member of a vanilla tag, following the tags it includes. */
internal fun resolveTag(serverJar: Path, registry: String, tag: String): Set<String> {
    val members = linkedSetOf<String>()
    ZipFile(serverJar.toFile()).use { jar ->
        fun visit(name: String) {
            val namespace = name.substringBefore(':')
            val path = name.substringAfter(':')
            val entry = jar.getEntry("data/$namespace/tags/$registry/$path.json")
            val file = parseJson(jar.getInputStream(entry).bufferedReader().readText()).jsonObject
            val values = file.getValue("values").jsonArray
            for (value in values) {
                val text = if (value is JsonObject) value.getValue("id").asText()!! else value.asText()!!
                if (text.startsWith("#")) visit(text.substring(1)) else members.add(text)
            }
        }
        visit(tag)
    }
    return members
}
