package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;

/** Platform transfer boundary. A result counts only settled items with a known owner. */
@FunctionalInterface
public interface InventoryTransferExecutor {
    MoveResult move(InventoryEndpoint.View source, VolumeLedger target, ItemKey resource, long maximum);

    record MoveResult(long moved, int operations) {
    }
}
