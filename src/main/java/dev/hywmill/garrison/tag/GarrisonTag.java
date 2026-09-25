package dev.hywmill.garrison.tag;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Persistent metadata on every HywMill-managed HYW unit: which village roster slot, and which
 * spawn generation of it, the entity is. It is the only thing used to match an entity to its
 * roster entry; the roster itself stays authoritative.
 *
 * <p>PERSISTENT CONTRACT: the field names and the deterministic UUID derivation must never change.
 */
public record GarrisonTag(UUID villageId, UUID rosterId, int generation) {
    public static final Codec<GarrisonTag> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("village").forGetter(GarrisonTag::villageId),
            UUIDUtil.CODEC.fieldOf("roster").forGetter(GarrisonTag::rosterId),
            Codec.INT.fieldOf("generation").forGetter(GarrisonTag::generation)
    ).apply(i, GarrisonTag::new));

    private static final String UUID_NAMESPACE = "hywmill:garrison_unit:";

    /** Entity UUID for a roster slot's generation. Two spawns of the same slot generation share one UUID. */
    public static UUID entityUuid(UUID rosterId, int generation) {
        return UUID.nameUUIDFromBytes((UUID_NAMESPACE + rosterId + ":" + generation).getBytes(StandardCharsets.UTF_8));
    }

    public UUID expectedEntityUuid() {
        return entityUuid(rosterId, generation);
    }
}
