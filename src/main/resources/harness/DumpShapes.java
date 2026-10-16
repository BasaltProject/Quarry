import java.io.PrintWriter;
import java.lang.reflect.Method;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Every block state's collision boxes, light dampening, flags and sturdy faces, one line per state:
 * `<state id> <light> <blocks motion> <replaceable> <solid> <solid render> <sturdy> <centre> <rigid>
 * <full occlusion faces> <boxes>`, faces in `Direction.values()` order.
 *
 * `blocksMotion` left 26.2 for the `minecraft:blocks_motion` block tag; where the method is gone the
 * flag is printed as `?` and the driver answers it from the tag.
 */
final class DumpShapes {
    private DumpShapes() {}

    static void dump(PrintWriter out) throws ReflectiveOperationException {
        Method blocksMotion = null;
        try {
            blocksMotion = BlockState.class.getMethod("blocksMotion");
        } catch (NoSuchMethodException gone) {
            // Answered from the tag by the driver.
        }
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                VoxelShape collision;
                try {
                    collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
                            CollisionContext.empty());
                } catch (Throwable t) {
                    collision = Shapes.block();
                }
                StringBuilder boxes = new StringBuilder();
                for (AABB box : collision.toAabbs()) {
                    if (boxes.length() > 0) boxes.append(';');
                    boxes.append(box.minX).append(',').append(box.minY).append(',').append(box.minZ).append(',')
                            .append(box.maxX).append(',').append(box.maxY).append(',').append(box.maxZ);
                }
                StringBuilder sturdy = new StringBuilder();
                StringBuilder centre = new StringBuilder();
                StringBuilder rigid = new StringBuilder();
                StringBuilder occlusionFull = new StringBuilder();
                for (Direction direction : Direction.values()) {
                    sturdy.append(supports(state, direction, SupportType.FULL) ? '1' : '0');
                    centre.append(supports(state, direction, SupportType.CENTER) ? '1' : '0');
                    rigid.append(supports(state, direction, SupportType.RIGID) ? '1' : '0');
                    occlusionFull.append(Block.isShapeFullBlock(state.getFaceOcclusionShape(direction)) ? '1' : '0');
                }
                out.append(String.valueOf(Block.getId(state))).append('\t')
                        .append(String.valueOf(state.getLightDampening())).append('\t')
                        .append(blocksMotion == null ? '?' : (Boolean) blocksMotion.invoke(state) ? '1' : '0')
                        .append('\t')
                        .append(state.canBeReplaced() ? '1' : '0').append('\t')
                        .append(state.isSolid() ? '1' : '0').append('\t')
                        .append(state.isSolidRender() ? '1' : '0').append('\t')
                        .append(sturdy).append('\t')
                        .append(centre).append('\t')
                        .append(rigid).append('\t')
                        .append(occlusionFull).append('\t')
                        .append(boxes).append('\n');
            }
        }
    }

    /** A face that cannot be asked supports nothing, which is what an empty shape answers anyway. */
    private static boolean supports(BlockState state, Direction direction, SupportType support) {
        try {
            return state.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction, support);
        } catch (Throwable t) {
            return false;
        }
    }
}
