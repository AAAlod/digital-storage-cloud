package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.ItemKey;

/** Read-side inventory contract. Transfer guarantees are supplied separately by the platform. */
public interface InventoryEndpoint extends Iterable<InventoryEndpoint.View> {
    boolean supportsExtraction();

    interface View {
        boolean isBlank();
        ItemKey resource();
        long amount();
    }

    /** Versioned, weakly held endpoint; resolving a stale reference must return null. */
    interface Reference {
        InventoryEndpoint resolve();
    }
}
