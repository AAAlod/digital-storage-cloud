package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.optimization.InventoryEndpoint;
import dev.kehai.digitalstorage.storage.ItemKey;
import java.util.Iterator;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;

/** Keeps filters, direction and leave-one rules on the original Fabric inventory wrapper. */
public final class FabricInventoryEndpoint implements InventoryEndpoint {
    private final Storage<ItemVariant> storage;

    public FabricInventoryEndpoint(Storage<ItemVariant> storage) {
        this.storage = storage;
    }

    public Storage<ItemVariant> storage() { return storage; }

    @Override
    public boolean supportsExtraction() {
        return storage.supportsExtraction();
    }

    @Override
    public Iterator<InventoryEndpoint.View> iterator() {
        Iterator<StorageView<ItemVariant>> iterator = storage.iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() { return iterator.hasNext(); }

            @Override
            public InventoryEndpoint.View next() { return new dev.kehai.digitalstorage.platform.fabric.FabricInventoryEndpoint.View(iterator.next()); }
        };
    }

    public static final class View implements InventoryEndpoint.View {
        private final StorageView<ItemVariant> view;

        public View(StorageView<ItemVariant> view) {
            this.view = view;
        }

        public StorageView<ItemVariant> storageView() { return view; }

        @Override
        public boolean isBlank() { return view.isResourceBlank(); }
        @Override
        public ItemKey resource() { return FabricItemKeys.fromVariant(view.getResource()); }
        @Override
        public long amount() { return view.getAmount(); }
    }
}
