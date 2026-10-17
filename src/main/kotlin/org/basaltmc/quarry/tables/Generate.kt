package org.basaltmc.quarry.tables

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText

/** The generated sources, by file name, and the folder of `server-registry` each one belongs in. */
private val outputs = linkedMapOf(
    "VanillaTables.cpp" to "server-registry/src",
    "VanillaBlocks.cpp" to "server-registry/src",
    "VanillaEntries.cpp" to "server-registry/src",
    "WorldTables.cpp" to "server-registry/src",
    "VanillaTables.hpp" to "server-registry/include/basalt/server/registry",
    "VanillaBlocks.hpp" to "server-registry/include/basalt/server/registry",
    "VanillaEntries.hpp" to "server-registry/include/basalt/server/registry",
)

public object Generate {
    /** Builds every table from the reports, the dumps and the unpacked [serverJar]. */
    internal fun tables(reports: Reports, dumps: Path, serverJar: Path): Map<String, Any> {
        val tables = LinkedHashMap<String, Any>()
        tables.putAll(BlockTables.build(reports, dumps, serverJar))
        tables.putAll(ItemTables.components(reports.itemComponents()))
        tables.putAll(ItemTables.harness(dumps))
        tables.putAll(ItemTables.world(reports, dumps, serverJar))
        // Empty when the jar keeps these in data rather than in code.
        if ((tables["kFuelValuesTable"] as List<*>).isEmpty()) {
            tables["kFuelValuesTable"] = Recipes.fuel(reports.itemComponents(), serverJar)
        }
        if ((tables["kBrewingMixesTable"] as List<*>).isEmpty()) {
            val potions = reports.registry("minecraft:potion").map { "minecraft:${it.second}" }
            tables["kBrewingMixesTable"] = Recipes.brewing(serverJar, potions)
        }
        return tables
    }

    private val placeholder = Regex("@(version|size|body|join)(?::(\\w+))?@")

    internal fun render(template: String, tables: Map<String, Any>, version: String): String =
        placeholder.replace(template) { match ->
            val kind = match.groupValues[1]
            val name = match.groupValues[2]
            if (kind == "version") return@replace version
            val table = tables[name] ?: error("no table named $name")
            when (kind) {
                "size" -> (table as List<*>).size.toString()
                "join" -> table as String
                else -> (table as List<*>).joinToString("") { "    $it,\n" }.trimEnd('\n')
            }
        }

    private fun format(text: String, style: Path, target: Path): String {
        val process = ProcessBuilder("clang-format", "--style=file:$style", "--assume-filename=$target")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.use { it.write(text.toByteArray()) }
        val formatted = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "clang-format failed on $target" }
        return formatted
    }

    /** Writes the sources under [out], formatted with the `.clang-format` at [style]. */
    public fun write(
        reportsDir: Path,
        dumps: Path,
        serverJar: Path,
        out: Path,
        version: String,
        style: Path,
    ): List<Path> {
        val tables = tables(Reports(reportsDir), dumps, serverJar)
        val written = ArrayList<Path>()
        for ((name, folder) in outputs) {
            val source = Generate::class.java.getResourceAsStream("/templates/$name.in") ?: error("no template $name")
            val template = source
                .bufferedReader().readText()
            val target = out.resolve(folder).resolve(name)
            target.parent.createDirectories()
            target.toFile().writeText(format(render(template, tables, version), style, target))
            written.add(target)
        }
        return written
    }
}
