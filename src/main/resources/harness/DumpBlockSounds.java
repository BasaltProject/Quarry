import java.io.PrintWriter;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;

/**
 * Each block's `SoundType`, which is code and not data: the volume and pitch, and the break, step,
 * place, hit and fall sounds. Read off the default state; only the decorated pot changes it by state.
 */
final class DumpBlockSounds {
    private DumpBlockSounds() {}

    static void dump(PrintWriter out) {
        for (Block block : BuiltInRegistries.BLOCK) {
            SoundType sound = block.defaultBlockState().getSoundType();
            out.println(BuiltInRegistries.BLOCK.getKey(block).getPath()
                    + "\t" + sound.getVolume()
                    + "\t" + sound.getPitch()
                    + "\t" + sound.getBreakSound().location().getPath()
                    + "\t" + sound.getStepSound().location().getPath()
                    + "\t" + sound.getPlaceSound().location().getPath()
                    + "\t" + sound.getHitSound().location().getPath()
                    + "\t" + sound.getFallSound().location().getPath());
        }
    }
}
