package dev.hywmill.garrison.tag;

import dev.hywmill.HywMill;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.function.Supplier;

/** NeoForge data attachment holding the {@link GarrisonTag} (serialized with the entity). */
public final class GarrisonAttachments {
    private static final DeferredRegister<AttachmentType<?>> REGISTER =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, HywMill.MODID);

    /** Default value is never stored: only {@link #set} creates the attachment. */
    private static final GarrisonTag NONE = new GarrisonTag(new java.util.UUID(0, 0), new java.util.UUID(0, 0), -1);

    public static final Supplier<AttachmentType<GarrisonTag>> TAG = REGISTER.register("garrison_unit",
            () -> AttachmentType.builder(() -> NONE).serialize(GarrisonTag.CODEC).build());

    private GarrisonAttachments() {}

    public static void register(IEventBus modBus) {
        REGISTER.register(modBus);
    }

    @Nullable
    public static GarrisonTag get(Entity entity) {
        Optional<GarrisonTag> t = entity.getExistingData(TAG);
        return t.filter(x -> x.generation() >= 0).orElse(null);
    }

    /** Removes the tag: the entity becomes an ordinary HYW unit (its owner is left untouched). */
    public static void clear(Entity entity) {
        if (entity.hasData(TAG)) {
            entity.removeData(TAG);
        }
    }

    public static void set(Entity entity, GarrisonTag tag) {
        entity.setData(TAG, tag);
    }
}
