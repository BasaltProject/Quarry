import java.io.PrintWriter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStackTemplate;

/**
 * What items keep in code: the crafting remainder (`REMAINDER <item> <left>`) and the block a block
 * item places (`BLOCK <item> <block>`).
 */
final class DumpItems {
    private DumpItems() {}

    static void dump(PrintWriter out) {
        for (Item item : BuiltInRegistries.ITEM) {
            String id = BuiltInRegistries.ITEM.getKey(item).getPath();
            ItemStackTemplate remainder = item.getCraftingRemainder();
            if (remainder != null) {
                String left = BuiltInRegistries.ITEM.getKey(remainder.item().value()).getPath();
                out.println("REMAINDER\t" + id + "\t" + left);
            }
            if (item instanceof BlockItem blockItem) {
                out.println("BLOCK\t" + id + "\t" + BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).getPath());
            }
        }
    }
}
