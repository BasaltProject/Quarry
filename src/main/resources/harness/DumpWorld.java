import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.data.worldgen.biome.BiomeData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/**
 * Registries the world tables read that vanilla builds in code: every note block instrument
 * (`INSTRUMENT <name> <sound> <kind>`), every point of interest type with the blocks it is made of
 * (`POI <type> <block>`), and the biomes in `BiomeData.bootstrap` order (`BIOME <name>`).
 */
final class DumpWorld {
    private DumpWorld() {}

    static void dump(PrintWriter out) throws ReflectiveOperationException {
        for (NoteBlockInstrument instrument : NoteBlockInstrument.values()) {
            String kind = instrument.isTunable() ? "BaseBlock" : instrument.hasCustomSound() ? "Custom" : "MobHead";
            out.println("INSTRUMENT\t" + instrument.getSerializedName()
                    + "\t" + instrument.getSoundEvent().value().location().getPath() + "\t" + kind);
        }
        for (PoiType type : BuiltInRegistries.POINT_OF_INTEREST_TYPE) {
            String name = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getKey(type).getPath();
            for (Block block : BuiltInRegistries.BLOCK) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    if (type.is(state)) {
                        out.println("POI\t" + name + "\t" + BuiltInRegistries.BLOCK.getKey(block).getPath());
                        break;
                    }
                }
            }
        }
        dumpBiomes(out);
    }

    /**
     * A built registry lists its entries in hash order, so the bootstrap is run against a context that
     * only records what it is asked to register.
     */
    @SuppressWarnings("unchecked")
    private static void dumpBiomes(PrintWriter out) throws ReflectiveOperationException {
        HolderLookup.Provider registries = worldLookup();
        BootstrapContext<?> context = (BootstrapContext<?>) Proxy.newProxyInstance(
                DumpWorld.class.getClassLoader(), new Class<?>[] {BootstrapContext.class}, (proxy, method, args) -> {
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    if (method.getName().equals("lookup") || method.getName().equals("listContextElements")) {
                        Object lookup = registries.lookupOrThrow((ResourceKey<? extends Registry<?>>) args[0]);
                        return method.getName().equals("lookup") ? lookup : ((HolderLookup<?>) lookup).listElements();
                    }
                    if (method.getName().equals("register")) {
                        out.println("BIOME\t" + ((ResourceKey<?>) args[0]).identifier().getPath());
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        BiomeData.bootstrap((BootstrapContext<Biome>) context);
    }

    /** `createLookup` until 26.2, `createWorldLookup` since the reloadable registries were split off. */
    private static HolderLookup.Provider worldLookup() throws ReflectiveOperationException {
        for (String name : new String[] {"createWorldLookup", "createLookup"}) {
            try {
                return (HolderLookup.Provider) VanillaRegistries.class.getMethod(name).invoke(null);
            } catch (NoSuchMethodException absent) {
                // Try the other name.
            }
        }
        throw new NoSuchMethodException("VanillaRegistries has no world lookup");
    }
}
