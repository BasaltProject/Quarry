package org.basaltmc.quarry.dump

import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.tags.BlockTags
import net.minecraft.world.flag.FeatureFlagSet
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.item.Item
import net.minecraft.world.item.alchemy.Potion
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Fallable
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FireBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.SupportType
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.WeatheringCopper
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.level.material.PushReaction

/**
 * Asks the real block classes what each state needs under or beside it to stay put, plus block families, fire odds,
 * weathering, random ticks, redstone conductors, piston reactions, fuels and brewing.
 *
 * `canSurvive` is code, not data, so the only honest way to get it is to call it. The level it is called against is a
 * proxy holding a handful of positions: default methods run their own bodies, the few abstract ones answer from that
 * map.
 */
internal object BlockSupport {
    private val world = HashMap<Long, BlockState>()
    private val origin = BlockPos(0, 64, 0)

    fun dump(writer: PrintWriter) {
        val level = Proxy.newProxyInstance(
            BlockSupport::class.java.classLoader, arrayOf(LevelReader::class.java), ::handle,
        ) as LevelReader
        val blocks = BuiltInRegistries.BLOCK.toList()
        val moves = normalReaction()
        val out = StringBuilder()
        for (block in blocks) {
            val families = families(block)
            if (families.isNotEmpty()) {
                out.append("FAMILY\t").append(BuiltInRegistries.BLOCK.getId(block)).append('\t').append(families)
                    .append('\n')
            }
        }
        fireOdds(blocks, out)
        for (block in blocks) {
            val next = WeatheringCopper.getNext(block)
            if (next.isEmpty) continue
            out.append("COPPER\t").append(BuiltInRegistries.BLOCK.getKey(block).path).append('\t')
                .append(BuiltInRegistries.BLOCK.getKey(next.get()).path).append('\n')
        }
        for (block in blocks) {
            for (state in block.stateDefinition.possibleStates) {
                val id = Block.getId(state)
                if (state.isRandomlyTicking) out.append("RANDOMTICK\t").append(id).append('\n')
                // parity: `BlockBehaviour.Properties.isRedstoneConductor`.
                if (state.isRedstoneConductor(level, origin)) out.append("CONDUCTOR\t").append(id).append('\n')
                if (state.pistonPushReaction != moves) {
                    out.append("PUSH\t").append(id).append('\t').append(state.pistonPushReaction.name).append('\n')
                }
                world.clear()
                if (survives(state, level)) continue
                probeSupport(state, id, blocks, level, out)
            }
        }
        dumpFuels(out)
        dumpBrewing(out)
        writer.print(out)
    }

    private fun families(block: Block): String {
        val state = block.defaultBlockState()
        val out = StringBuilder()
        if (block is FenceBlock) out.append("fence,")
        if (block is FenceGateBlock) out.append("gate,")
        if (block is IronBarsBlock) out.append("bars,")
        if (block is LeavesBlock) out.append("leaves,")
        if (block is WallBlock) out.append("wall,")
        if (state.`is`(BlockTags.FENCES)) out.append("fences_tag,")
        if (state.`is`(BlockTags.WOODEN_FENCES)) out.append("wooden_fences_tag,")
        if (state.`is`(BlockTags.WALLS)) out.append("walls_tag,")
        if (state.`is`(BlockTags.PREVENTS_NEARBY_LEAF_DECAY)) out.append("prevents_decay,")
        if (state.`is`(BlockTags.MAINTAINS_FARMLAND)) out.append("maintains_farmland,")
        if (state.`is`(BlockTags.SUPPORTS_CACTUS)) out.append("supports_cactus,")
        if (state.`is`(BlockTags.SNIFFER_EGG_HATCH_BOOST)) out.append("hatch_boost,")
        // What a piston refuses to move whatever its push reaction says: the last line of `PistonBaseBlock.isPushable`.
        if (state.hasBlockEntity()) out.append("block_entity,")
        if (state.`is`(BlockTags.CAUSES_CONTINUOUS_GEYSER_ERUPTIONS)) out.append("geyser_continuous,")
        if (state.`is`(BlockTags.CAUSES_PERIODIC_GEYSER_ERUPTIONS)) out.append("geyser_periodic,")
        // Fallable, not FallingBlock: suspicious sand and gravel fall the same way and implement it without extending it.
        if (block is Fallable) out.append("falling,")
        if (block is StairBlock) out.append("stairs,")
        if (state.`is`(BlockTags.SOUL_FIRE_BASE_BLOCKS)) out.append("soul_fire_base,")
        if (Block.isExceptionForConnection(state)) out.append("exception,")
        return out.toString()
    }

    /** The two tables `FireBlock` keeps to itself: what catches, and how readily it burns away. */
    private fun fireOdds(blocks: List<Block>, out: StringBuilder) {
        try {
            val igniteField = FireBlock::class.java.getDeclaredField("igniteOdds").also { it.isAccessible = true }
            val burnField = FireBlock::class.java.getDeclaredField("burnOdds").also { it.isAccessible = true }
            val ignite = igniteField.get(Blocks.FIRE)
            val burn = burnField.get(Blocks.FIRE)
            val getInt = ignite.javaClass.getMethod("getInt", Any::class.java)
            for (block in blocks) {
                val igniteOdds = getInt.invoke(ignite, block) as Int
                val burnOdds = getInt.invoke(burn, block) as Int
                if (igniteOdds == 0 && burnOdds == 0) continue
                out.append("FIRE\t").append(BuiltInRegistries.BLOCK.getId(block)).append('\t').append(igniteOdds)
                    .append('\t').append(burnOdds).append('\n')
            }
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("FireBlock odds moved: $failure", failure)
        }
    }

    /** One `SUPPORT` line per state that cannot stand alone, and a `WET` line where water changes it. */
    private fun probeSupport(state: BlockState, id: Int, blocks: List<Block>, level: LevelReader, out: StringBuilder) {
        val line = StringBuilder()
        val wetLine = StringBuilder()
        var any = false
        var crowded = true
        for (direction in Direction.values()) {
            val members = StringBuilder()
            val wetMembers = StringBuilder()
            var first: Block? = null
            for (candidate in blocks) {
                world.clear()
                world[origin.relative(direction).asLong()] = candidate.defaultBlockState()
                if (survives(state, level)) {
                    if (members.isNotEmpty()) members.append(',')
                    members.append(BuiltInRegistries.BLOCK.getId(candidate))
                    any = true
                    if (first == null) first = candidate
                }
                // The same question in water: sugar cane wants a river beside it and kelp wants to be under one.
                flood(direction, candidate)
                if (survives(state, level)) {
                    if (wetMembers.isNotEmpty()) wetMembers.append(',')
                    wetMembers.append(BuiltInRegistries.BLOCK.getId(candidate))
                    any = true
                }
            }
            if (wetLine.isNotEmpty()) wetLine.append('\t')
            wetLine.append(direction.getName()).append('=')
                .append(if (wetMembers.contentEquals(members)) "" else wetMembers)
            // Whether that same support still holds with the other five sides walled in: a block that says no here
            // has a condition about what must not be next to it.
            if (first != null) {
                world.clear()
                for (other in Direction.values()) {
                    world[origin.relative(other).asLong()] =
                        (if (other == direction) first else Blocks.STONE).defaultBlockState()
                }
                if (!survives(state, level)) crowded = false
            }
            if (line.isNotEmpty()) line.append('\t')
            line.append(direction.getName()).append('=').append(shorten(members.toString(), direction, blocks, level))
        }
        out.append("SUPPORT\t").append(id).append('\t').append(if (any) "1" else "0").append('\t')
            .append(if (crowded) "1" else "0").append('\t').append(line).append('\n')
        if (wetLine.toString().replace("=", "").replace("\t", "").replace(Regex("[a-z]"), "").isNotEmpty()) {
            out.append("WET\t").append(id).append('\t').append(wetLine).append('\n')
        }
    }

    /** `NORMAL` until 26.2, `PUSH_PULL` since. */
    private fun normalReaction(): PushReaction =
        PushReaction.values().firstOrNull { it.name == "NORMAL" || it.name == "PUSH_PULL" }
            ?: throw IllegalStateException("no PushReaction moves a block normally")

    /**
     * parity: `FuelValues.vanillaBurnTimes`, read straight off the map it keeps. The class is gone after 26.2, where
     * fuel is the `minecraft:cooking_fuel` item component and the tables read that instead.
     */
    private fun dumpFuels(out: StringBuilder) {
        val fuelValues = try {
            Class.forName("net.minecraft.world.level.block.entity.FuelValues")
        } catch (_: ClassNotFoundException) {
            return
        }
        try {
            val vanilla = fuelValues.methods.lastOrNull { it.name == "vanillaBurnTimes" && it.parameterCount == 2 }
                ?: throw NoSuchMethodException("FuelValues.vanillaBurnTimes")
            val fuels = vanilla.invoke(
                null, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), FeatureFlags.VANILLA_SET,
            )
            val values = fuelValues.getDeclaredField("values").also { it.isAccessible = true }.get(fuels) as Map<*, *>
            for ((item, burn) in values) {
                val ticks = (burn as Number).toInt()
                if (ticks <= 0) continue
                out.append("FUEL\t").append(BuiltInRegistries.ITEM.getKey(item as Item).path).append('\t')
                    .append(ticks).append('\n')
            }
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("FuelValues moved: $failure", failure)
        }
    }

    /**
     * parity: `PotionBrewing.bootstrap`: base potion, ingredient, result. The class is gone after 26.2, where brewing is
     * `minecraft:brewing` recipes and the tables read those instead.
     */
    private fun dumpBrewing(out: StringBuilder) {
        val potionBrewing = try {
            Class.forName("net.minecraft.world.item.alchemy.PotionBrewing")
        } catch (_: ClassNotFoundException) {
            return
        }
        try {
            val brewing = potionBrewing.getMethod("bootstrap", FeatureFlagSet::class.java).invoke(null, FeatureFlags.VANILLA_SET)
            for (field in listOf("potionMixes", "containerMixes")) {
                val mixes = potionBrewing.getDeclaredField(field).also { it.isAccessible = true }
                for (mix in mixes.get(brewing) as List<*>) {
                    val type = mix!!.javaClass
                    fun read(name: String): Any? = type.getDeclaredMethod(name).also { it.isAccessible = true }.invoke(mix)
                    val items = (read("ingredient") as Ingredient).items()
                        .map { BuiltInRegistries.ITEM.getKey(it.value()).path }.toList().joinToString(",")
                    out.append("BREW\t").append(field).append('\t').append(describeHolder(read("from"))).append('\t')
                        .append(items).append('\t').append(describeHolder(read("to"))).append('\n')
                }
            }
        } catch (failure: ReflectiveOperationException) {
            throw IllegalStateException("PotionBrewing moved: $failure", failure)
        }
    }

    /** A holder of a potion or an item, named the way the registry does. */
    private fun describeHolder(holder: Any?): String {
        val value = (holder as Holder<*>).value()
        if (value is Item) return BuiltInRegistries.ITEM.getKey(value).path
        return BuiltInRegistries.POTION.getKey(value as Potion)!!.path
    }

    /**
     * Replaces a member list with `*`, `c` or `r` when it is exactly the blocks whose face towards this one is sturdy,
     * supports a centre, or is rigid. Naming the condition keeps it right for states the probe never placed.
     */
    private fun shorten(members: String, direction: Direction, blocks: List<Block>, level: LevelReader): String {
        if (members.isEmpty()) return members
        val full = StringBuilder()
        val centre = StringBuilder()
        val rigid = StringBuilder()
        val facing = direction.opposite
        val pos = origin.relative(direction)
        for (candidate in blocks) {
            world.clear()
            val support = candidate.defaultBlockState()
            world[pos.asLong()] = support
            val id = BuiltInRegistries.BLOCK.getId(candidate)
            for ((kind, list) in listOf(SupportType.FULL to full, SupportType.CENTER to centre, SupportType.RIGID to rigid)) {
                if (support.isFaceSturdy(level, pos, facing, kind)) {
                    if (list.isNotEmpty()) list.append(',')
                    list.append(id)
                }
            }
        }
        return when (members) {
            full.toString() -> "*"
            centre.toString() -> "c"
            rigid.toString() -> "r"
            else -> members
        }
    }

    /** The candidate in place, water at this position and around it: the wet half of every probe. */
    private fun flood(direction: Direction, candidate: Block) {
        world.clear()
        val water = Blocks.WATER.defaultBlockState()
        world[origin.asLong()] = water
        for (side in Direction.Plane.HORIZONTAL) {
            world[origin.relative(side).asLong()] = water
            // Sugar cane looks beside the block it stands on, not beside itself.
            world[origin.below().relative(side).asLong()] = water
        }
        world[origin.relative(direction).asLong()] = candidate.defaultBlockState()
    }

    private fun survives(state: BlockState, level: LevelReader): Boolean =
        try {
            state.canSurvive(level, origin)
        } catch (_: Throwable) {
            true
        }

    private fun handle(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        val name = method.name
        if (name == "getBlockState" && args != null && args.size == 1) {
            return world[(args[0] as BlockPos).asLong()] ?: Blocks.AIR.defaultBlockState()
        }
        if (name == "getFluidState" && args != null && args.size == 1) {
            val state = world[(args[0] as BlockPos).asLong()]
            return state?.fluidState ?: Fluids.EMPTY.defaultFluidState()
        }
        if (name == "getBlockEntity") return null
        if (name == "getMinY") return 0
        if (name == "getHeight" && method.parameterCount == 0) return 384
        if (name == "isClientSide") return false
        // Full daylight: a crop asks for light in the same canSurvive this probe measures support with.
        if (name == "getRawBrightness" || name == "getBrightness" || name == "getMaxLocalRawBrightness") return 15
        if (method.isDefault) return InvocationHandler.invokeDefault(proxy, method, *args.orEmpty())
        return when (method.returnType) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            else -> null
        }
    }
}
