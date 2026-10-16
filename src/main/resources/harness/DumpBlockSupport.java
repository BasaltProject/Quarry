import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.PushReaction;

/**
 * Asks the real block classes what each state needs under or beside it to stay put, plus block
 * families, fire odds, weathering, random ticks, redstone conductors, piston reactions, fuels and
 * brewing.
 *
 * canSurvive is code, not data, so the only honest way to get it is to call it. The level it is called
 * against is a proxy holding a handful of positions: default methods run their own bodies, the few
 * abstract ones answer from that map.
 */
final class DumpBlockSupport {
    private static final Map<Long, BlockState> WORLD = new HashMap<>();
    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    private DumpBlockSupport() {}

    static void dump(PrintWriter writer) throws ReflectiveOperationException {
        LevelReader level = (LevelReader) Proxy.newProxyInstance(
                DumpBlockSupport.class.getClassLoader(), new Class<?>[] {LevelReader.class}, DumpBlockSupport::handle);

        List<Block> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) blocks.add(block);
        PushReaction moves = normalReaction();

        StringBuilder out = new StringBuilder();
        for (Block block : blocks) {
            StringBuilder families = new StringBuilder();
            BlockState state = block.defaultBlockState();
            if (block instanceof FenceBlock) families.append("fence,");
            if (block instanceof FenceGateBlock) families.append("gate,");
            if (block instanceof IronBarsBlock) families.append("bars,");
            if (block instanceof LeavesBlock) families.append("leaves,");
            if (block instanceof WallBlock) families.append("wall,");
            if (state.is(BlockTags.FENCES)) families.append("fences_tag,");
            if (state.is(BlockTags.WOODEN_FENCES)) families.append("wooden_fences_tag,");
            if (state.is(BlockTags.WALLS)) families.append("walls_tag,");
            if (state.is(BlockTags.PREVENTS_NEARBY_LEAF_DECAY)) families.append("prevents_decay,");
            if (state.is(BlockTags.MAINTAINS_FARMLAND)) families.append("maintains_farmland,");
            if (state.is(BlockTags.SUPPORTS_CACTUS)) families.append("supports_cactus,");
            if (state.is(BlockTags.SNIFFER_EGG_HATCH_BOOST)) families.append("hatch_boost,");
            // What a piston refuses to move whatever its push reaction says: the last line of
            // `PistonBaseBlock.isPushable` is `!state.hasBlockEntity()`.
            if (state.hasBlockEntity()) families.append("block_entity,");
            if (state.is(BlockTags.CAUSES_CONTINUOUS_GEYSER_ERUPTIONS)) families.append("geyser_continuous,");
            if (state.is(BlockTags.CAUSES_PERIODIC_GEYSER_ERUPTIONS)) families.append("geyser_periodic,");
            // Fallable, not FallingBlock: suspicious sand and gravel fall the same way and are the only
            // two that implement it without extending it.
            if (block instanceof Fallable) families.append("falling,");
            if (block instanceof StairBlock) families.append("stairs,");
            if (state.is(BlockTags.SOUL_FIRE_BASE_BLOCKS)) families.append("soul_fire_base,");
            if (Block.isExceptionForConnection(state)) families.append("exception,");
            if (families.length() > 0) {
                out.append("FAMILY\t").append(BuiltInRegistries.BLOCK.getId(block)).append('\t').append(families)
                        .append('\n');
            }
        }
        // The two tables `FireBlock` keeps to itself: what catches, and how readily it burns away.
        try {
            Field igniteField = FireBlock.class.getDeclaredField("igniteOdds");
            Field burnField = FireBlock.class.getDeclaredField("burnOdds");
            igniteField.setAccessible(true);
            burnField.setAccessible(true);
            Object ignite = igniteField.get(Blocks.FIRE);
            Object burn = burnField.get(Blocks.FIRE);
            Method getInt = ignite.getClass().getMethod("getInt", Object.class);
            for (Block block : blocks) {
                int igniteOdds = (Integer) getInt.invoke(ignite, block);
                int burnOdds = (Integer) getInt.invoke(burn, block);
                if (igniteOdds == 0 && burnOdds == 0) continue;
                out.append("FIRE\t").append(BuiltInRegistries.BLOCK.getId(block)).append('\t').append(igniteOdds)
                        .append('\t').append(burnOdds).append('\n');
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireBlock odds moved: " + failure, failure);
        }
        // What each copper block turns into as it weathers, from `WeatheringCopper.NEXT_BY_BLOCK`.
        for (Block block : blocks) {
            Optional<Block> next = WeatheringCopper.getNext(block);
            if (next.isEmpty()) continue;
            out.append("COPPER\t").append(BuiltInRegistries.BLOCK.getKey(block).getPath()).append('\t')
                    .append(BuiltInRegistries.BLOCK.getKey(next.get()).getPath()).append('\n');
        }
        for (Block block : blocks) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int id = Block.getId(state);
                if (state.isRandomlyTicking()) out.append("RANDOMTICK\t").append(id).append('\n');
                // parity: `BlockBehaviour.Properties.isRedstoneConductor`.
                if (state.isRedstoneConductor(level, ORIGIN)) out.append("CONDUCTOR\t").append(id).append('\n');
                // Only the states a piston does not simply move.
                if (state.getPistonPushReaction() != moves) {
                    out.append("PUSH\t").append(id).append('\t').append(state.getPistonPushReaction().name())
                            .append('\n');
                }
                WORLD.clear();
                if (survives(state, level)) continue;
                probeSupport(state, id, blocks, level, out);
            }
        }
        dumpFuels(out);
        dumpBrewing(out);
        writer.print(out);
    }

    /** One `SUPPORT` line per state that cannot stand alone, and a `WET` line where water changes it. */
    private static void probeSupport(BlockState state, int id, List<Block> blocks, LevelReader level,
            StringBuilder out) {
        StringBuilder line = new StringBuilder();
        StringBuilder wetLine = new StringBuilder();
        boolean any = false;
        boolean crowded = true;
        for (Direction direction : Direction.values()) {
            StringBuilder members = new StringBuilder();
            StringBuilder wetMembers = new StringBuilder();
            Block first = null;
            for (Block candidate : blocks) {
                WORLD.clear();
                WORLD.put(ORIGIN.relative(direction).asLong(), candidate.defaultBlockState());
                if (survives(state, level)) {
                    if (members.length() > 0) members.append(',');
                    members.append(BuiltInRegistries.BLOCK.getId(candidate));
                    any = true;
                    if (first == null) first = candidate;
                }
                // The same question in water: sugar cane wants a river beside it and kelp wants to be
                // under one. A block that answers here and not above is only ever kept, never broken.
                flood(direction, candidate);
                if (survives(state, level)) {
                    if (wetMembers.length() > 0) wetMembers.append(',');
                    wetMembers.append(BuiltInRegistries.BLOCK.getId(candidate));
                    any = true;
                }
            }
            if (wetLine.length() > 0) wetLine.append('\t');
            wetLine.append(direction.getName()).append('=')
                    .append(wetMembers.toString().contentEquals(members) ? "" : wetMembers);
            // Whether that same support still holds with the other five sides walled in: a block that
            // says no here has a condition about what must *not* be next to it.
            if (first != null) {
                WORLD.clear();
                for (Direction other : Direction.values()) {
                    WORLD.put(ORIGIN.relative(other).asLong(),
                            (other == direction ? first : Blocks.STONE).defaultBlockState());
                }
                if (!survives(state, level)) crowded = false;
            }
            if (line.length() > 0) line.append('\t');
            line.append(direction.getName()).append('=').append(shorten(members.toString(), direction, blocks, level));
        }
        out.append("SUPPORT\t").append(id).append('\t').append(any ? "1" : "0").append('\t')
                .append(crowded ? "1" : "0").append('\t').append(line).append('\n');
        if (wetLine.toString().replace("=", "").replace("\t", "").replaceAll("[a-z]", "").length() > 0) {
            out.append("WET\t").append(id).append('\t').append(wetLine).append('\n');
        }
    }

    /** `NORMAL` until 26.2, `PUSH_PULL` since. */
    private static PushReaction normalReaction() {
        for (PushReaction reaction : PushReaction.values()) {
            if (reaction.name().equals("NORMAL") || reaction.name().equals("PUSH_PULL")) return reaction;
        }
        throw new IllegalStateException("no PushReaction moves a block normally");
    }

    /**
     * parity: `FuelValues.vanillaBurnTimes`, read straight off the map it keeps. The class is gone after
     * 26.2, where fuel is the `minecraft:cooking_fuel` item component and the driver reads it instead.
     */
    private static void dumpFuels(StringBuilder out) {
        Class<?> fuelValues;
        try {
            fuelValues = Class.forName("net.minecraft.world.level.block.entity.FuelValues");
        } catch (ClassNotFoundException gone) {
            return;
        }
        try {
            Method vanilla = null;
            for (Method method : fuelValues.getMethods()) {
                if (method.getName().equals("vanillaBurnTimes") && method.getParameterCount() == 2) vanilla = method;
            }
            if (vanilla == null) throw new NoSuchMethodException("FuelValues.vanillaBurnTimes");
            Object fuels = vanilla.invoke(null,
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), FeatureFlags.VANILLA_SET);
            Field valuesField = fuelValues.getDeclaredField("values");
            valuesField.setAccessible(true);
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) valuesField.get(fuels)).entrySet()) {
                int ticks = ((Number) entry.getValue()).intValue();
                if (ticks <= 0) continue;
                out.append("FUEL\t").append(BuiltInRegistries.ITEM.getKey((Item) entry.getKey()).getPath())
                        .append('\t').append(ticks).append('\n');
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FuelValues moved: " + failure, failure);
        }
    }

    /**
     * parity: `PotionBrewing.bootstrap`: base potion, ingredient, result. The class is gone after 26.2,
     * where brewing is `minecraft:brewing` recipes and the driver reads those instead.
     */
    private static void dumpBrewing(StringBuilder out) {
        Class<?> potionBrewing;
        try {
            potionBrewing = Class.forName("net.minecraft.world.item.alchemy.PotionBrewing");
        } catch (ClassNotFoundException gone) {
            return;
        }
        try {
            Object brewing = potionBrewing.getMethod("bootstrap", FeatureFlagSet.class)
                    .invoke(null, FeatureFlags.VANILLA_SET);
            for (String field : new String[] {"potionMixes", "containerMixes"}) {
                Field mixes = potionBrewing.getDeclaredField(field);
                mixes.setAccessible(true);
                for (Object mix : (List<?>) mixes.get(brewing)) {
                    Method from = mix.getClass().getDeclaredMethod("from");
                    Method to = mix.getClass().getDeclaredMethod("to");
                    Method ingredient = mix.getClass().getDeclaredMethod("ingredient");
                    from.setAccessible(true);
                    to.setAccessible(true);
                    ingredient.setAccessible(true);
                    StringBuilder items = new StringBuilder();
                    ((Ingredient) ingredient.invoke(mix)).items().forEach(held -> {
                        if (items.length() > 0) items.append(',');
                        items.append(BuiltInRegistries.ITEM.getKey(held.value()).getPath());
                    });
                    out.append("BREW\t").append(field).append('\t').append(describeHolder(from.invoke(mix)))
                            .append('\t').append(items).append('\t').append(describeHolder(to.invoke(mix)))
                            .append('\n');
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("PotionBrewing moved: " + failure, failure);
        }
    }

    /** A holder of a potion or an item, named the way the registry does. */
    private static String describeHolder(Object holder) {
        Object value = ((Holder<?>) holder).value();
        if (value instanceof Item item) return BuiltInRegistries.ITEM.getKey(item).getPath();
        return BuiltInRegistries.POTION.getKey((Potion) value).getPath();
    }

    /**
     * Replaces a member list with `*`, `c` or `r` when it is exactly the blocks whose face towards this
     * one is sturdy, supports a centre, or is rigid. Naming the condition keeps it right for states the
     * probe never placed: an upside-down slab holds a torch, its default state does not.
     */
    private static String shorten(String members, Direction direction, List<Block> blocks, LevelReader level) {
        if (members.isEmpty()) return members;
        StringBuilder full = new StringBuilder();
        StringBuilder centre = new StringBuilder();
        StringBuilder rigid = new StringBuilder();
        Direction facing = direction.getOpposite();
        BlockPos pos = ORIGIN.relative(direction);
        for (Block candidate : blocks) {
            WORLD.clear();
            BlockState support = candidate.defaultBlockState();
            WORLD.put(pos.asLong(), support);
            int id = BuiltInRegistries.BLOCK.getId(candidate);
            if (support.isFaceSturdy(level, pos, facing, SupportType.FULL)) {
                if (full.length() > 0) full.append(',');
                full.append(id);
            }
            if (support.isFaceSturdy(level, pos, facing, SupportType.CENTER)) {
                if (centre.length() > 0) centre.append(',');
                centre.append(id);
            }
            if (support.isFaceSturdy(level, pos, facing, SupportType.RIGID)) {
                if (rigid.length() > 0) rigid.append(',');
                rigid.append(id);
            }
        }
        if (members.contentEquals(full)) return "*";
        if (members.contentEquals(centre)) return "c";
        if (members.contentEquals(rigid)) return "r";
        return members;
    }

    /** The candidate in place, water at this position and around it: the wet half of every probe. */
    private static void flood(Direction direction, Block candidate) {
        WORLD.clear();
        WORLD.put(ORIGIN.asLong(), Blocks.WATER.defaultBlockState());
        for (Direction side : Direction.Plane.HORIZONTAL) {
            WORLD.put(ORIGIN.relative(side).asLong(), Blocks.WATER.defaultBlockState());
            // Sugar cane looks beside the block it stands on, not beside itself.
            WORLD.put(ORIGIN.below().relative(side).asLong(), Blocks.WATER.defaultBlockState());
        }
        WORLD.put(ORIGIN.relative(direction).asLong(), candidate.defaultBlockState());
    }

    private static boolean survives(BlockState state, LevelReader level) {
        try {
            return state.canSurvive(level, ORIGIN);
        } catch (Throwable t) {
            return true;
        }
    }

    private static Object handle(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (name.equals("getBlockState") && args != null && args.length == 1) {
            BlockState state = WORLD.get(((BlockPos) args[0]).asLong());
            return state == null ? Blocks.AIR.defaultBlockState() : state;
        }
        if (name.equals("getFluidState") && args != null && args.length == 1) {
            BlockState state = WORLD.get(((BlockPos) args[0]).asLong());
            return state == null ? Fluids.EMPTY.defaultFluidState() : state.getFluidState();
        }
        if (name.equals("getBlockEntity")) return null;
        if (name.equals("getMinY")) return 0;
        if (name.equals("getHeight") && method.getParameterCount() == 0) return 384;
        if (name.equals("isClientSide")) return false;
        // Full daylight: a crop asks for light in the same canSurvive this probe measures support with.
        if (name.equals("getRawBrightness") || name.equals("getBrightness")
                || name.equals("getMaxLocalRawBrightness")) {
            return 15;
        }
        if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
        Class<?> returnType = method.getReturnType();
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return null;
    }
}
