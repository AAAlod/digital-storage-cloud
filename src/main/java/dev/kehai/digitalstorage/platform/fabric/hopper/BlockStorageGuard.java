package dev.kehai.digitalstorage.platform.fabric.hopper;

import com.tom.storagemod.util.IProxy;
import java.util.Collections;
import java.util.Iterator;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Guards block-provided storages without block entities while Tom caches them. */
public final class BlockStorageGuard implements Storage<ItemVariant> {
    private static final Map<Level, Map<BlockPos, ArrayList<GuardReference>>> WATCHES = new WeakHashMap<>();
    private static final ReferenceQueue<BlockStorageGuard> COLLECTED = new ReferenceQueue<>();
    private final Level world;
    private final BlockPos pos;
    private final Block block;
    private final Storage<ItemVariant> delegate;
    private boolean invalid;

    private BlockStorageGuard(Level world, BlockPos pos, Block block, Storage<ItemVariant> delegate) {
        this.world = world;
        this.pos = pos.immutable();
        this.block = block;
        this.delegate = delegate;
    }

    public static Storage<ItemVariant> wrap(Level world, BlockPos pos, BlockEntity entity, Storage<ItemVariant> storage) {
        if (storage == null || entity != null || storage instanceof IProxy) return storage;
        clearCollected();
        var positions = WATCHES.computeIfAbsent(world, ignored -> new HashMap<>());
        var guards = positions.computeIfAbsent(pos.immutable(), ignored -> new ArrayList<>());
        guards.removeIf(reference -> reference.get() == null);
        Block block = world.getBlockState(pos).getBlock();
        for (var reference : guards) {
            BlockStorageGuard existing = reference.get();
            if (existing != null && !existing.invalid && existing.block == block && existing.delegate == storage) return existing;
        }
        BlockStorageGuard guard = new BlockStorageGuard(world, pos, block, storage);
        guards.add(new GuardReference(guard));
        return guard;
    }

    /** Invalidate guards when the actual block type changes, including between ticks. */
    public static void blockChanging(Level world, BlockPos pos, Block nextBlock) {
        clearCollected();
        var positions = WATCHES.get(world);
        if (positions == null) return;
        var guards = positions.get(pos);
        if (guards == null) return;
        guards.removeIf(reference -> {
            BlockStorageGuard guard = reference.get();
            if (guard == null) return true;
            if (guard.block != nextBlock) { guard.invalidate(); return true; }
            return false;
        });
        if (guards.isEmpty()) positions.remove(pos);
        if (positions.isEmpty()) WATCHES.remove(world);
    }

    private static void clearCollected() {
        GuardReference reference;
        while ((reference = (GuardReference) COLLECTED.poll()) != null) {
            Level world = reference.world.get();
            var positions = world == null ? null : WATCHES.get(world);
            if (positions == null) continue;
            var guards = positions.get(reference.pos);
            if (guards != null) {
                guards.remove(reference);
                if (guards.isEmpty()) positions.remove(reference.pos);
            }
            if (positions.isEmpty()) WATCHES.remove(world);
        }
    }

    private static final class GuardReference extends WeakReference<BlockStorageGuard> {
        final WeakReference<Level> world;
        final BlockPos pos;
        GuardReference(BlockStorageGuard guard) {
            super(guard, COLLECTED);
            world = new WeakReference<>(guard.world);
            pos = guard.pos;
        }
    }

    private boolean valid() {
        if (!invalid && (!world.hasChunkAt(pos) || world.getBlockState(pos).getBlock() != block)) invalidate();
        return !invalid;
    }

    private void invalidate() {
        invalid = true;
        dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkCache.guardInvalidated(this);
    }

    public boolean allowsDirectBatchTransfer() {
        return dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkIntrospection.isDirectGuardDelegateAllowed(delegate);
    }

    public boolean allowsFilteredBatchTransfer() {
        return dev.kehai.digitalstorage.platform.fabric.tom.TomNetworkIntrospection.isFilteredGuardDelegateAllowed(delegate);
    }

    @Override public boolean supportsInsertion() { return valid() && delegate.supportsInsertion(); }
    @Override public boolean supportsExtraction() { return valid() && delegate.supportsExtraction(); }
    @Override public long insert(ItemVariant item, long amount, TransactionContext tx) {
        return valid() ? delegate.insert(item, amount, tx) : 0;
    }
    @Override public long extract(ItemVariant item, long amount, TransactionContext tx) {
        return valid() ? delegate.extract(item, amount, tx) : 0;
    }
    @Override public Iterator<StorageView<ItemVariant>> iterator() {
        if (!valid()) return Collections.emptyIterator();
        Iterator<StorageView<ItemVariant>> iterator = delegate.iterator();
        return new Iterator<>() {
            @Override public boolean hasNext() { return valid() && iterator.hasNext(); }
            @Override public StorageView<ItemVariant> next() { return new GuardedView(iterator.next()); }
        };
    }

    private final class GuardedView implements StorageView<ItemVariant> {
        private final StorageView<ItemVariant> view;
        private GuardedView(StorageView<ItemVariant> view) { this.view = view; }
        @Override public long extract(ItemVariant item, long amount, TransactionContext tx) {
            return valid() ? view.extract(item, amount, tx) : 0;
        }
        @Override public boolean isResourceBlank() { return !valid() || view.isResourceBlank(); }
        @Override public ItemVariant getResource() { return valid() ? view.getResource() : ItemVariant.blank(); }
        @Override public long getAmount() { return valid() ? view.getAmount() : 0; }
        @Override public long getCapacity() { return valid() ? view.getCapacity() : 0; }
    }

    @Override public boolean equals(Object other) {
        return other instanceof BlockStorageGuard guard && world == guard.world && pos.equals(guard.pos)
                && block == guard.block && delegate.equals(guard.delegate);
    }
    @Override public int hashCode() { return delegate.hashCode(); }
}
