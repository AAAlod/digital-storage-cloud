package dev.kehai.digitalstorage.platform.fabric;

import dev.kehai.digitalstorage.platform.fabric.FabricDigitalItemStorage;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;

public final class FabricLedgerSelfTest {
    private FabricLedgerSelfTest() {
    }

    public static void run() {
        repeatedCanonicalLookupsShareNestedSnapshots();
        vanillaAndLedgerCommitMatrix();
        exceptionRollsBackBothInventories();
        detachedEntriesReleaseAdapterCaches();
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
                SimpleInventory physical = new SimpleInventory(new ItemStack(Items.STONE, 64));
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
                expect(physical.getStack(0).getCount() == (committed ? 48 : 64)
                                && ledger.totalItemCount() == (committed ? 16 : 0)
                                && dirty.get() == (committed ? 1 : 0),
                        "Vanilla and ledger did not commit or roll back together");
            }
        }
    }

    private static void exceptionRollsBackBothInventories() {
        SimpleInventory physical = new SimpleInventory(new ItemStack(Items.STONE, 64));
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
        expect(physical.getStack(0).getCount() == 64 && ledger.totalItemCount() == 0 && ledger.contentVersion() == 0,
                "Exception left a partially completed physical-to-ledger transfer");
    }

    private static void detachedEntriesReleaseAdapterCaches() {
        FabricDigitalItemStorage storage = new FabricDigitalItemStorage(() -> { }, 1);
        for (int index = 0; index < 1000; index++) {
            NbtCompound tag = new NbtCompound();
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
