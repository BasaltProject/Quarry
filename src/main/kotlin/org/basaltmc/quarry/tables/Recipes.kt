package org.basaltmc.quarry.tables

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Fuel and brewing where vanilla moved them from code into data (after 26.2): fuel is the `minecraft:cooking_fuel`
 * item component and brewing is `minecraft:brewing` recipes, folded back into the shape the tables have always had.
 */
internal object Recipes {
    /** The block a plain furnace is, which is what the fuel table answers for. */
    private const val FURNACE = "minecraft:furnace"

    private fun warn(message: String) = System.err.println("warning: $message")

    private class Data(val jar: ZipFile, val serverJar: Path) {
        fun load(folder: String, name: String): JsonElement {
            val entry = jar.getEntry("data/${name.substringBefore(':')}/$folder/${name.substringAfter(':')}.json")
            return parseJson(jar.getInputStream(entry).bufferedReader().readText())
        }

        fun test(predicate: JsonElement, block: String): Boolean {
            predicate.asText()?.let { return test(load("predicate", it), block) }
            val kind = predicate.jsonObject["type"].asText()
            require(kind == "minecraft:match_block") { "predicate type $kind is not understood, extend Recipes.kt" }
            val blocks = predicate.jsonObject.getValue("blocks")
            val text = blocks.asText()
            val members: Collection<String> = when {
                text == null -> blocks.let { (it as kotlinx.serialization.json.JsonArray).map { e -> e.asText()!! } }
                text.startsWith("#") -> resolveTag(serverJar, "block", text.substring(1))
                else -> listOf(text)
            }
            return block in members
        }

        fun number(provider: JsonElement, folder: String, block: String): Double {
            if (provider.isNumber()) return provider.asDouble()
            provider.asText()?.let { return number(load(folder, it), folder, block) }
            val obj = provider.jsonObject
            return when (val kind = obj["type"].asText()) {
                "minecraft:constant" -> obj.getValue("value").asDouble()
                "minecraft:conditional" -> {
                    val holds = test(obj.getValue("condition"), block)
                    val chosen = if (holds) obj.getValue("on_true") else obj.getValue("on_false")
                    number(chosen, folder, block)
                }
                "minecraft:div" -> {
                    val left = number(obj.getValue("left"), folder, block)
                    val right = number(obj.getValue("right"), folder, block)
                    // parity: `Quotient`, Java's integer division, which truncates towards zero.
                    if (folder == "context_int_provider") (left / right).toLong().toDouble() else left / right
                }
                else -> throw IllegalArgumentException("provider type $kind is not understood, extend Recipes.kt")
            }
        }
    }

    /** How long each item burns in a plain furnace, from its `minecraft:cooking_fuel` component. */
    fun fuel(components: List<Pair<String, JsonObject>>, serverJar: Path): List<String> {
        val rows = ArrayList<String>()
        ZipFile(serverJar.toFile()).use { jar ->
            val data = Data(jar, serverJar)
            for ((item, parts) in components) {
                val fuel = parts["minecraft:cooking_fuel"].objectOrNull() ?: continue
                val ticks = data.number(fuel.getValue("burn_time"), "context_int_provider", FURNACE).toLong()
                val speed = fuel["speed_multiplier"]?.let { data.number(it, "context_float_provider", FURNACE) } ?: 1.0
                if (speed != 1.0) warn("$item cooks at ${javaFloat(speed)}x in a furnace, which the table cannot say")
                if (ticks > 0) rows.add("{${cstr(item)}, $ticks}")
            }
        }
        return rows
    }

    /** The `minecraft:brewing` recipes, folded back into potion mixes (any bottle) and container mixes. */
    fun brewing(serverJar: Path, potions: List<String>): List<String> {
        val potionMixes = LinkedHashMap<Triple<String, String, String>, MutableSet<String>>()
        val containerMixes = LinkedHashMap<Triple<String, String, String>, MutableSet<String>>()
        ZipFile(serverJar.toFile()).use { jar ->
            for (name in jar.entries().asSequence().map { it.name }.sorted()) {
                if (!(name.startsWith("data/") && "/recipe/" in name && name.endsWith(".json"))) continue
                val recipe = parseJson(jar.getInputStream(jar.getEntry(name)).bufferedReader().readText()).jsonObject
                if (recipe["type"].asText() != "minecraft:brewing") continue
                val input = recipe.getValue("input").jsonObject
                val bottle = input.getValue("item").asText()
                val before = input.getValue("potion_contents").jsonObject.getValue("potions").asText()
                val reagent = recipe.getValue("reagent").jsonObject.getValue("item").asText()
                val output = recipe.getValue("output").jsonObject
                val result = output.getValue("id").asText()
                val contents = output["components"].objectOrNull()?.get("minecraft:potion_contents")
                val after = contents.objectOrNull()?.get("potion").asText()
                require(bottle != null && before != null && reagent != null && result != null) {
                    "$name names a list or a tag, which the brewing table cannot hold"
                }
                if (bottle == result && after != null) {
                    potionMixes.getOrPut(Triple(before, reagent, after)) { linkedSetOf() }.add(bottle)
                } else if (bottle != result && (after == null || after == before)) {
                    containerMixes.getOrPut(Triple(bottle, reagent, result)) { linkedSetOf() }.add(before)
                } else {
                    throw IllegalArgumentException("$name changes both the bottle and what is in it")
                }
            }
        }
        val bottles = potionMixes.values.flatten().toSet()
        val rows = ArrayList<String>()
        for ((key, madeIn) in potionMixes) {
            if (madeIn != bottles) {
                warn("${key.first} + ${key.second} only brews in ${madeIn.sorted()}, the table says any bottle")
            }
            rows.add(mix("potionMixes", key))
        }
        for ((key, contents) in containerMixes) {
            val missing = (potions.toSet() - contents).sorted()
            if (missing.isNotEmpty()) {
                warn("${key.first} + ${key.second} does not take $missing, the table says any contents")
            }
            rows.add(mix("containerMixes", key))
        }
        return rows
    }

    private fun mix(kind: String, key: Triple<String, String, String>): String {
        val fields = listOf(kind) + listOf(key.first, key.second, key.third).map(::stripNamespace)
        return "{" + fields.joinToString(", ") { cstr(it) } + "}"
    }
}
