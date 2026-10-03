package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/** Shared binding/menu entity with Forge lifecycle callbacks. Capability is wired separately. */
public final class ForgeAccessorBlockEntity extends DigitalStorageAccessorBlockEntity {
    public ForgeAccessorBlockEntity(BlockPos pos, BlockState state) { super(pos, state); }

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
