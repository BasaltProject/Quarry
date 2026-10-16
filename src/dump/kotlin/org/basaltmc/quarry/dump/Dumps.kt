package org.basaltmc.quarry.dump

import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.data.worldgen.BootstrapContext
import net.minecraft.data.worldgen.biome.BiomeData
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.DefaultAttributes
import net.minecraft.world.entity.ai.village.poi.PoiType
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SupportType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes

private fun blockName(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).path

/** What each block keeps in `BlockBehaviour.Properties`, which is code and not data. */
internal fun dumpBlocks(out: PrintWriter) {
    for (block in BuiltInRegistries.BLOCK) {
        val state = block.defaultBlockState()
        out.println(
            listOf(
                blockName(block), block.defaultDestroyTime(), state.requiresCorrectToolForDrops(),
                block.explosionResistance, block.friction, block.speedFactor, block.jumpFactor,
                block.bounceRestitution, state.instrument().serializedName,
            ).joinToString("\t"),
        )
    }
}

/** Each block's `SoundType`, read off the default state; only the decorated pot changes it by state. */
internal fun dumpBlockSounds(out: PrintWriter) {
    for (block in BuiltInRegistries.BLOCK) {
        val sound = block.defaultBlockState().soundType
        out.println(
            listOf(
                blockName(block), sound.volume, sound.pitch, sound.breakSound.location().path,
                sound.stepSound.location().path, sound.placeSound.location().path, sound.hitSound.location().path,
                sound.fallSound.location().path,
            ).joinToString("\t"),
        )
    }
}

/** Every block entity type in registry order, which is its protocol id, and the blocks that own one. */
internal fun dumpBlockEntities(out: PrintWriter) {
    for (type in BuiltInRegistries.BLOCK_ENTITY_TYPE) {
        val blocks = BuiltInRegistries.BLOCK.filter { type.isValid(it.defaultBlockState()) }.map(::blockName)
        out.println(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type as BlockEntityType<*>)!!.path + "\t" + blocks.joinToString(","))
    }
}

/** The box, spawn category and tracking of every entity type, and every living type's attribute base values. */
internal fun dumpEntities(out: PrintWriter) {
    for (type in BuiltInRegistries.ENTITY_TYPE) {
        val id = BuiltInRegistries.ENTITY_TYPE.getKey(type).path
        out.println(
            listOf(
                "TYPE", id, type.width, type.height, type.category.getName(), type.clientTrackingRange(),
                type.updateInterval(), type.fireImmune(), type.canSpawnFarFromPlayer(),
            ).joinToString("\t"),
        )
        if (!DefaultAttributes.hasSupplier(type)) continue
        @Suppress("UNCHECKED_CAST")
        val supplier = DefaultAttributes.getSupplier(type as EntityType<out LivingEntity>)
        for (attribute in BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
            if (!supplier.hasAttribute(attribute)) continue
            out.println("ATTRIBUTE\t$id\t${attribute.key().identifier().path}\t${supplier.getBaseValue(attribute)}")
        }
    }
}

/** What items keep in code: the crafting remainder and the block a block item places. */
internal fun dumpItems(out: PrintWriter) {
    for (item in BuiltInRegistries.ITEM) {
        val id = BuiltInRegistries.ITEM.getKey(item).path
        val remainder = item.craftingRemainder
        if (remainder != null) {
            out.println("REMAINDER\t$id\t${BuiltInRegistries.ITEM.getKey(remainder.item().value()).path}")
        }
        if (item is BlockItem) out.println("BLOCK\t$id\t${blockName(item.block)}")
    }
}

/** What a map draws for each block state, then the colour behind each of the 64 map colour ids. */
internal fun dumpMapColors(out: PrintWriter) {
    for (id in 0 until Block.BLOCK_STATE_REGISTRY.size()) {
        val state = Block.BLOCK_STATE_REGISTRY.byId(id)!!
        out.println("state\t$id\t${state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id}")
    }
    for (id in 0 until 64) out.println("colour\t$id\t${MapColor.byId(id).col}")
}

private fun supports(state: BlockState, direction: Direction, support: SupportType): Boolean =
    try {
        state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction, support)
    } catch (_: Throwable) {
        false
    }

private fun flag(value: Boolean): Char = if (value) '1' else '0'

/**
 * Every block state's collision boxes, light dampening, flags and sturdy faces, one line per state. `blocksMotion`
 * left 26.2 for the `minecraft:blocks_motion` block tag; where the method is gone the flag is `?`.
 */
internal fun dumpShapes(out: PrintWriter) {
    val blocksMotion = try {
        BlockState::class.java.getMethod("blocksMotion")
    } catch (_: NoSuchMethodException) {
        null
    }
    for (block in BuiltInRegistries.BLOCK) {
        for (state in block.stateDefinition.possibleStates) {
            val collision = try {
                state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, CollisionContext.empty())
            } catch (_: Throwable) {
                Shapes.block()
            }
            val boxes = collision.toAabbs().joinToString(";") {
                "${it.minX},${it.minY},${it.minZ},${it.maxX},${it.maxY},${it.maxZ}"
            }
            val sturdy = StringBuilder()
            val centre = StringBuilder()
            val rigid = StringBuilder()
            val occlusionFull = StringBuilder()
            for (direction in Direction.values()) {
                sturdy.append(flag(supports(state, direction, SupportType.FULL)))
                centre.append(flag(supports(state, direction, SupportType.CENTER)))
                rigid.append(flag(supports(state, direction, SupportType.RIGID)))
                occlusionFull.append(flag(Block.isShapeFullBlock(state.getFaceOcclusionShape(direction))))
            }
            val motion = if (blocksMotion == null) '?' else flag(blocksMotion.invoke(state) as Boolean)
            out.append(Block.getId(state).toString()).append('\t').append(state.lightDampening.toString()).append('\t')
                .append(motion).append('\t').append(flag(state.canBeReplaced())).append('\t')
                .append(flag(state.isSolid)).append('\t').append(flag(state.isSolidRender)).append('\t')
                .append(sturdy).append('\t').append(centre).append('\t').append(rigid).append('\t')
                .append(occlusionFull).append('\t').append(boxes).append('\n')
        }
    }
}

/** Note block instruments, point of interest types with their blocks, and the biomes in bootstrap order. */
internal fun dumpWorld(out: PrintWriter) {
    for (instrument in NoteBlockInstrument.values()) {
        val kind = if (instrument.isTunable) "BaseBlock" else if (instrument.hasCustomSound()) "Custom" else "MobHead"
        out.println("INSTRUMENT\t${instrument.serializedName}\t${instrument.soundEvent.value().location().path}\t$kind")
    }
    for (type in BuiltInRegistries.POINT_OF_INTEREST_TYPE) {
        val name = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getKey(type as PoiType)!!.path
        for (block in BuiltInRegistries.BLOCK) {
            if (block.stateDefinition.possibleStates.any { type.`is`(it) }) {
                out.println("POI\t$name\t${blockName(block)}")
            }
        }
    }
    dumpBiomes(out)
}

/** A built registry lists its entries in hash order, so the bootstrap runs against a context that records. */
private fun dumpBiomes(out: PrintWriter) {
    val registries = worldLookup()
    val context = Proxy.newProxyInstance(
        BootstrapContext::class.java.classLoader, arrayOf(BootstrapContext::class.java),
    ) { proxy, method, args ->
        if (method.isDefault) return@newProxyInstance InvocationHandler.invokeDefault(proxy, method, *args.orEmpty())
        when (method.name) {
            "lookup", "listContextElements" -> {
                @Suppress("UNCHECKED_CAST")
                val lookup = registries.lookupOrThrow(args[0] as ResourceKey<out Registry<*>>)
                if (method.name == "lookup") lookup else (lookup as HolderLookup<*>).listElements()
            }
            "register" -> {
                out.println("BIOME\t${(args[0] as ResourceKey<*>).identifier().path}")
                null
            }
            else -> throw UnsupportedOperationException(method.name)
        }
    }
    @Suppress("UNCHECKED_CAST")
    BiomeData.bootstrap(context as BootstrapContext<Biome>)
}

/** `createLookup` until 26.2, `createWorldLookup` since the reloadable registries were split off. */
private fun worldLookup(): HolderLookup.Provider {
    for (name in listOf("createWorldLookup", "createLookup")) {
        try {
            return VanillaRegistries::class.java.getMethod(name).invoke(null) as HolderLookup.Provider
        } catch (_: NoSuchMethodException) {
            continue
        }
    }
    throw NoSuchMethodException("VanillaRegistries has no world lookup")
}
