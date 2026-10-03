package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/** Incremental migration policy. Platform managers own player/world lifetime and transfer execution. */
public final class MigrationTask {
    private final List<ItemKey> candidates;
    private final Set<ItemKey> selected;
    private final int estimatedFreedViews;
    private final TopologyToken topology;
    // Retain the resolved wrappers while active, including across equivalent topology rebuilds.
    private final List<InventoryEndpoint> sourceHandles;
    private final InventoryTransferExecutor executor;
    private final Set<ItemKey> migrated = new HashSet<>();
    private Iterator<InventoryEndpoint.View> currentViews = Collections.emptyIterator();
    private InventoryEndpoint.View pendingView;
    private int sourceIndex;
    private long movedItems;
    private long scannedViews;
    private long inventoryOperations;
    private boolean blocked;
    private String stopDetail = "";

    public MigrationTask(List<ItemKey> candidates, int estimatedFreedViews, TopologyToken topology,
                         List<? extends InventoryEndpoint.Reference> sources, InventoryTransferExecutor executor) {
        this.candidates = List.copyOf(candidates);
        this.selected = Set.copyOf(candidates);
        this.estimatedFreedViews = estimatedFreedViews;
        this.topology = topology;
        this.sourceHandles = sources.stream().map(InventoryEndpoint.Reference::resolve).toList();
        this.executor = java.util.Objects.requireNonNull(executor);
    }

    public State tick(DigitalStorageRecord record, int viewBudget) {
        if (record == null) {
            stopDetail = "target unavailable";
            return State.STOPPED;
        }
        if (!TopologyToken.isCurrent(topology)) {
            stopDetail = topology == null ? "network changed" : topology.staleDetail();
            return State.STOPPED;
        }
        VolumeLedger target = record.storage();
        int scannedThisTick = 0;
        while (scannedThisTick < viewBudget) {
            while (pendingView == null && !currentViews.hasNext()) {
                if (sourceIndex >= sourceHandles.size()) {
                    if (blocked) stopDetail = "target full or inventory changed";
                    return blocked ? State.STOPPED : State.COMPLETE;
                }
                InventoryEndpoint source = sourceHandles.get(sourceIndex++);
                if (source == null) {
                    stopDetail = "inventory unloaded";
                    return State.STOPPED;
                }
                currentViews = source.supportsExtraction() ? source.iterator() : Collections.emptyIterator();
            }
            InventoryEndpoint.View view = pendingView == null ? currentViews.next() : pendingView;
            pendingView = null;
            scannedThisTick++;
            scannedViews++;
            if (view.isBlank() || view.amount() <= 0) continue;
            ItemKey resource = view.resource();
            if (!selected.contains(resource)) continue;
            boolean exists = target.amountOf(resource) > 0;
            if (!record.canInsert(resource) || (!exists && target.variantCount() >= record.variantCapacity())) {
                blocked = true;
                continue;
            }
            long available = VolumeLedger.MAX_AMOUNT_PER_VARIANT - target.amountOf(resource);
            if (available <= 0) {
                blocked = true;
                continue;
            }
            InventoryTransferExecutor.MoveResult move = executor.move(view, target, resource, available);
            inventoryOperations += move.operations();
            if (move.moved() > 0) {
                movedItems = Math.addExact(movedItems, move.moved());
                migrated.add(resource);
            } else if (move.operations() > 0) {
                blocked = true;
            }
            if (!move.stopDetail().isEmpty()) {
                stopDetail = move.stopDetail();
                return State.STOPPED;
            }
            // Forge extraction is stack-sized even when one physical slot
            // reports a larger count. Revisit it within the same view budget,
            // rather than treating a partial batch as a completed source slot.
            if (move.revisitSource()) pendingView = view;
        }
        return State.RUNNING;
    }

    public long scannedViews() { return scannedViews; }
    public long movedItems() { return movedItems; }
    public String stopDetail() { return stopDetail; }

    public Status status(long tick, State state, String detail) {
        return new Status(state, Long.toString(movedItems), state == State.COMPLETE ? candidates.size() : migrated.size(),
                candidates.size(), estimatedFreedViews, scannedViews, inventoryOperations, tick, detail);
    }

    public enum State { IDLE, RUNNING, COMPLETE, CANCELLED, STOPPED }

    public record Status(State state, String movedItems, int completedCandidates, int totalCandidates,
                         int estimatedFreedViews, long scannedViews, long inventoryOperations,
                         long updatedTick, String detail) {
        public static Status idle() { return new Status(State.IDLE, "0", 0, 0, 0, 0, 0, 0, ""); }
        public boolean active() { return state == State.RUNNING; }
    }
}
