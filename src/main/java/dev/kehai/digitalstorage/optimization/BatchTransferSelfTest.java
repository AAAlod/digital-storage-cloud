package dev.kehai.digitalstorage.optimization;

import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;

public final class BatchTransferSelfTest {
    private BatchTransferSelfTest() { }
    public static void run() {
        recommendedPreviewBound();
        var record = DigitalStorageRecord.createNew(() -> { });
        var firstTag = new CompoundTag(); firstTag.putInt("Damage", 12);
        var secondTag = new CompoundTag(); secondTag.putInt("Damage", 30);
        var first = ItemKey.of(Items.DIAMOND_SWORD, firstTag);
        var second = ItemKey.of(Items.DIAMOND_SWORD, secondTag);
        record.storage().load(first, 5); record.storage().load(second, 3);
        check(!record.acceptsUnstackableItems(), "Fixture must reject new tools");
        try (var tx = LedgerTransaction.open()) {
            var withdrawal = record.storage().reserveExtraction(first, 5, tx);
            withdrawal.settle(2);
            check(record.storage().amountOf(first) == 3, "Rejected remainder was stranded by insertion policy");
            boolean duplicate = false;
            try { withdrawal.settle(2); } catch (IllegalStateException expected) { duplicate = true; }
            check(duplicate, "Withdrawal receipt accepted duplicate settlement");
            tx.commit();
        }
        check(record.storage().amountOf(first) == 3 && record.storage().amountOf(second) == 3
                && record.storage().totalItemCount() == 6 && record.storage().variantCount() == 2, "Withdrawal metrics/identity failed");
        try (var tx = LedgerTransaction.open()) {
            record.storage().reserveExtraction(first, 3, tx).settle(1);
        }
        check(record.storage().amountOf(first) == 3, "Withdrawal rollback lost items");
        int[] steps = {0};
        long[] delivered = {0};
        var port = new BatchTransfer.Port() {
            public Iterable<BatchTransfer.Entry> contents() { return List.of(); }
            public BatchTransfer.Cursor cursor(boolean exporting) {
                return new BatchTransfer.Cursor() {
                    int slot;
                    public boolean hasNext() { return slot < 8; }
                    public BatchTransfer.Result next(DigitalStorageRecord source, ItemKey key, long max) {
                        steps[0]++; slot++;
                        if (slot <= 3) return BatchTransfer.Result.empty();
                        try (var tx = LedgerTransaction.open()) {
                            long moved = source.storage().extract(key, Math.min(max, 1), tx);
                            tx.commit(); delivered[0] += moved;
                            return new BatchTransfer.Result(moved, "");
                        }
                    }
                };
            }
        };
        boolean[] valid = {true};
        var route = new BatchTransfer.Route(List.of(port), () -> valid[0], "fixture");
        check(BatchTransfer.preview(record, route, true, true).size() == 2, "NBT identities were merged in preview");
        var task = new BatchTransfer(record, route, List.of(new BatchTransfer.Entry(first, 2)), true);
        while (task.active()) { int previous = steps[0]; task.tick(2); check(steps[0] - previous <= 2, "Batch exceeded slot budget"); }
        check(task.state().equals("COMPLETE") && delivered[0] == 2 && record.storage().amountOf(first) == 1
                && record.storage().amountOf(second) == 3, "Batch exceeded selected amount/identity");
        var stale = new BatchTransfer(record, route, List.of(new BatchTransfer.Entry(second, 2)), true);
        valid[0] = false; stale.tick(10);
        check(stale.state().equals("STOPPED") && stale.moved() == 0, "Stale network moved items");
        var cancelled = new BatchTransfer(record, route, List.of(new BatchTransfer.Entry(second, 2)), true);
        cancelled.cancel(); cancelled.tick(100);
        check(cancelled.moved() == 0, "Cancelled task continued");
        var injected = DigitalStorageRecord.createNew(() -> { });
        var stone = ItemKey.of(Items.STONE); injected.storage().load(stone, 2);
        boolean[] stillCurrent = {true}; int[] calls = {0}; long[] received = {0};
        var changingPort = new BatchTransfer.Port() {
            public Iterable<BatchTransfer.Entry> contents() { return List.of(); }
            public BatchTransfer.Cursor cursor(boolean exporting) { return new BatchTransfer.Cursor() {
                public boolean hasNext() { return true; }
                public BatchTransfer.Result next(DigitalStorageRecord source, ItemKey key, long max) {
                    calls[0]++;
                    try (var tx = LedgerTransaction.open()) {
                        long count = source.storage().extract(key, 1, tx); tx.commit(); received[0] += count;
                        stillCurrent[0] = false; return new BatchTransfer.Result(count, "");
                    }
                }
            }; }
        };
        var changing = new BatchTransfer(injected, new BatchTransfer.Route(List.of(changingPort), () -> true,
                () -> stillCurrent[0], "fault-injection"), List.of(new BatchTransfer.Entry(stone, 2)), true);
        changing.tick(10);
        check(changing.state().equals("STOPPED") && changing.detail().equals("network_changed") && calls[0] == 1
                && received[0] == 1 && injected.storage().amountOf(stone) == 1, "Mid-tick invalidation failed conservation");
    }
    private static void recommendedPreviewBound() {
        var entries = new java.util.ArrayList<BatchTransfer.Entry>();
        var keys = new java.util.HashSet<ItemKey>();
        for (int i = 0; i < 4097; i++) {
            var tag = new CompoundTag(); tag.putInt("preview-fixture", i);
            var key = ItemKey.of(Items.STONE, tag); entries.add(new BatchTransfer.Entry(key, 1));
            if (i < 20) keys.add(key);
        }
        var port = new BatchTransfer.Port() {
            public Iterable<BatchTransfer.Entry> contents() { return entries; }
            public BatchTransfer.Cursor cursor(boolean exporting) { return new BatchTransfer.Cursor() {
                public boolean hasNext() { return false; }
                public BatchTransfer.Result next(DigitalStorageRecord record, ItemKey key, long maximum) { return BatchTransfer.Result.empty(); }
            }; }
        };
        var record = DigitalStorageRecord.createNew(() -> { });
        var route = new BatchTransfer.Route(List.of(port), () -> true, "fixture");
        var recommended = BatchTransfer.preview(record, route, false, false, keys);
        check(recommended.size() == 20 && new BatchTransfer(record, route, recommended, false).active(), "recommended subset blocked by unrelated variants");
        boolean rejected = false;
        try { BatchTransfer.preview(record, route, false, false); } catch (IllegalStateException e) { rejected = "too_many_items".equals(e.getMessage()); }
        check(rejected, "manual preview did not retain variant limit");
    }
    private static void check(boolean value, String detail) { if (!value) throw new IllegalStateException(detail); }
}
