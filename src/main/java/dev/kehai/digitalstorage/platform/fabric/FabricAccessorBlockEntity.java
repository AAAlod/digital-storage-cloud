package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.block.entity.DigitalStorageAccessorBlockEntity;
import dev.kehai.digitalstorage.screen.DigitalStorageScreenState;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import java.util.Collections;
import java.util.Iterator;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** Fabric storage and menu-opening adapter; binding and permissions stay in the shared base. */
public final class FabricAccessorBlockEntity extends DigitalStorageAccessorBlockEntity
        implements Storage<ItemVariant>, ExtendedScreenHandlerFactory {
    private long lease;
    private GuardedStorage guarded;
    public FabricAccessorBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    public static FabricDigitalItemStorage canonicalStorage(DigitalStorageAccessorBlockEntity accessor) {
        if (accessor.isRemoved()) return null;
        var record = accessor.getRecord();
        return record == null ? null : FabricDigitalItemStorage.of(record.storage());
    }

    private void revoke() { lease++; guarded = null; }

    public Storage<ItemVariant> guardedStorage() {
        var volume = getVolume();
        if (isRemoved() || volume == null || boundVolumeId().filter(volume.id()::equals).isEmpty()) return null;
        if (guarded == null || !guarded.volumeId.equals(volume.id())) guarded = new GuardedStorage(volume.id(), lease);
        return guarded;
    }

    @Override public BindResult bind(ServerPlayer player, UUID id) {
        var result = super.bind(player, id); if (result == BindResult.SUCCESS) revoke(); return result;
    }
    @Override public BindResult clearBinding(ServerPlayer player) {
        var result = super.clearBinding(player); if (result == BindResult.SUCCESS) revoke(); return result;
    }
    @Override public BindResult forceClearBinding() {
        var result = super.forceClearBinding(); if (result == BindResult.SUCCESS) revoke(); return result;
    }
    @Override public boolean clearOrphanedBinding(dev.kehai.digitalstorage.storage.DigitalStorageState state) {
        boolean changed = super.clearOrphanedBinding(state); if (changed) revoke(); return changed;
    }
    @Override public dev.kehai.digitalstorage.storage.StorageVolume getVolume() {
        var before = boundVolumeId(); var volume = super.getVolume();
        if (!before.equals(boundVolumeId())) revoke();
        return volume;
    }
    @Override public void load(CompoundTag tag) { revoke(); super.load(tag); }
    @Override public void setRemoved() { revoke(); super.setRemoved(); }

    /** Fabric has no Forge invalidateCaps lifecycle; callers can explicitly revoke a cached lease. */
    public void invalidateCaps() { revoke(); }

    public final class GuardedStorage implements Storage<ItemVariant> {
        private final UUID volumeId;
        private final long capturedLease;
        private GuardedStorage(UUID volumeId, long capturedLease) { this.volumeId = volumeId; this.capturedLease = capturedLease; }
        public FabricDigitalItemStorage canonical() {
            if (capturedLease != lease || isRemoved() || boundVolumeId().filter(volumeId::equals).isEmpty()) return null;
            var volume = getVolume();
            if (capturedLease != lease || volume == null || !volumeId.equals(volume.id())) return null;
            return FabricDigitalItemStorage.of(volume.record().storage());
        }
        @Override public long insert(ItemVariant variant, long amount, TransactionContext tx) {
            var storage = canonical(); return storage == null ? 0 : storage.insert(variant, amount, tx);
        }
        @Override public long extract(ItemVariant variant, long amount, TransactionContext tx) {
            var storage = canonical(); return storage == null ? 0 : storage.extract(variant, amount, tx);
        }
        @Override public Iterator<StorageView<ItemVariant>> iterator() {
            var storage = canonical();
            if (storage == null) return Collections.emptyIterator();
            var iterator = storage.iterator();
            return new Iterator<>() {
                public boolean hasNext() { return canonical() != null && iterator.hasNext(); }
                public StorageView<ItemVariant> next() {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    var view = iterator.next();
                    return new StorageView<>() {
                        public long extract(ItemVariant variant, long amount, TransactionContext tx) { return canonical() == null ? 0 : view.extract(variant, amount, tx); }
                        public boolean isResourceBlank() { return canonical() == null || view.isResourceBlank(); }
                        public ItemVariant getResource() { return canonical() == null ? ItemVariant.blank() : view.getResource(); }
                        public long getAmount() { return canonical() == null ? 0 : view.getAmount(); }
                        public long getCapacity() { return canonical() == null ? 0 : view.getCapacity(); }
                    };
                }
            };
        }
    }

    /** Runs only from the isolated management world self-test. */
    public static void runLeaseWorldSelfTest(ServerPlayer player, FabricAccessorBlockEntity first) {
        var world = player.serverLevel(); var state = dev.kehai.digitalstorage.storage.DigitalStorageState.get(world);
        var secondPos = first.getBlockPos().west();
        if (!world.getBlockState(secondPos).isAir()) throw new IllegalStateException("Lease fixture requires an empty position");
        var a = state.createVolume(player.getUUID(), "Lease fixture A", Integer.MAX_VALUE).orElseThrow();
        var b = state.createVolume(player.getUUID(), "Lease fixture B", Integer.MAX_VALUE).orElseThrow();
        var key = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE);
        var stone = ItemVariant.of(net.minecraft.world.item.Items.STONE);
        FabricAccessorBlockEntity second = null;
        try {
            world.setBlockAndUpdate(secondPos, first.getBlockState());
            second = (FabricAccessorBlockEntity) world.getBlockEntity(secondPos);
            a.record().storage().load(key, 5); b.record().storage().load(key, 7);
            if (first.bind(player, a.id()) != BindResult.SUCCESS || second.bind(player, a.id()) != BindResult.SUCCESS)
                throw new IllegalStateException("Lease fixture binding failed");
            var old = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(world, first.getBlockPos(), null);
            var alias = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(world, secondPos, null);
            if (old == null || alias == null || dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkIntrospection.canonicalDigitalEndpoint(old) != canonicalStorage(first))
                throw new IllegalStateException("Provider did not return canonical-recognized guard");
            var view = old.iterator().next();
            var merged = new com.tom.storagemod.util.MergedStorage(); merged.add(old); merged.add(alias);
            long total = 0; for (var entry : merged) total += entry.getAmount();
            if (total != 5) throw new IllegalStateException("Guarded same-volume Tom endpoints were not deduplicated");
            if (first.clearBinding(player) != BindResult.SUCCESS) throw new IllegalStateException("Lease fixture unbind failed");
            assertRevoked(old, view, stone);
            if (dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkIntrospection.canonicalDigitalEndpoint(old) != null)
                throw new IllegalStateException("Revoked guard still exposes canonical endpoint");
            if (first.bind(player, b.id()) != BindResult.SUCCESS) throw new IllegalStateException("Lease fixture rebind failed");
            assertRevoked(old, view, stone);
            var current = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(world, first.getBlockPos(), null);
            try (var tx = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
                if (current == null || current.insert(stone, 1, tx) != 1) throw new IllegalStateException("New binding guard rejected insert");
                tx.commit();
            }
            if (a.record().storage().amountOf(key) != 5 || b.record().storage().amountOf(key) != 8)
                throw new IllegalStateException("New guard accessed wrong volume");
            var loadView = current.iterator().next(); first.load(first.saveWithoutMetadata()); assertRevoked(current, loadView, stone);
            var afterLoad = net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(world, first.getBlockPos(), null);
            if (afterLoad == null) throw new IllegalStateException("Load did not allow a fresh guard");
            var invalidatedView = afterLoad.iterator().next(); first.invalidateCaps(); assertRevoked(afterLoad, invalidatedView, stone);
            var removedView = alias.iterator().next(); second.setRemoved(); assertRevoked(alias, removedView, stone);
            dev.kehai.digitalstorage.DigitalStorage.LOGGER.info("Fabric accessor lease self-test passed: stale storage/views, rebind, removal and Tom volume dedup");
        } finally {
            if (first.isBound()) first.forceClearBinding();
            if (second != null && second.isBound()) second.forceClearBinding();
            world.removeBlock(secondPos, false);
            try (var tx = dev.kehai.digitalstorage.storage.LedgerTransaction.open()) {
                a.record().storage().extract(key, Long.MAX_VALUE, tx); b.record().storage().extract(key, Long.MAX_VALUE, tx); tx.commit();
            }
            if (!state.deleteEmptyVolume(player.getUUID(), a.id()) || !state.deleteEmptyVolume(player.getUUID(), b.id()))
                throw new IllegalStateException("Lease fixture cleanup failed");
        }
    }
    private static void assertRevoked(Storage<ItemVariant> storage, StorageView<ItemVariant> view, ItemVariant item) {
        try (var tx = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction.openOuter()) {
            if (storage.insert(item, 1, tx) != 0 || storage.extract(item, 1, tx) != 0 || storage.iterator().hasNext()
                    || view.extract(item, 1, tx) != 0 || !view.isResourceBlank() || !view.getResource().isBlank()
                    || view.getAmount() != 0 || view.getCapacity() != 0) throw new IllegalStateException("Revoked storage/view leaked access");
        }
    }

    @Override
    public void writeScreenOpeningData(ServerPlayer player, FriendlyByteBuf buf) {
        buf.writeBlockPos(getBlockPos());
        DigitalStorageScreenState.capture(player, this, Component.empty()).write(buf);
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        var storage = guardedStorage();
        return storage == null ? 0 : storage.insert(resource, maxAmount, transaction);
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        var storage = guardedStorage();
        return storage == null ? 0 : storage.extract(resource, maxAmount, transaction);
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        var storage = guardedStorage();
        return storage == null ? Collections.emptyIterator() : storage.iterator();
    }
}
