import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Every block entity type in registry order, which is its protocol id, and the blocks that own one. */
final class DumpBlockEntities {
    private DumpBlockEntities() {}

    static void dump(PrintWriter out) {
        for (BlockEntityType<?> type : BuiltInRegistries.BLOCK_ENTITY_TYPE) {
            List<String> blocks = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                if (type.isValid(block.defaultBlockState())) {
                    blocks.add(BuiltInRegistries.BLOCK.getKey(block).getPath());
                }
            }
            out.println(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).getPath() + "\t" + String.join(",", blocks));
        }
    }
}
