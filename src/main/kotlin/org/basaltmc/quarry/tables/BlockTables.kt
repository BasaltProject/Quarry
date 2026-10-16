package org.basaltmc.quarry.tables

import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Block tables: ids, state definitions, hardness, shapes, support and the village and portal specs. */
internal object BlockTables {
    /** The shape and support indexes are split into literals of this size: a compiler only has to accept 64 KB. */
    private const val JOIN_CHUNK = 16000

    /** 0 means nothing holds a state up; 1 is a rule this build cannot express, which must never break it. */
    private const val UNKNOWN_SUPPORT = 1
    private const val FIRST_SUPPORT_RULE = 2

    /** The table keeps the names `PushReaction` had until 26.2, which is what the C++ side parses. */
    private val pushReactionNames = mapOf(
        "DESTROY" to "DESTROY", "BLOCK" to "BLOCK", "PUSH_ONLY" to "PUSH_ONLY", "IGNORE" to "IGNORE",
        "POPPED" to "DESTROY", "IMMOVEABLE" to "BLOCK", "PUSH" to "PUSH_ONLY", "IGNORE_ENTITY" to "IGNORE",
    )

    private fun joinLiteral(text: String): String =
        "{" + text.chunked(JOIN_CHUNK).joinToString(", ") { cstr(it) } + "}"

    private fun defaultStates(blocks: JsonObject): List<Pair<Int, String>> =
        blocks.map { (name, block) ->
            val default = block.jsonObject.getValue("states").jsonArray.first { it.jsonObject["default"].isTrue() }
            default.jsonObject.getValue("id").asInt() to name.substringAfter(':')
        }.sortedWith(compareBy({ it.first }, { it.second }))

    /**
     * `<first state id>|<block>|<property>=<values>;...` for every block, properties outermost first. The report lists
     * properties in its own order, which for a dozen blocks is not the order the ids vary in, so the order is worked
     * out from the states: a property's stride is how far apart two states differing only in it are.
     */
    private fun stateDefinitions(blocks: JsonObject): Map<String, String> {
        val specs = LinkedHashMap<String, String>()
        for ((name, block) in blocks) {
            val states = block.jsonObject.getValue("states").jsonArray.associate { state ->
                state.jsonObject.getValue("id").asInt() to
                    (state.jsonObject["properties"].objectOrNull()?.mapValues { it.value.asText()!! } ?: emptyMap())
            }
            val base = states.keys.min()
            val values = block.jsonObject["properties"].objectOrNull() ?: JsonObject(emptyMap())
            val baseProperties = states.getValue(base)
            val strides = LinkedHashMap<String, Int>()
            for ((property, options) in values) {
                val wanted = baseProperties.toMutableMap()
                wanted[property] = options.jsonArray[1].asText()!!
                strides[property] = states.entries.first { it.value == wanted }.key - base
            }
            val ordered = values.keys.sortedBy { -strides.getValue(it) }
            val body = ordered.joinToString(";") { property ->
                "$property=${values.getValue(property).jsonArray.joinToString(",") { it.asText()!! }}"
            }
            val short = name.substringAfter(':')
            specs[short] = "$base|$short|$body"
        }
        return specs
    }

    private fun specBase(spec: String): Int = spec.substringBefore('|').toInt()

    fun build(reports: Reports, dumps: Path, serverJar: Path): Map<String, Any> {
        val blocks = reports.blocks
        val specs = stateDefinitions(blocks)
        val multiState = blocks.filter { it.value.jsonObject.containsKey("properties") }.keys
            .map { specs.getValue(it.substringAfter(':')) }
        val rows = readTsv(dumps.resolve("blocks.tsv"))
        val hardness = rows.sortedBy { it[0] }
        val movement = hardness.filter { it.subList(4, 8) != listOf("0.6", "1.0", "1.0", "0.0") }
        val instruments = hardness.filter { it[8] != "harp" }
        val tables = LinkedHashMap<String, Any>()
        tables["kDefaultBlockStatesTable"] = idPairs(defaultStates(blocks))
        tables["kBlockIdsTable"] = idPairs(reports.registry("minecraft:block"))
        tables["kBlockStateDefinitions"] = multiState.sortedBy(::specBase).map(::cstr)
        tables["kBlockHardnessTable"] = hardness.map { "{${cstr(it[0])}, ${it[1]}F, ${it[2]}, ${it[3]}F}" }
        tables["kBlockMovementTable"] =
            movement.map { "{${cstr(it[0])}, ${it[4]}F, ${it[5]}F, ${it[6]}F, ${it[7]}F}" }
        tables["kBlockInstrumentsTable"] = instruments.map { "{${cstr(it[0])}, ${cstr(it[8])}}" }
        tables["kPortalBlockStateSpecsTable"] = listOf("nether_portal", "end_portal_frame").map { cstr(specs.getValue(it)) }
        tables["kVillageBlockStateSpecsTable"] =
            villageBlocks(serverJar).map { specs.getValue(it) }.sortedBy(::specBase).map(::cstr)
        tables.putAll(soundTables(dumps))
        tables.putAll(shapeTables(dumps, blocks, serverJar))
        tables.putAll(supportTables(dumps))
        tables.putAll(mapColourTables(dumps))
        return tables
    }

    /** Each distinct `SoundType`, stone's first so that it is the default, and every block that differs. */
    private fun soundTables(dumps: Path): Map<String, Any> {
        val rows = readTsv(dumps.resolve("block_sounds.tsv")).sortedBy { it[0] }
        val stone = rows.first { it[0] == "stone" }.drop(1)
        val others = rows.map { it.drop(1) }.toSet() - setOf(stone)
        val order = compareBy<List<String>> { it.drop(2).joinToString("\u0000") }.thenBy { it.take(2).joinToString("\u0000") }
        val kinds = listOf(stone) + others.sortedWith { left, right -> compareLists(left.drop(2) + left.take(2), right.drop(2) + right.take(2)) }
        val index = kinds.withIndex().associate { it.value to it.index }
        return mapOf(
            "kBlockSoundTypesTable" to kinds.map { kind ->
                "{${kind[0]}F, ${kind[1]}F, ${kind.drop(2).joinToString(", ") { cstr(it) }}}"
            },
            "kBlockSoundsTable" to rows.filter { it.drop(1) != stone }.map { "{${cstr(it[0])}, ${index.getValue(it.drop(1))}}" },
        )
    }

    private fun compareLists(left: List<String>, right: List<String>): Int {
        for (i in 0 until minOf(left.size, right.size)) {
            val c = left[i].compareTo(right[i])
            if (c != 0) return c
        }
        return left.size.compareTo(right.size)
    }

    /** A block state as a template or processor writes it: `{Name}` until 26.2, `{id}` or `id[...]` since. */
    private fun blockName(state: Any?): String = when (state) {
        is JsonElement -> state.asText()?.substringBefore('[') ?: (state.jsonObject["Name"] ?: state.jsonObject["id"]).asText()!!
        is String -> state.substringBefore('[')
        is Map<*, *> -> (state["Name"] ?: state["id"]) as String
        else -> error("block state $state")
    }

    /** Every block a village template places, or that one of its processors can turn a block into. */
    private fun villageBlocks(serverJar: Path): Set<String> {
        val names = linkedSetOf<String>()
        val processors = linkedSetOf<String>()
        val outputs = linkedSetOf<String>()

        fun collectOutputs(node: JsonElement) {
            when (node) {
                is JsonObject -> for ((key, value) in node) {
                    if (key == "output_state") outputs.add(blockName(value))
                    collectOutputs(value)
                }
                is JsonArray -> node.forEach(::collectOutputs)
                else -> {}
            }
        }

        ZipFile(serverJar.toFile()).use { jar ->
            for (entry in jar.entries().asSequence().map { it.name }.sorted()) {
                if (entry.startsWith("data/minecraft/structure/village/") && entry.endsWith(".nbt")) {
                    val template = Nbt.readGzipped(jar.getInputStream(jar.getEntry(entry)))
                    @Suppress("UNCHECKED_CAST")
                    val palettes: List<List<Any?>> = when {
                        template.containsKey("palette") -> listOf(template["palette"] as List<Any?>)
                        else -> (template["palettes"] as? List<List<Any?>>) ?: emptyList()
                    }
                    for (palette in palettes) {
                        for (block in palette) names.add(blockName(block))
                    }
                } else if (entry.startsWith("data/minecraft/worldgen/template_pool/village/") && entry.endsWith(".json")) {
                    val elements = parseJson(jar.getInputStream(jar.getEntry(entry)).bufferedReader().readText())
                        .jsonObject.getValue("elements").jsonArray
                    for (element in elements) {
                        val processor = element.jsonObject.getValue("element").jsonObject["processors"]
                        if (processor.asText() != null) processors.add(processor.asText()!!)
                        else if (processor != null) collectOutputs(processor)
                    }
                }
            }
            for (processor in processors) {
                val namespace = processor.substringBefore(':')
                val path = processor.substringAfter(':')
                val entry = jar.getEntry("data/$namespace/worldgen/processor_list/$path.json")
                collectOutputs(parseJson(jar.getInputStream(entry).bufferedReader().readText()))
            }
        }
        return (names + outputs).map { it.substringAfter(':') }.toSet()
    }

    private fun thirtySeconds(coordinate: String): String {
        val scaled = coordinate.toDouble() * 32.0
        val rounded = Math.rint(scaled)
        require(abs(scaled - rounded) < 1e-6) { "shape coordinate $coordinate is not a multiple of 1/32" }
        return rounded.roundToInt().toString()
    }

    /** Every distinct shape entry, and which one each state uses. */
    private fun shapeTables(dumps: Path, blocks: JsonObject, serverJar: Path): Map<String, Any> {
        val entries = LinkedHashMap<String, Int>()
        val index = HashMap<Int, Int>()
        var blocksMotion: Map<Int, Boolean>? = null
        for (original in readTsv(dumps.resolve("shapes.tsv"))) {
            val row = original.toMutableList()
            if (row[2] == "?") {
                // parity: after 26.2 `blocksMotion` is the `minecraft:blocks_motion` block tag.
                if (blocksMotion == null) {
                    val tagged = resolveTag(serverJar, "block", "minecraft:blocks_motion")
                    val owner = HashMap<Int, String>()
                    for ((name, block) in blocks) {
                        for (state in block.jsonObject.getValue("states").jsonArray) owner[state.jsonObject.getValue("id").asInt()] = name
                    }
                    blocksMotion = owner.mapValues { it.value in tagged }
                }
                row[2] = if (blocksMotion.getValue(row[0].toInt())) "1" else "0"
            }
            val boxes = row[10].split(';').filter { it.isNotEmpty() }
                .joinToString(";") { box -> box.split(',').joinToString(",") { thirtySeconds(it) } }
            val entry = row.subList(1, 10).joinToString("|") + "|" + boxes
            index[row[0].toInt()] = entries.getOrPut(entry) { entries.size }
        }
        val byState = IntArray(index.keys.max() + 1)
        for ((state, entry) in index) byState[state] = entry
        return mapOf(
            "kBlockShapes" to entries.keys.map(::cstr),
            "kBlockShapeIndexTable" to joinLiteral(encodeIndex(byState.toList())),
        )
    }

    private fun supportTables(dumps: Path): Map<String, Any> {
        val wetByState = HashMap<Int, List<String>>()
        val support = ArrayList<List<String>>()
        val randomTick = ArrayList<String>()
        val conductors = ArrayList<String>()
        val push = ArrayList<String>()
        val brewing = ArrayList<String>()
        val families = ArrayList<Pair<Int, String>>()
        val fire = ArrayList<String>()
        val weathering = ArrayList<String>()
        val fuels = ArrayList<String>()
        for (row in readTsv(dumps.resolve("support.tsv"))) {
            when (row[0]) {
                "RANDOMTICK" -> randomTick.add(row[1])
                "CONDUCTOR" -> conductors.add(row[1])
                "PUSH" -> {
                    val name = pushReactionNames[row[2]] ?: error("push reaction ${row[2]} has no name in the table")
                    push.add("{${row[1]}, ${cstr(name)}}")
                }
                "BREW" -> brewing.add("{" + row.subList(1, 5).joinToString(", ") { cstr(it) } + "}")
                "WET" -> wetByState[row[1].toInt()] = row.drop(2).map { it.substringAfter('=') }
                "SUPPORT" -> support.add(row)
                "FAMILY" -> families.add(row[1].toInt() to row[2])
                "FIRE" -> fire.add("{${row[1]}, ${row[2]}, ${row[3]}}")
                "COPPER" -> weathering.add("{${cstr(row[1])}, ${cstr(row[2])}}")
                "FUEL" -> fuels.add("{${cstr(row[1])}, ${row[2]}}")
            }
        }
        check(support.isNotEmpty()) { "the support harness printed nothing" }

        val sets = LinkedHashMap<String, Int>()
        val rules = LinkedHashMap<String, Int>()

        fun token(value: String): String =
            if (value in setOf("", "*", "c", "r")) value else "#" + sets.getOrPut(value) { sets.size }

        val index = IntArray(support.maxOf { it[1].toInt() } + 1)
        for (row in support) {
            val state = row[1].toInt()
            // No single neighbour holds it up, or something must not be beside it: not expressible here.
            if (row[2] == "0" || row[3] == "0") {
                index[state] = UNKNOWN_SUPPORT
                continue
            }
            val dry = row.drop(4).map { it.substringAfter('=') }
            val wet = wetByState[state] ?: List(dry.size) { "" }
            val rule = (dry + wet).joinToString("|") { token(it) }
            index[state] = FIRST_SUPPORT_RULE + rules.getOrPut(rule) { rules.size }
        }

        val byFamily = LinkedHashMap<String, MutableList<Int>>()
        for ((block, names) in families) {
            for (name in names.split(',').filter { it.isNotEmpty() }) byFamily.getOrPut(name) { ArrayList() }.add(block)
        }
        return mapOf(
            "kSupportRulesTable" to rules.keys.map(::cstr),
            "kSupportSetsTable" to sets.keys.map { members -> cstr(blockSet(members.split(',').map { it.toInt() })) },
            "kBlockSupportIndexTable" to joinLiteral(encodeIndex(index.toList())),
            "kRandomTickingStatesTable" to randomTick,
            "kFuelValuesTable" to fuels,
            "kRedstoneConductorStatesTable" to conductors,
            "kPushReactionsTable" to push,
            "kBrewingMixesTable" to brewing,
            "kWeatheringStepsTable" to weathering,
            "kFireOddsTable" to fire,
            "kBlockFamiliesTable" to byFamily.map { (name, ids) -> "{${cstr(name)}, ${cstr(blockSet(ids))}}" },
        )
    }

    /** Each state's map colour as runs over state ids, and the 64 colours themselves. */
    private fun mapColourTables(dumps: Path): Map<String, Any> {
        val runs = ArrayList<String>()
        val colours = ArrayList<String>()
        var last: String? = null
        for (row in readTsv(dumps.resolve("map_colors.tsv"))) {
            if (row[0] == "state") {
                if (row[2] != last) {
                    runs.add("{${row[1]}, ${row[2]}}")
                    last = row[2]
                }
            } else if (row[0] == "colour") {
                colours.add(row[2])
            }
        }
        check(colours.size == 64) { "the map colour harness did not print 64 colours" }
        return mapOf("kBlockMapColorsTable" to runs, "kMapColorsTable" to colours)
    }
}
