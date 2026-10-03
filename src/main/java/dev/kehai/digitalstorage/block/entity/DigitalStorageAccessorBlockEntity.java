package dev.kehai.digitalstorage.block.entity;

import dev.kehai.digitalstorage.DigitalStorageContent;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class DigitalStorageAccessorBlockEntity extends BlockEntity implements MenuProvider {
    private static final String CONTROLLER_ID_KEY = "ControllerId";
    private static final String BOUND_VOLUME_ID_KEY = "BoundVolumeId";

    private UUID controllerId;
    private UUID boundVolumeId;

    public DigitalStorageAccessorBlockEntity(BlockPos pos, BlockState state) {
        super(DigitalStorageContent.accessorType(), pos, state);
    }

    public Optional<UUID> controllerId() {
        return Optional.ofNullable(controllerId);
    }

    public Optional<UUID> boundVolumeId() {
        return Optional.ofNullable(boundVolumeId);
    }

    public boolean isBound() {
        return controllerId != null && boundVolumeId != null;
    }

    public DigitalStorageRecord getRecord() {
        StorageVolume volume = getVolume();
        return volume == null ? null : volume.record();
    }

    public StorageVolume getVolume() {
        if (!(level instanceof ServerLevel serverWorld) || boundVolumeId == null) {
            return null;
        }
        DigitalStorageState state = DigitalStorageState.get(serverWorld);
        UUID requestedVolumeId = boundVolumeId;
        StorageVolume volume = state.volume(requestedVolumeId).orElse(null);
        if (volume == null && clearOrphanedBinding(requestedVolumeId)) {
            DigitalStorageMountTracker.update(this, serverWorld);
        }
        return volume;
    }

    public boolean clearOrphanedBinding(DigitalStorageState state) {
        UUID requestedVolumeId = boundVolumeId;
        if (requestedVolumeId == null || state.volume(requestedVolumeId).isPresent()) {
            return false;
        }
        return clearOrphanedBinding(requestedVolumeId);
    }

    private synchronized boolean clearOrphanedBinding(UUID expectedVolumeId) {
        if (!expectedVolumeId.equals(boundVolumeId)) {
            return false;
        }
        controllerId = null;
        boundVolumeId = null;
        markDirtyAndSync();
        return true;
    }

    public BindResult bind(ServerPlayer player, UUID requestedVolumeId) {
        if (!(level instanceof ServerLevel serverWorld)) {
            return BindResult.UNAVAILABLE;
        }
        UUID playerId = player.getUUID();
        DigitalStorageState state = DigitalStorageState.get(serverWorld);
        if (!state.ownsVolume(playerId, requestedVolumeId)) {
            return BindResult.NOT_OWNER;
        }
        synchronized (this) {
            if (controllerId != null || boundVolumeId != null) {
                return BindResult.ALREADY_BOUND;
            }
            controllerId = playerId;
            boundVolumeId = requestedVolumeId;
            markDirtyAndSync();
        }
        DigitalStorageMountTracker.update(this, serverWorld);
        return BindResult.SUCCESS;
    }

    public BindResult clearBinding(ServerPlayer player) {
        synchronized (this) {
            if (controllerId == null) {
                return BindResult.NOT_BOUND;
            }
            if (!controllerId.equals(player.getUUID())) {
                return BindResult.NOT_CONTROLLER;
            }
            controllerId = null;
            boundVolumeId = null;
            markDirtyAndSync();
        }
        if (level instanceof ServerLevel serverWorld) {
            DigitalStorageMountTracker.update(this, serverWorld);
        }
        return BindResult.SUCCESS;
    }

    public BindResult forceClearBinding() {
        synchronized (this) {
            if (controllerId == null && boundVolumeId == null) {
                return BindResult.NOT_BOUND;
            }
            controllerId = null;
            boundVolumeId = null;
            markDirtyAndSync();
        }
        if (level instanceof ServerLevel serverWorld) {
            DigitalStorageMountTracker.update(this, serverWorld);
        }
        return BindResult.SUCCESS;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.digitalstorage.digital_storage_accessor");
    }

    @Override
    public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player player) {
        return new DigitalStorageScreenHandler(syncId, playerInventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        super.saveAdditional(nbt);
        if (controllerId != null) {
            nbt.putUUID(CONTROLLER_ID_KEY, controllerId);
        }
        if (boundVolumeId != null) {
            nbt.putUUID(BOUND_VOLUME_ID_KEY, boundVolumeId);
        }
    }

    @Override
    public void load(CompoundTag nbt) {
        super.load(nbt);
        controllerId = nbt.hasUUID(CONTROLLER_ID_KEY) ? nbt.getUUID(CONTROLLER_ID_KEY) : null;
        boundVolumeId = nbt.hasUUID(BOUND_VOLUME_ID_KEY) ? nbt.getUUID(BOUND_VOLUME_ID_KEY) : null;
        if ((controllerId == null) != (boundVolumeId == null)) {
            controllerId = null;
            boundVolumeId = null;
        }
        if (level instanceof ServerLevel serverWorld) {
            DigitalStorageMountTracker.update(this, serverWorld);
        }
    }

    private void markDirtyAndSync() {
        setChanged();
        if (level != null) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public enum BindResult {
        SUCCESS,
        NOT_OWNER,
        ALREADY_BOUND,
        NOT_CONTROLLER,
        NOT_BOUND,
        UNAVAILABLE
    }
}
