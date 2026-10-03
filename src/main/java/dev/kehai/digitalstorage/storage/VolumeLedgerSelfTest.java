package dev.kehai.digitalstorage.storage;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.item.Items;

public final class VolumeLedgerSelfTest {
    private VolumeLedgerSelfTest() {
    }

    public static void run() {
        nestedTransactions();
        randomizedConservation();
        closedScopeCannotLeaveProvisionalEntries();
        transactionOrderIsEnforced();
    }

    private static void nestedTransactions() {
        AtomicInteger dirty = new AtomicInteger();
        VolumeLedger ledger = new VolumeLedger(dirty::incrementAndGet, 2);
        ItemKey stone = ItemKey.of(Items.STONE);
        ItemKey dirt = ItemKey.of(Items.DIRT);
        ledger.load(stone, 10);
        try (LedgerTransaction outer = LedgerTransaction.open()) {
            expect(ledger.insert(stone, 5, outer) == 5, "Outer insert failed");
            try (LedgerTransaction nested = outer.openNested()) {
                ledger.extract(stone, 15, nested);
                ledger.insert(dirt, 7, nested);
                nested.commit();
            }
            expect(ledger.amountOf(dirt) == 7 && ledger.contentVersion() == 0 && dirty.get() == 0,
                    "Nested commit sent final notifications early");
        }
        expect(ledger.amountOf(stone) == 10 && ledger.amountOf(dirt) == 0
                        && ledger.variantCount() == 1 && ledger.totalItemCount() == 10 && dirty.get() == 0,
                "Outer abort did not restore a nested committed replacement");
        try (LedgerTransaction outer = LedgerTransaction.open()) {
            ledger.insert(stone, 5, outer);
            try (LedgerTransaction nested = outer.openNested()) {
                ledger.insert(dirt, 7, nested);
            }
            expect(ledger.amountOf(stone) == 15 && ledger.amountOf(dirt) == 0,
                    "Nested abort damaged its parent's mutation");
            outer.commit();
        }
        expect(ledger.amountOf(stone) == 15 && ledger.contentVersion() == 1 && dirty.get() == 1,
                "Final commit notification or quantity was incorrect");
    }

    private static void randomizedConservation() {
        VolumeLedger ledger = new VolumeLedger(() -> { }, 3);
        ItemKey[] keys = {ItemKey.of(Items.STONE), ItemKey.of(Items.DIRT), ItemKey.of(Items.PAPER)};
        Map<ItemKey, Long> expected = new HashMap<>();
        Random random = new Random(0x1ED6E4L);
        for (int index = 0; index < 3000; index++) {
            ItemKey key = keys[random.nextInt(keys.length)];
            long before = expected.getOrDefault(key, 0L);
            long requested = 1 + random.nextInt(100);
            boolean insert = random.nextBoolean();
            boolean outerCommit = random.nextBoolean();
            boolean nestedCommit = random.nextBoolean();
            long changed;
            try (LedgerTransaction outer = LedgerTransaction.open()) {
                try (LedgerTransaction nested = outer.openNested()) {
                    changed = insert ? ledger.insert(key, requested, nested) : ledger.extract(key, requested, nested);
                    if (nestedCommit) {
                        nested.commit();
                    }
                }
                if (outerCommit) {
                    outer.commit();
                }
            }
            if (outerCommit && nestedCommit) {
                expected.put(key, before + (insert ? changed : -changed));
            }
            long total = 0;
            int variants = 0;
            for (ItemKey checked : keys) {
                long amount = expected.getOrDefault(checked, 0L);
                expect(ledger.amountOf(checked) == amount, "Local nested transaction lost items");
                total += amount;
                variants += amount > 0 ? 1 : 0;
            }
            expect(ledger.totalItemCount() == total && ledger.variantCount() == variants,
                    "Local nested transaction lost cached metrics");
        }
    }

    private static void closedScopeCannotLeaveProvisionalEntries() {
        VolumeLedger ledger = new VolumeLedger(() -> { }, 1);
        LedgerTransaction closed = LedgerTransaction.open();
        closed.close();
        try {
            ledger.insert(ItemKey.of(Items.DIRT), 1, closed);
            throw new IllegalStateException("A closed mutation scope was accepted");
        } catch (IllegalStateException expected) {
            expect(ledger.variantCount() == 0 && ledger.totalItemCount() == 0 && !ledger.iterator().hasNext(),
                    "Rejected mutation changed ledger metrics");
            expect(ledger.snapshotCursor().advance(Integer.MAX_VALUE).examinedEntries() == 0,
                    "Rejected mutation stranded an invisible provisional entry");
            // A valid subsequent insert must still have capacity and be able to commit.
            try (LedgerTransaction transaction = LedgerTransaction.open()) {
                expect(ledger.insert(ItemKey.of(Items.STONE), 1, transaction) == 1,
                        "Rejected mutation stranded a provisional entry");
                transaction.commit();
            }
        }
    }

    private static void transactionOrderIsEnforced() {
        try (LedgerTransaction outer = LedgerTransaction.open()) {
            boolean rejectedOuter = false;
            try {
                LedgerTransaction.open();
            } catch (IllegalStateException expected) {
                rejectedOuter = true;
            }
            expect(rejectedOuter, "Overlapping local outer transactions were accepted");
            try (LedgerTransaction child = outer.openNested()) {
                boolean rejectedParent = false;
                try {
                    outer.commit();
                } catch (IllegalStateException expected) {
                    rejectedParent = true;
                }
                expect(rejectedParent, "Parent committed while its child was open");
                child.commit();
            }
            outer.commit();
        }
        // Closing the prior chain must release the current transaction completely.
        try (LedgerTransaction next = LedgerTransaction.open()) {
            next.commit();
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
