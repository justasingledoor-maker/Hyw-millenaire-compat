package dev.hywmill.military.doctrine;

import javax.annotation.Nullable;

/**
 * Relative adjustment applied after the culture/type layers for a military tier.
 * {@code reserveSet}, when present, forces the reserve (NONE tier: 0) instead of adding to it.
 */
public record TierModifier(int commitDelta, int reserveDelta, int radiusDelta, @Nullable Integer reserveSet) {
    public static final TierModifier NONE = new TierModifier(0, 0, 0, null);

    public boolean isIdentity() {
        return commitDelta == 0 && reserveDelta == 0 && radiusDelta == 0 && reserveSet == null;
    }
}
