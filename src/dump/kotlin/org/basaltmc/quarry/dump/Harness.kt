package org.basaltmc.quarry.dump

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import net.minecraft.SharedConstants
import net.minecraft.core.Registry
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.ServerPacksSource
import net.minecraft.server.packs.resources.MultiPackResourceManager
import net.minecraft.tags.TagLoader

private val dumps: Map<String, (PrintWriter) -> Unit> = mapOf(
    "blocks" to ::dumpBlocks,
    "shapes" to ::dumpShapes,
    "items" to ::dumpItems,
    "block_entities" to ::dumpBlockEntities,
    "entities" to ::dumpEntities,
    "world" to ::dumpWorld,
    "support" to BlockSupport::dump,
    "map_colors" to ::dumpMapColors,
    "block_sounds" to ::dumpBlockSounds,
)

/**
 * Bootstraps the real registries once, binds the vanilla tags, and runs the dumps named on the command line, each
 * into its own `<name>.tsv` under the output directory. Output goes to files because the game installs its own
 * logger over stdout, which wraps every line in a timestamp.
 */
public fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("usage: Harness <out-dir> <dump>...")
        exitProcess(2)
    }
    val out = Path.of(args[0])
    Files.createDirectories(out)
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()
    bindVanillaTags()
    for (name in args.drop(1)) {
        val dump = dumps[name] ?: throw IllegalArgumentException("unknown dump $name")
        PrintWriter(Files.newBufferedWriter(out.resolve("$name.tsv"))).use(dump)
    }
}

/** Without the vanilla tags every `state.is(SOME_TAG)` is false and a sapling stands on nothing. */
private fun bindVanillaTags() {
    // A pack of its own until 26.2; since, a holder whose `fullResources` is that pack.
    val vanilla: Any = ServerPacksSource.createVanillaPackSource()
    val pack = vanilla as? PackResources ?: vanilla.javaClass.getMethod("fullResources").invoke(vanilla) as PackResources
    val manager = MultiPackResourceManager(PackType.SERVER_DATA, listOf(pack))
    val registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
    for (pending in TagLoader.loadTagsForExistingRegistries(manager, registries)) {
        (pending as Registry.PendingTags<*>).apply()
    }
}
