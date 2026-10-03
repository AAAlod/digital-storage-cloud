package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;

/** Platform transfer boundary. A result counts only settled items with a known owner. */
@FunctionalInterface
public interface InventoryTransferExecutor {
    MoveResult move(InventoryEndpoint.View source, VolumeLedger target, ItemKey resource, long maximum);

    record MoveResult(long moved, int operations, String stopDetail, boolean revisitSource) {
        public MoveResult(long moved, int operations) { this(moved, operations, "", false); }
        public MoveResult(long moved, int operations, String stopDetail) { this(moved, operations, stopDetail, false); }
        public MoveResult {
            if (moved < 0 || operations < 0) throw new IllegalArgumentException("Negative transfer settlement count");
            java.util.Objects.requireNonNull(stopDetail);
            if (revisitSource && (moved == 0 || !stopDetail.isEmpty())) {
                throw new IllegalArgumentException("Repeated source needs progress and an active transfer");
            }
        }
    }
}
