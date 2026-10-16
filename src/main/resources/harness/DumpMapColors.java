import java.io.PrintWriter;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * What a map draws for each block state, which `BlockBehaviour.Properties.mapColor` keeps in code, and
 * the colour behind each of the 64 map colour ids. Rows `state <id> <colour id>` then `colour <id> <rgb>`.
 */
final class DumpMapColors {
    private DumpMapColors() {}

    static void dump(PrintWriter out) {
        for (int id = 0; id < Block.BLOCK_STATE_REGISTRY.size(); id++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
            out.println("state\t" + id + "\t" + state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).id);
        }
        for (int id = 0; id < 64; id++) {
            out.println("colour\t" + id + "\t" + MapColor.byId(id).col);
        }
    }
}
