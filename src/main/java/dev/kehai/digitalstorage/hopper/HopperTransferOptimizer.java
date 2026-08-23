package dev.kehai.digitalstorage.hopper;

import dev.kehai.digitalstorage.DigitalStorageMod;
import dev.kehai.digitalstorage.config.DigitalStorageConfig;
import dev.kehai.digitalstorage.storage.DigitalItemStorage;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.block.entity.BlockEntity;

public final class HopperTransferOptimizer {
    private static final Map<Storage<ItemVariant>, WeakReference<StorageView<ItemVariant>>> FILTER_CURSORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private HopperTransferOptimizer() {
    }

    /** Resolve the device tier from its cached state; this performs no registry or world lookup. */
    public static long batchLimit(BlockEntity hopper) {
        DigitalStorageConfig config = DigitalStorageConfig.get();
        return hopper.getCachedState().isOf(DigitalStorageMod.ADVANCED_INVENTORY_HOPPER)
                ? config.advancedHopperBatchSize
                : config.normalHopperBatchSize;
    }

    public static long moveFiltered(
            Storage<ItemVariant> source,
            Storage<ItemVariant> destination,
            Predicate<ItemVariant> predicate,
            long maximum,
            TransactionContext transaction
    ) {
        if (source == null || destination == null || source == destination || maximum <= 0) {
            return 0;
        }

        WeakReference<StorageView<ItemVariant>> cursorReference = FILTER_CURSORS.get(source);
        StorageView<ItemVariant> cursor = cursorReference == null ? null : cursorReference.get();
        if (cursor != null) {
            long moved = moveView(cursor, destination, predicate, maximum, transaction);
            if (moved > 0) {
                return moved;
            }
            FILTER_CURSORS.remove(source);
        }

        Iterator<StorageView<ItemVariant>> iterator = source.iterator();
        while (iterator.hasNext()) {
            StorageView<ItemVariant> view = iterator.next();
            long moved = moveView(view, destination, predicate, maximum, transaction);
            if (moved > 0) {
                FILTER_CURSORS.put(source, new WeakReference<>(view));
                return moved;
            }
        }
        return 0;
    }

    public static void invalidateFilteredCursor(Storage<ItemVariant> source) {
        FILTER_CURSORS.remove(source);
    }

    private static long moveView(
            StorageView<ItemVariant> view,
            Storage<ItemVariant> destination,
            Predicate<ItemVariant> predicate,
            long maximum,
            TransactionContext transaction
    ) {
        if (view.isResourceBlank() || view.getAmount() <= 0) {
            return 0;
        }
        ItemVariant resource = view.getResource();
        if (!predicate.test(resource)) {
            return 0;
        }
        long accepted = StorageUtil.simulateInsert(destination, resource, maximum, transaction);
        if (accepted <= 0) {
            return 0;
        }
        try (Transaction nested = transaction.openNested()) {
            long extracted = view.extract(resource, accepted, nested);
            if (extracted <= 0) {
                return 0;
            }
            long inserted = destination.insert(resource, extracted, nested);
            if (inserted != extracted) {
                return 0;
            }
            nested.commit();
            return inserted;
        }
    }

    public static long moveExact(
            Storage<ItemVariant> source,
            Storage<ItemVariant> destination,
            ItemVariant resource,
            long maximum,
            TransactionContext transaction
    ) {
        if (source == null || destination == null || resource.isBlank()) {
            return 0;
        }
        if (source == destination) {
            return 0;
        }

        if (destination instanceof DigitalItemStorage) {
            return moveIntoDigital(source, destination, resource, maximum, transaction);
        }
        if (source instanceof DigitalItemStorage) {
            return moveOutOfDigital(source, destination, resource, maximum, transaction);
        }

        // Ask the destination first. A full machine therefore causes no scan
        // or extraction attempt against the Tom's network.
        long accepted = StorageUtil.simulateInsert(destination, resource, maximum, transaction);
        if (accepted <= 0) {
            return 0;
        }

        try (Transaction nested = transaction.openNested()) {
            // Storage.extract(resource, ...) is the fast path: MergedStorage checks
            // each backing storage directly instead of enumerating every view.
            long extracted = source.extract(resource, accepted, nested);
            if (extracted <= 0) {
                return 0;
            }

            long inserted = destination.insert(resource, extracted, nested);
            if (inserted != extracted) {
                return 0;
            }

            nested.commit();
            return inserted;
        }
    }

    private static long moveIntoDigital(
            Storage<ItemVariant> source,
            Storage<ItemVariant> destination,
            ItemVariant resource,
            long maximum,
            TransactionContext transaction
    ) {
        try (Transaction nested = transaction.openNested()) {
            long inserted = destination.insert(resource, maximum, nested);
            if (inserted <= 0) {
                return 0;
            }
            long extracted = source.extract(resource, inserted, nested);
            if (extracted != inserted) {
                return 0;
            }
            nested.commit();
            return inserted;
        }
    }

    private static long moveOutOfDigital(
            Storage<ItemVariant> source,
            Storage<ItemVariant> destination,
            ItemVariant resource,
            long maximum,
            TransactionContext transaction
    ) {
        try (Transaction nested = transaction.openNested()) {
            long extracted = source.extract(resource, maximum, nested);
            if (extracted <= 0) {
                return 0;
            }
            long inserted = destination.insert(resource, extracted, nested);
            if (inserted != extracted) {
                return 0;
            }
            nested.commit();
            return inserted;
        }
    }

}
