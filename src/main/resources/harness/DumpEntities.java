import java.io.PrintWriter;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

/**
 * What `EntityType.Builder` and each class's `createAttributes` build in code: the box, spawn category
 * and tracking of every type (`TYPE`), and every living type's attribute base values (`ATTRIBUTE`).
 */
final class DumpEntities {
    private DumpEntities() {}

    @SuppressWarnings("unchecked")
    static void dump(PrintWriter out) {
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
            out.println("TYPE\t" + id
                    + "\t" + type.getWidth()
                    + "\t" + type.getHeight()
                    + "\t" + type.getCategory().getName()
                    + "\t" + type.clientTrackingRange()
                    + "\t" + type.updateInterval()
                    + "\t" + type.fireImmune()
                    + "\t" + type.canSpawnFarFromPlayer());
            if (!DefaultAttributes.hasSupplier(type)) continue;
            AttributeSupplier supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);
            for (Holder.Reference<Attribute> attribute : BuiltInRegistries.ATTRIBUTE.listElements().toList()) {
                if (!supplier.hasAttribute(attribute)) continue;
                out.println("ATTRIBUTE\t" + id + "\t" + attribute.key().identifier().getPath()
                        + "\t" + supplier.getBaseValue(attribute));
            }
        }
    }
}
