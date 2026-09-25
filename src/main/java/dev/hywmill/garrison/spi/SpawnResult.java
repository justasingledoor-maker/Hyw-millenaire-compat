package dev.hywmill.garrison.spi;

import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/** Outcome of {@link UnitProvider#spawn}: the added entity, or a failure reason. */
public record SpawnResult(@Nullable Entity entity, int appliedLevel, String failure) {
    public static SpawnResult failed(String why) {
        return new SpawnResult(null, -1, why);
    }

    public boolean ok() {
        return entity != null;
    }
}
