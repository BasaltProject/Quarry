import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.minecraft.SharedConstants;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.TagLoader;

/**
 * Bootstraps the real registries once, binds the vanilla tags, and runs the dumps named on the command
 * line, each into its own `<name>.tsv` under the output directory.
 *
 * Output goes to files and not to stdout because the game installs its own logger over stdout, which
 * wraps every line in a timestamp.
 */
public final class Harness {
    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        if (args.length < 2) {
            System.err.println("usage: Harness <out-dir> <dump>...");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        bindVanillaTags();
        for (int i = 1; i < args.length; i++) {
            String name = args[i];
            try (PrintWriter writer = new PrintWriter(
                    Files.newBufferedWriter(out.resolve(name + ".tsv"), StandardCharsets.UTF_8))) {
                switch (name) {
                    case "blocks" -> DumpBlocks.dump(writer);
                    case "shapes" -> DumpShapes.dump(writer);
                    case "items" -> DumpItems.dump(writer);
                    case "block_entities" -> DumpBlockEntities.dump(writer);
                    case "entities" -> DumpEntities.dump(writer);
                    case "world" -> DumpWorld.dump(writer);
                    case "support" -> DumpBlockSupport.dump(writer);
                    case "map_colors" -> DumpMapColors.dump(writer);
                    case "block_sounds" -> DumpBlockSounds.dump(writer);
                    default -> throw new IllegalArgumentException("unknown dump " + name);
                }
            }
        }
    }

    /** Without the vanilla tags every `state.is(SOME_TAG)` is false and a sapling stands on nothing. */
    private static void bindVanillaTags() throws ReflectiveOperationException {
        // A pack of its own until 26.2; since, a holder whose `fullResources` is that pack.
        Object vanilla = ServerPacksSource.createVanillaPackSource();
        PackResources pack = vanilla instanceof PackResources resources
                ? resources
                : (PackResources) vanilla.getClass().getMethod("fullResources").invoke(vanilla);
        MultiPackResourceManager manager = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack));
        RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (Registry.PendingTags<?> pending : TagLoader.loadTagsForExistingRegistries(manager, registries)) {
            pending.apply();
        }
    }
}
