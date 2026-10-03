package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.optimization.InventoryEndpoint;
import dev.kehai.digitalstorage.optimization.InventoryTransferExecutor;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;

/** Both sides participate in the same Fabric transaction; partial insertion rolls back extraction. */
public final class FabricTransferExecutor implements InventoryTransferExecutor {
    public static final FabricTransferExecutor INSTANCE = new FabricTransferExecutor();

    private FabricTransferExecutor() {
    }

    @Override
    public MoveResult move(InventoryEndpoint.View source, VolumeLedger target, ItemKey resource, long maximum) {
        if (!(source instanceof FabricInventoryEndpoint.View view)) {
            throw new IllegalArgumentException("Expected a Fabric inventory view");
        }
        return moveView(view.storageView(), FabricDigitalItemStorage.of(target), FabricItemKeys.toVariant(resource), maximum);
    }

    public static MoveResult moveView(StorageView<ItemVariant> view, Storage<ItemVariant> target,
                                      ItemVariant resource, long maximum) {
        if (view.isResourceBlank() || !resource.equals(view.getResource()) || view.getAmount() <= 0) {
            return new MoveResult(0, 0);
        }
        long requested = Math.min(maximum, view.getAmount());
        try (Transaction transaction = Transaction.openOuter()) {
            long extracted = view.extract(resource, requested, transaction);
            if (extracted <= 0) return new MoveResult(0, 1);
            long inserted = target.insert(resource, extracted, transaction);
            if (inserted != extracted) return new MoveResult(0, 2);
            transaction.commit();
            return new MoveResult(inserted, 2);
        }
    }
}
