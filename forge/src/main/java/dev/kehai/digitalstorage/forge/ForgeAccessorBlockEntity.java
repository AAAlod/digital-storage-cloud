package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import java.util.UUID;

/** Shared binding/menu entity with Forge lifecycle and revocable item capability. */
public final class ForgeAccessorBlockEntity extends DigitalStorageAccessorBlockEntity {
    private LazyOptional<IItemHandler> itemCapability;
    private Lease lease;
    private UUID capabilityVolumeId;
    public ForgeAccessorBlockEntity(BlockPos pos, BlockState state) { super(pos, state); }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, Direction side) {
        if (capability != ForgeCapabilities.ITEM_HANDLER) return super.getCapability(capability, side);
        var volume = isRemoved() ? null : getVolume();
        if (volume == null) {
            invalidateItemCapability();
            return LazyOptional.empty();
        }
        if (itemCapability == null) {
            var ledger = volume.record().storage();
            Lease createdLease = new Lease();
            lease = createdLease;
            capabilityVolumeId = boundVolumeId().orElseThrow();
            var handler = ForgeDigitalItemStorage.of(ledger).guarded(() -> {
                if (!createdLease.valid || isRemoved()) return false;
                // Keep the owner alive as well as its ledger: the state caches
                // clean volumes weakly and must not reload a second live copy.
                return getVolume() == volume;
            });
            itemCapability = LazyOptional.of(() -> handler);
        }
        return itemCapability.cast();
    }

    private void invalidateItemCapability() {
        if (lease != null) lease.valid = false;
        var previous = itemCapability;
        itemCapability = null;
        lease = null;
        capabilityVolumeId = null;
        if (previous != null) previous.invalidate();
    }

    @Override
    public void setChanged() {
        if (capabilityVolumeId != null && !boundVolumeId().filter(capabilityVolumeId::equals).isPresent()) {
            invalidateItemCapability();
        }
        super.setChanged();
    }

    @Override public void load(CompoundTag tag) { invalidateItemCapability(); super.load(tag); }
    @Override public void invalidateCaps() { invalidateItemCapability(); super.invalidateCaps(); }
    @Override public void reviveCaps() { invalidateItemCapability(); super.reviveCaps(); }

    private static final class Lease { private boolean valid = true; }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) DigitalStorageMountTracker.onBlockEntityLoad(this, serverLevel);
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) DigitalStorageMountTracker.onBlockEntityUnload(this, serverLevel);
        super.setRemoved();
    }
}
