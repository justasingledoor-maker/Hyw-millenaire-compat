package dev.hywmill.recruit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.UUID;

/** The Muster Roll's state: the village it is bound to, the player who placed it, and the spawn radius that player set. */
public final class MusterRollBlockEntity extends BlockEntity {
    @Nullable private UUID village;
    @Nullable private UUID owner;
    private int radius = 8;

    public MusterRollBlockEntity(BlockPos pos, BlockState state) {
        super(MusterRoll.BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    public UUID village() {
        return village;
    }

    public void bind(@Nullable UUID village) {
        this.village = village;
        setChanged();
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    public void setOwner(@Nullable UUID owner) {
        this.owner = owner;
        setChanged();
    }

    public int radius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = radius;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (village != null) {
            tag.putUUID("village", village);
        }
        if (owner != null) {
            tag.putUUID("owner", owner);
        }
        tag.putInt("radius", radius);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        village = tag.hasUUID("village") ? tag.getUUID("village") : null;
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        radius = tag.contains("radius") ? tag.getInt("radius") : 8;
    }
}
