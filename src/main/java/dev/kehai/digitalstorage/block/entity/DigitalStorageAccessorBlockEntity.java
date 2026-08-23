package dev.kehai.digitalstorage.block.entity;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenHandler;
import dev.kehai.digitalstorage.security.DigitalStorageMountTracker;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.StorageVolume;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public final class DigitalStorageAccessorBlockEntity extends BlockEntity
        implements Storage<ItemVariant>, ExtendedScreenHandlerFactory {
    private static final String CONTROLLER_ID_KEY = "ControllerId";
    private static final String BOUND_VOLUME_ID_KEY = "BoundVolumeId";

    private UUID controllerId;
    private UUID boundVolumeId;

    public DigitalStorageAccessorBlockEntity(BlockPos pos, BlockState state) {
        super(DigitalStorageMod.DIGITAL_STORAGE_ACCESSOR_BLOCK_ENTITY, pos, state);
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
        if (!(world instanceof ServerWorld serverWorld) || boundVolumeId == null) {
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

    public DigitalItemStorage getCanonicalStorage() {
        DigitalStorageRecord record = getRecord();
        return record == null ? null : record.storage();
    }

    public BindResult bind(ServerPlayerEntity player, UUID requestedVolumeId) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return BindResult.UNAVAILABLE;
        }
        UUID playerId = player.getUuid();
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

    public BindResult clearBinding(ServerPlayerEntity player) {
        synchronized (this) {
            if (controllerId == null) {
                return BindResult.NOT_BOUND;
            }
            if (!controllerId.equals(player.getUuid())) {
                return BindResult.NOT_CONTROLLER;
            }
            controllerId = null;
            boundVolumeId = null;
            markDirtyAndSync();
        }
        if (world instanceof ServerWorld serverWorld) {
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
        if (world instanceof ServerWorld serverWorld) {
            DigitalStorageMountTracker.update(this, serverWorld);
        }
        return BindResult.SUCCESS;
    }

    @Override
    public Text getDisplayName() {
        return Text.translatable("block.digitalstorage.digital_storage_accessor");
    }

    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        return new DigitalStorageScreenHandler(syncId, playerInventory, this);
    }

    @Override
    public void writeScreenOpeningData(ServerPlayerEntity player, PacketByteBuf buf) {
        buf.writeBlockPos(pos);
        dev.kehai.digitalstorage.screen.DigitalStorageScreenState.capture(player, this, Text.empty()).write(buf);
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        DigitalItemStorage storage = getCanonicalStorage();
        return storage == null ? 0 : storage.insert(resource, maxAmount, transaction);
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        DigitalItemStorage storage = getCanonicalStorage();
        return storage == null ? 0 : storage.extract(resource, maxAmount, transaction);
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        DigitalItemStorage storage = getCanonicalStorage();
        return storage == null ? Collections.emptyIterator() : storage.iterator();
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        if (controllerId != null) {
            nbt.putUuid(CONTROLLER_ID_KEY, controllerId);
        }
        if (boundVolumeId != null) {
            nbt.putUuid(BOUND_VOLUME_ID_KEY, boundVolumeId);
        }
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        controllerId = nbt.containsUuid(CONTROLLER_ID_KEY) ? nbt.getUuid(CONTROLLER_ID_KEY) : null;
        boundVolumeId = nbt.containsUuid(BOUND_VOLUME_ID_KEY) ? nbt.getUuid(BOUND_VOLUME_ID_KEY) : null;
        if ((controllerId == null) != (boundVolumeId == null)) {
            controllerId = null;
            boundVolumeId = null;
        }
        if (world instanceof ServerWorld serverWorld) {
            DigitalStorageMountTracker.update(this, serverWorld);
        }
    }

    private void markDirtyAndSync() {
        markDirty();
        if (world != null) {
            world.updateListeners(pos, getCachedState(), getCachedState(), 3);
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
