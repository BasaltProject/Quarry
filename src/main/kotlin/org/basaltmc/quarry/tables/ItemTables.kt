package org.basaltmc.quarry.tables

import java.nio.file.Path
import kotlin.math.floor
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Item, entity and world tables: registry ids, item components, entity shapes and attributes, POIs. */
internal object ItemTables {
    private val armourSlots = setOf("head", "chest", "legs", "feet", "body")

    // parity: `Consumable.DEFAULT_CONSUME_SECONDS`, the 1.6 seconds eating takes.
    private const val DEFAULT_CONSUME_SECONDS = 1.6

    /** What the item component reports say, one item file at a time in file name order. */
    fun components(components: List<Pair<String, JsonObject>>): Map<String, Any> {
        val tools = ArrayList<String>()
        val durability = ArrayList<String>()
        val stacking = ArrayList<String>()
        val armour = ArrayList<String>()
        val weapons = ArrayList<String>()
        val food = ArrayList<String>()
        val trims = ArrayList<Pair<String, String>>()
        for ((item, parts) in components) {
            val tool = parts["minecraft:tool"]
            val weapon = parts["minecraft:weapon"]
            val rules = tool.objectOrNull()?.get("rules").arrayOrNull()
            if (rules != null) {
                for (rule in rules) {
                    val blocks = rule.jsonObject["blocks"].asText()
                    if (blocks == null || !blocks.startsWith("#")) continue
                    val speed = rule.jsonObject["speed"]
                    val speedText = if (speed.isNumber()) "${javaFloat(speed.asDouble())}F" else "std::nullopt"
                    val correct = rule.jsonObject["correct_for_drops"].isTrue()
                    tools.add("{${cstr(item)}, ${cstr(blocks.substring(1))}, ${cbool(correct)}, $speedText}")
                }
            }
            val maxDamage = parts["minecraft:max_damage"]
            if (maxDamage.isNumber()) {
                // Zero without a tool or weapon component: `Item.mineBlock` returns early for one.
                val perBlock = if (tool == null) 0 else tool.jsonObject["damage_per_block"]?.asInt() ?: 1
                val perAttack = if (weapon == null) 0 else weapon.jsonObject["item_damage_per_attack"]?.asInt() ?: 1
                durability.add("{${cstr(item)}, ${maxDamage.asInt()}, $perBlock, $perAttack}")
            }
            val stackSize = parts["minecraft:max_stack_size"]
            val stack = if (stackSize.isNumber()) stackSize.asInt() else 64
            val seconds = parts["minecraft:use_cooldown"].objectOrNull()?.get("seconds")
            // parity: `Math.round`, which rounds half up.
            val ticks = floor((if (seconds.isNumber()) seconds.asDouble() else 0.0) * 20.0 + 0.5).toInt()
            if (stack != 64 || ticks != 0) stacking.add(cstr("$item|$stack|$ticks"))

            val modifiers = parts["minecraft:attribute_modifiers"].arrayOrNull()
            if (modifiers != null) {
                armourOf(item, modifiers)?.let { armour.add(cstr(it)) }
                weaponOf(item, modifiers)?.let { weapons.add(cstr(it)) }
            }
            foodOf(item, parts)?.let { food.add(cstr(it)) }
            parts["minecraft:provides_trim_material"].asText()?.let { trims.add(item to stripNamespace(it)) }
        }
        return mapOf(
            "kItemToolsTable" to tools,
            "kItemDurabilityTable" to durability,
            "kItemStackingTable" to stacking,
            "kItemArmourTable" to armour,
            "kItemWeaponsTable" to weapons,
            "kItemFoodTable" to food,
            "kTrimMaterialsTable" to trims.sortedWith(compareBy({ it.first }, { it.second }))
                .map { (item, material) -> "{${cstr(item)}, ${cstr(material)}}" },
        )
    }

    /** Only modifiers on an armour slot count: a weapon's damage lives in the same component. */
    private fun armourOf(item: String, modifiers: List<JsonElement>): String? {
        val values = linkedMapOf(
            "minecraft:armor" to 0.0,
            "minecraft:armor_toughness" to 0.0,
            "minecraft:knockback_resistance" to 0.0,
        )
        var slot = ""
        for (modifier in modifiers) {
            val where = modifier.jsonObject["slot"].asText()
            val amount = modifier.jsonObject["amount"]
            if (where !in armourSlots || !amount.isNumber()) continue
            slot = where!!
            val type = modifier.jsonObject["type"].asText()
            if (type in values) values[type!!] = amount.asDouble()
        }
        if (slot.isEmpty() || values.values.none { it != 0.0 }) return null
        val (armour, toughness, knockback) = values.values.map(::javaDouble)
        return "$item|$slot|$armour|$toughness|$knockback"
    }

    private fun weaponOf(item: String, modifiers: List<JsonElement>): String? {
        var damage = 0.0
        var speed = 0.0
        for (modifier in modifiers) {
            val amount = modifier.jsonObject["amount"]
            if (modifier.jsonObject["slot"].asText() != "mainhand" || !amount.isNumber()) continue
            when (modifier.jsonObject["type"].asText()) {
                "minecraft:attack_damage" -> damage = amount.asDouble()
                "minecraft:attack_speed" -> speed = amount.asDouble()
            }
        }
        if (damage == 0.0 && speed == 0.0) return null
        return "$item|${javaDouble(damage)}|${javaDouble(speed)}"
    }

    private fun foodOf(item: String, parts: JsonObject): String? {
        val consumable = parts["minecraft:consumable"].objectOrNull() ?: return null
        val food = parts["minecraft:food"].objectOrNull() ?: JsonObject(emptyMap())
        // Milk is why this is not simply "has a food component": it feeds nobody and clears every effect.
        if (food.isEmpty() && consumable["on_consume_effects"] == null) return null
        val nutrition = if (food["nutrition"].isNumber()) food["nutrition"].asInt() else 0
        val saturation = if (food["saturation"].isNumber()) javaFloat(food["saturation"].asDouble()) else "0.0"
        val always = food["can_always_eat"].isTrue()
        val consumeSeconds = consumable["consume_seconds"]
        val seconds = javaFloat(if (consumeSeconds.isNumber()) consumeSeconds.asDouble() else DEFAULT_CONSUME_SECONDS)
        val effects = consumable["on_consume_effects"].arrayOrNull() ?: emptyList()
        val left = parts["minecraft:use_remainder"].objectOrNull()?.get("id").asText()?.let(::stripNamespace) ?: ""
        return "$item|$nutrition|$saturation|${cbool(always)}|$seconds|${consumeEffects(effects)}|$left"
    }

    /** parity: `Consumable.onConsumeEffects`, of which only these four shapes act on the eater. */
    private fun consumeEffects(effects: List<JsonElement>): String {
        val parts = ArrayList<String>()
        for (effect in effects) {
            val obj = effect.jsonObject
            when (obj["type"].asText()) {
                "minecraft:apply_effects" -> {
                    val applied = obj["effects"].arrayOrNull() ?: continue
                    val chance = javaFloat(if (obj["probability"].isNumber()) obj["probability"].asDouble() else 1.0)
                    parts.add(
                        applied.joinToString(";") { e ->
                            val o = e.jsonObject
                            "apply:${stripNamespace(o["id"].asText() ?: "")},${o["duration"]?.asInt() ?: 0}," +
                                "${o["amplifier"]?.asInt() ?: 0},$chance"
                        },
                    )
                }
                "minecraft:remove_effects" ->
                    parts.add("remove:${obj["effects"].asText()?.let(::stripNamespace) ?: "null"}")
                "minecraft:clear_all_effects" -> parts.add("clear")
                "minecraft:teleport_randomly" ->
                    parts.add("teleport:${javaFloat(obj["diameter"].let { if (it.isNumber()) it.asDouble() else 16.0 })}")
            }
        }
        return parts.joinToString(";")
    }

    fun harness(dumps: Path): Map<String, Any> {
        val items = readTsv(dumps.resolve("items.tsv"))
        val byPair = compareBy<Pair<String, String>>({ it.first }, { it.second })
        val remainders = items.filter { it[0] == "REMAINDER" }.map { it[1] to it[2] }.sortedWith(byPair)
        val placed = items.filter { it[0] == "BLOCK" && it[1] != it[2] }.map { it[1] to it[2] }.sortedWith(byPair)
        val entityRows = readTsv(dumps.resolve("entities.tsv"))
        val entityTypes = entityRows.filter { it[0] == "TYPE" }.map { it.drop(1) }
        val attributes = entityRows.filter { it[0] == "ATTRIBUTE" }.map { it.drop(1) }
        val blockEntities = readTsv(dumps.resolve("block_entities.tsv"))
        val owners = blockEntities.flatMap { row -> row[1].split(',').filter { it.isNotEmpty() }.map { it to row[0] } }
            .sortedBy { it.first }
        return mapOf(
            "kCraftingRemaindersTable" to remainders.map { (a, b) -> "{${cstr(a)}, ${cstr(b)}}" },
            "kItemBlocksTable" to placed.map { (a, b) -> "{${cstr(a)}, ${cstr(b)}}" },
            "kBlockEntityTypeNamesTable" to blockEntities.map { cstr(it[0]) },
            "kBlockEntityOwnersTable" to owners.map { (a, b) -> "{${cstr(a)}, ${cstr(b)}}" },
            "kEntityTypeShapesTable" to entityTypes.sortedBy { it[0] }.map { r ->
                "{${cstr(r[0])}, ${r[1]}F, ${r[2]}F, ${cstr(r[3])}, ${r[4]}, ${r[5]}, ${r[6]}, ${r[7]}}"
            },
            "kEntityAttributesTable" to attributes.sortedWith(compareBy({ it[0] }, { it[1] })).map { r ->
                "{${cstr(r[0])}, ${cstr(r[1])}, ${r[2]}}"
            },
        )
    }

    fun world(reports: Reports, dumps: Path, serverJar: Path): Map<String, Any> {
        val world = readTsv(dumps.resolve("world.tsv"))
        val biomes = world.filter { it[0] == "BIOME" }.map { it[1] }
        val overworld = resolveTag(serverJar, "worldgen/biome", "minecraft:is_overworld")
        // The overworld first, then the rest in bootstrap order: the original biome ids predate the other dimensions.
        val ordered = biomes.filter { "minecraft:$it" in overworld } + biomes.filter { "minecraft:$it" !in overworld }
        return mapOf(
            "kBiomeIdsTable" to idPairs(ordered.withIndex().map { it.index to it.value }),
            "kCustomStatsTable" to reports.registry("minecraft:custom_stat").map { cstr(it.second) },
            "kInstrumentsTable" to world.filter { it[0] == "INSTRUMENT" }.map {
                "{${cstr(it[1])}, ${cstr(it[2])}, NoteBlockInstrument::Kind::${it[3]}}"
            },
            "kPoiBlocksTable" to world.filter { it[0] == "POI" }.map { "{${cstr(it[1])}, ${cstr(it[2])}}" },
            "kItemIdsTable" to idPairs(reports.registry("minecraft:item")),
            "kEntityTypeIdsTable" to idPairs(reports.registry("minecraft:entity_type")),
            "kParticleTypeIdsTable" to idPairs(reports.registry("minecraft:particle_type")),
            "kDataComponentIdsTable" to idPairs(reports.registry("minecraft:data_component_type")),
        )
    }
}
