package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class FabricLedgerSelfTest {
    private FabricLedgerSelfTest() {
    }

    public static void run() {
        repeatedCanonicalLookupsShareNestedSnapshots();
        vanillaAndLedgerCommitMatrix();
        exceptionRollsBackBothInventories();
        detachedEntriesReleaseAdapterCaches();
        sharedMutationsInvalidateViewCache();
    }

    private static void repeatedCanonicalLookupsShareNestedSnapshots() {
        AtomicInteger dirty = new AtomicInteger();
        VolumeLedger ledger = new VolumeLedger(dirty::incrementAndGet, 2);
        ItemVariant stone = ItemVariant.of(Items.STONE);
        FabricDigitalItemStorage canonical = FabricDigitalItemStorage.of(ledger);
        try (Transaction outer = Transaction.openOuter()) {
            try (Transaction inner = outer.openNested()) {
                FabricDigitalItemStorage.of(ledger).insert(stone, 12, inner);
                inner.commit();
            }
            expect(FabricDigitalItemStorage.of(ledger) == canonical && dirty.get() == 0 && ledger.contentVersion() == 0,
                    "Canonical lookup or nested notifications diverged");
            FabricDigitalItemStorage.of(ledger).insert(stone, 3, outer);
        }
        expect(ledger.totalItemCount() == 0 && dirty.get() == 0 && ledger.contentVersion() == 0,
                "Repeated canonical lookup lost the outer rollback snapshot");
        try (Transaction outer = Transaction.openOuter()) {
            try (Transaction aborted = outer.openNested()) {
                canonical.insert(stone, 5, aborted);
                canonical.iterator().next();
            }
            try (Transaction committed = outer.openNested()) {
                FabricDigitalItemStorage.of(ledger).insert(stone, 7, committed);
                committed.commit();
            }
            canonical.insert(stone, 2, outer);
            outer.commit();
        }
        expect(ledger.amountOf(ItemKey.of(Items.STONE)) == 9 && dirty.get() == 1 && ledger.contentVersion() == 1,
                "Detached bridge reuse changed a subsequent nested commit");
    }

    private static void vanillaAndLedgerCommitMatrix() {
        ItemVariant stone = ItemVariant.of(Items.STONE);
        for (boolean innerCommit : new boolean[]{false, true}) {
            for (boolean outerCommit : new boolean[]{false, true}) {
                SimpleContainer physical = new SimpleContainer(new ItemStack(Items.STONE, 64));
                var source = InventoryStorage.of(physical, null);
                AtomicInteger dirty = new AtomicInteger();
                VolumeLedger ledger = new VolumeLedger(dirty::incrementAndGet, 1);
                FabricDigitalItemStorage target = FabricDigitalItemStorage.of(ledger);
                try (Transaction outer = Transaction.openOuter()) {
                    try (Transaction inner = outer.openNested()) {
                        expect(source.extract(stone, 16, inner) == 16 && target.insert(stone, 16, inner) == 16,
                                "Cross-inventory nested transfer failed");
                        if (innerCommit) {
                            inner.commit();
                        }
                    }
                    expect(dirty.get() == 0 && ledger.contentVersion() == 0,
                            "Cross-inventory nested commit notified before final commit");
                    if (outerCommit) {
                        outer.commit();
                    }
                }
                boolean committed = innerCommit && outerCommit;
                expect(physical.getItem(0).getCount() == (committed ? 48 : 64)
                                && ledger.totalItemCount() == (committed ? 16 : 0)
                                && dirty.get() == (committed ? 1 : 0),
                        "Vanilla and ledger did not commit or roll back together");
            }
        }
    }

    private static void exceptionRollsBackBothInventories() {
        SimpleContainer physical = new SimpleContainer(new ItemStack(Items.STONE, 64));
        var source = InventoryStorage.of(physical, null);
        VolumeLedger ledger = new VolumeLedger(() -> { }, 1);
        var target = FabricDigitalItemStorage.of(ledger);
        ItemVariant stone = ItemVariant.of(Items.STONE);
        RuntimeException sentinel = new RuntimeException("Expected transaction interruption");
        try {
            try (Transaction outer = Transaction.openOuter()) {
                source.extract(stone, 8, outer);
                target.insert(stone, 8, outer);
                throw sentinel;
            }
        } catch (RuntimeException expected) {
            if (expected != sentinel) {
                throw expected;
            }
        }
        expect(physical.getItem(0).getCount() == 64 && ledger.totalItemCount() == 0 && ledger.contentVersion() == 0,
                "Exception left a partially completed physical-to-ledger transfer");
    }

    private static void detachedEntriesReleaseAdapterCaches() {
        FabricDigitalItemStorage storage = new FabricDigitalItemStorage(() -> { }, 1);
        for (int index = 0; index < 1000; index++) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Variant", index);
            ItemVariant variant = ItemVariant.of(Items.PAPER, tag);
            try (Transaction transaction = Transaction.openOuter()) {
                storage.insert(variant, 1, transaction);
                storage.iterator().next();
                if ((index & 1) == 0) {
                    storage.extract(variant, 1, transaction);
                    transaction.commit();
                }
            }
        }
        expect(storage.variantCount() == 0 && cacheSize(storage, "bridges") <= 1 && cacheSize(storage, "views") == 0,
                "Detached ledger entries accumulated in adapter caches");
    }

    private static void sharedMutationsInvalidateViewCache() {
        VolumeLedger ledger = new VolumeLedger(() -> { }, 1);
        ledger.load(ItemKey.of(Items.STONE), 2);
        FabricDigitalItemStorage storage = FabricDigitalItemStorage.of(ledger);
        var original = storage.iterator().next();
        expect(storage.iterator().next() == original, "Unchanged ledger did not reuse its Fabric view");
        try (LedgerTransaction transaction = LedgerTransaction.open()) {
            ledger.extract(ItemKey.of(Items.STONE), 2, transaction);
            expect(!storage.iterator().hasNext() && cacheSize(storage, "views") == 1,
                    "Shared provisional zero amount discarded its still-attached view");
        }
        var restored = storage.iterator().next();
        expect(restored == original && restored.getAmount() == 2 && storage.iterator().next() == restored,
                "Shared rollback did not restore a reusable live view");
        try (LedgerTransaction transaction = LedgerTransaction.open()) {
            ledger.extract(ItemKey.of(Items.STONE), 2, transaction);
            transaction.commit();
        }
        expect(!storage.iterator().hasNext() && cacheSize(storage, "views") == 0,
                "Shared committed removal retained a detached Fabric view");
        try (LedgerTransaction transaction = LedgerTransaction.open()) {
            ledger.insert(ItemKey.of(Items.PAPER), 1, transaction);
            storage.iterator().next();
        }
        expect(!storage.iterator().hasNext() && cacheSize(storage, "views") == 0,
                "Shared aborted insertion retained a detached Fabric view");
        ledger.load(ItemKey.of(Items.DIRT), 1);
        expect(storage.iterator().next().getResource().equals(ItemVariant.of(Items.DIRT)),
                "Shared replacement reused the removed entry's view");
    }

    private static int cacheSize(FabricDigitalItemStorage storage, String name) {
        try {
            var field = FabricDigitalItemStorage.class.getDeclaredField(name);
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(storage)).size();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not inspect adapter cache regression", exception);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
