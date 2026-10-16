import java.io.PrintWriter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

/**
 * What each block keeps in `BlockBehaviour.Properties`, which is code and not data: destroy time,
 * whether drops need the right tool, explosion resistance, friction, speed and jump factors, bounce
 * and the note block instrument it gives.
 */
final class DumpBlocks {
    private DumpBlocks() {}

    static void dump(PrintWriter out) {
        for (Block block : BuiltInRegistries.BLOCK) {
            out.println(BuiltInRegistries.BLOCK.getKey(block).getPath()
                    + "\t" + block.defaultDestroyTime()
                    + "\t" + block.defaultBlockState().requiresCorrectToolForDrops()
                    + "\t" + block.getExplosionResistance()
                    + "\t" + block.getFriction()
                    + "\t" + block.getSpeedFactor()
                    + "\t" + block.getJumpFactor()
                    + "\t" + block.getBounceRestitution()
                    + "\t" + block.defaultBlockState().instrument().getSerializedName());
        }
    }
}
