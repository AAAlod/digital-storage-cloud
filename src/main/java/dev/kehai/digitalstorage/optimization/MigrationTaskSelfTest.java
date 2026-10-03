package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.item.Items;

/** Shared scheduler tests use ordinary inventory views; Fabric integration is verified separately. */
public final class MigrationTaskSelfTest {
    private MigrationTaskSelfTest() {
    }

    public static void run() {
        ItemKey stone = ItemKey.of(Items.STONE);
        ItemKey dirt = ItemKey.of(Items.DIRT);
        var token = new TestToken();
        List<InventoryEndpoint.View> views = new ArrayList<>();
        for (int i = 0; i < 300; i++) views.add(new TestView(ItemKey.blank(), 0));
        TestView first = new TestView(stone, 64);
        TestView second = new TestView(stone, 32);
        TestView ignored = new TestView(dirt, 12);
        views.set(0, first);
        views.set(299, second);
        views.set(150, ignored);
        InventoryEndpoint source = endpoint(true, views);
        int[] resolves = {0};
        InventoryEndpoint.Reference reference = () -> { resolves[0]++; return source; };
        var record = DigitalStorageRecord.createNew(() -> { });
        var task = new MigrationTask(List.of(stone), 2, token, List.of(reference), settledExecutor());
        MigrationTask.State state = MigrationTask.State.RUNNING;
        int ticks = 0;
        while (state == MigrationTask.State.RUNNING && ticks++ < 10) {
            long before = task.scannedViews();
            state = task.tick(record, 128);
            if (task.scannedViews() - before > 128) throw new IllegalStateException("Shared migration exceeded scan budget");
        }
        MigrationTask.Status status = task.status(25, state, task.stopDetail());
        if (state != MigrationTask.State.COMPLETE || ticks != 3 || task.scannedViews() != 300
                || task.movedItems() != 96 || first.amount != 0 || second.amount != 0 || ignored.amount != 12
                || record.storage().amountOf(stone) != 96 || resolves[0] != 1
                || status.inventoryOperations() != 4 || status.completedCandidates() != 1
                || status.active() || !status.movedItems().equals("96")) {
            throw new IllegalStateException("Shared migration budget/selection/progress/handle retention regression failed");
        }

        var stale = new MigrationTask(List.of(stone), 1, token, List.of(reference),
                (view, target, resource, maximum) -> { throw new IllegalStateException("Stale task attempted transfer"); });
        token.current = false;
        if (stale.tick(record, 128) != MigrationTask.State.STOPPED || !stale.stopDetail().equals("test topology changed")
                || stale.scannedViews() != 0) throw new IllegalStateException("Shared migration ignored topology invalidation");
        token.current = true;
        var unloaded = new MigrationTask(List.of(stone), 1, token, List.of(() -> null), settledExecutor());
        if (unloaded.tick(record, 128) != MigrationTask.State.STOPPED || !unloaded.stopDetail().equals("inventory unloaded")) {
            throw new IllegalStateException("Shared migration ignored missing source");
        }
        var missingTarget = new MigrationTask(List.of(stone), 1, token, List.of(reference), settledExecutor());
        if (missingTarget.tick(null, 128) != MigrationTask.State.STOPPED) {
            throw new IllegalStateException("Shared migration ignored missing target");
        }
        var locked = new MigrationTask(List.of(stone), 1, token,
                List.of(() -> endpoint(false, List.of(new TestView(stone, 5)))), settledExecutor());
        if (locked.tick(record, 128) != MigrationTask.State.COMPLETE || locked.scannedViews() != 0) {
            throw new IllegalStateException("Shared migration scanned non-extractable endpoint");
        }
        TestView rejectedView = new TestView(stone, 7);
        var rejected = new MigrationTask(List.of(stone), 1, token,
                List.of(() -> endpoint(true, List.of(rejectedView))),
                (view, target, resource, maximum) -> new InventoryTransferExecutor.MoveResult(0, 2));
        if (rejected.tick(record, 128) != MigrationTask.State.STOPPED || rejected.movedItems() != 0
                || rejectedView.amount != 7 || !rejected.stopDetail().equals("target full or inventory changed")) {
            throw new IllegalStateException("Shared migration treated rejected transfer as complete");
        }
        var forbidden = new MigrationTask(List.of(ItemKey.of(Items.DIAMOND_SWORD)), 1, token,
                List.of(() -> endpoint(true, List.of(new TestView(ItemKey.of(Items.DIAMOND_SWORD), 1)))),
                (view, target, resource, maximum) -> { throw new IllegalStateException("Policy-rejected item reached transfer"); });
        if (forbidden.tick(record, 128) != MigrationTask.State.STOPPED || forbidden.movedItems() != 0) {
            throw new IllegalStateException("Shared migration ignored target policy");
        }
    }

    private static InventoryTransferExecutor settledExecutor() {
        return (view, target, resource, maximum) -> {
            TestView source = (TestView) view;
            long requested = Math.min(source.amount, maximum);
            try (LedgerTransaction transaction = LedgerTransaction.open()) {
                long inserted = target.insert(resource, requested, transaction);
                if (inserted != requested) return new InventoryTransferExecutor.MoveResult(0, 2);
                transaction.commit();
                source.amount -= inserted;
                return new InventoryTransferExecutor.MoveResult(inserted, 2);
            }
        };
    }

    private static InventoryEndpoint endpoint(boolean extractable, List<InventoryEndpoint.View> views) {
        return new InventoryEndpoint() {
            @Override public boolean supportsExtraction() { return extractable; }
            @Override public Iterator<InventoryEndpoint.View> iterator() { return views.iterator(); }
        };
    }

    private static final class TestView implements InventoryEndpoint.View {
        private final ItemKey resource;
        private long amount;
        private TestView(ItemKey resource, long amount) { this.resource = resource; this.amount = amount; }
        @Override public boolean isBlank() { return resource.isBlank(); }
        @Override public ItemKey resource() { return resource; }
        @Override public long amount() { return amount; }
    }

    private static final class TestToken implements TopologyToken {
        private boolean current = true;
        @Override public boolean isCurrent() { return current; }
        @Override public String staleDetail() { return "test topology changed"; }
    }
}
