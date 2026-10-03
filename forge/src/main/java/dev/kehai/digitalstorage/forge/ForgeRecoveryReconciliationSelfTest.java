package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.world.item.Items;

public final class ForgeRecoveryReconciliationSelfTest {
    private ForgeRecoveryReconciliationSelfTest() { }
    public static void run() {
        Path root;
        try { root = Files.createTempDirectory("digitalstorage-forge-reconciliation-").toAbsolutePath().normalize(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        try {
            UUID owner = UUID.randomUUID();
            UUID volume = UUID.randomUUID();
            UUID administrator = UUID.randomUUID();
            var key = ItemKey.of(Items.STONE);
            var store = new ForgeTransferRecovery(root.resolve("partial"));
            var held = store.hold(owner, volume, key, 64, "Owned fixture items");
            var attempt = store.beginDelivery(held.id(), owner, 16);
            var reopened = new ForgeTransferRecovery(root.resolve("partial"));
            var result = reopened.reconcile(held.id(), attempt.deliveryId(), administrator,
                    ForgeTransferRecovery.Outcome.CONFIRMED_DELIVERED, "External persisted delivery of 16 verified");
            expect(result.state() == ForgeTransferRecovery.State.HELD && result.amount() == 48
                    && result.reconciliations().size() == 1 && result.reconciliations().get(0).amount() == 16
                    && new ForgeTransferRecovery(root.resolve("partial")).entry(held.id()).equals(result),
                    "Partial reconciliation lost receipt or restored already-delivered ownership");
            boolean rejected = false;
            try { store.reconcile(held.id(), attempt.deliveryId(), administrator,
                    ForgeTransferRecovery.Outcome.CONFIRMED_DELIVERED, "stale request"); }
            catch (IllegalStateException expected) { rejected = true; }
            expect(rejected, "Old store repeated a completed reconciliation");
            var second = reopened.beginDelivery(held.id(), owner, 16);
            expect(!second.deliveryId().equals(attempt.deliveryId()), "Retry reused a delivery attempt identity");
            rejected = false;
            try { reopened.reconcile(held.id(), attempt.deliveryId(), administrator,
                    ForgeTransferRecovery.Outcome.CONFIRMED_NOT_DELIVERED, "old attempt against new delivery"); }
            catch (IllegalStateException expected) { rejected = true; }
            expect(rejected && reopened.entry(held.id()).state() == ForgeTransferRecovery.State.DELIVERING,
                    "Old receipt resolved another delivery attempt");
            rejected = false;
            try { reopened.reconcile(held.id(), second.deliveryId(), administrator,
                    ForgeTransferRecovery.Outcome.CONFIRMED_NOT_DELIVERED, " "); }
            catch (IllegalArgumentException expected) { rejected = true; }
            expect(rejected && reopened.entry(held.id()).state() == ForgeTransferRecovery.State.DELIVERING,
                    "Reconciliation accepted no external evidence explanation");
            result = reopened.reconcile(held.id(), second.deliveryId(), administrator,
                    ForgeTransferRecovery.Outcome.CONFIRMED_NOT_DELIVERED, "Target inspected; second intent never committed");
            expect(result.state() == ForgeTransferRecovery.State.HELD && result.amount() == 48
                    && result.reconciliations().size() == 2, "Not-delivered decision consumed held ownership");
            var third = reopened.beginDelivery(held.id(), owner, 16);
            rejected = false;
            try { reopened.finishDelivery(held.id(), owner, second.deliveryId()); }
            catch (IllegalStateException expected) { rejected = true; }
            expect(rejected, "Stale normal completion settled a later delivery");
            result = reopened.finishDelivery(held.id(), owner, third.deliveryId());
            expect(result.amount() == 32 && result.reconciliations().size() == 2, "Normal delivery erased reconciliation history");

            var actualStore = new ForgeTransferRecovery(root.resolve("actual"));
            var actual = actualStore.hold(owner, volume, key, 8, "Actual owned fixture before target flush failure");
            var target = new VolumeLedger(volume, () -> { }, 32);
            var uncertain = ForgeRecoveryDelivery.deliver(actualStore, actual, owner, 8, target,
                    () -> { throw new IllegalStateException("Injected target flush failure"); });
            expect(uncertain.state() == ForgeRecoveryDelivery.State.UNCERTAIN && target.amountOf(key) == 8,
                    "Actual target fixture did not reach uncertain committed delivery");
            result = actualStore.reconcile(actual.id(), actualStore.entry(actual.id()).deliveryId(), administrator,
                    ForgeTransferRecovery.Outcome.CONFIRMED_DELIVERED, "External target persistence independently verified");
            expect(result.state() == ForgeTransferRecovery.State.DELIVERED && actualStore.pendingCount() == 0
                    && target.amountOf(key) == 8 && result.reconciliations().get(0).administrator().equals(administrator),
                    "Reconciliation changed physical target quantity or lost administrator receipt");

            var faulted = new ForgeTransferRecovery(root.resolve("fault"));
            var fault = faulted.hold(owner, volume, key, 5, "Owned fixture before reconciliation write failure");
            var pending = faulted.beginDelivery(fault.id(), owner, 5);
            Path temporary = root.resolve("fault").resolve(fault.id() + ".tmp");
            Files.createDirectory(temporary);
            rejected = false;
            try { faulted.reconcile(fault.id(), pending.deliveryId(), administrator,
                    ForgeTransferRecovery.Outcome.CONFIRMED_NOT_DELIVERED, "Intent never reached external target"); }
            catch (IllegalStateException expected) { rejected = true; }
            expect(rejected && faulted.unsavedCount() == 1 && !faulted.available()
                    && !new ForgeTransferRecovery(root.resolve("fault")).available(),
                    "Failed receipt write allowed delivery or ignored interrupted temporary");
            Files.delete(temporary);
            faulted.flushUnsaved();
            result = new ForgeTransferRecovery(root.resolve("fault")).entry(fault.id());
            expect(result.state() == ForgeTransferRecovery.State.HELD && result.amount() == 5
                    && result.reconciliations().size() == 1 && result.reconciliations().get(0).deliveryId().equals(pending.deliveryId()),
                    "Receipt and decision state did not persist together after filesystem repair");

            var legacy = ForgeTransferRecovery.encode(pending);
            legacy.putInt("SchemaVersion", 1);
            legacy.remove("DeliveryId");
            legacy.remove("Reconciliations");
            var old = ForgeTransferRecovery.read(legacy);
            expect(old.deliveryId() != null && old.deliveryId().equals(ForgeTransferRecovery.read(legacy).deliveryId())
                    && old.reconciliations().isEmpty() && old.state() == ForgeTransferRecovery.State.DELIVERING,
                    "Legacy intent became replayable or changed its derived handling marker");
            var malformed = ForgeTransferRecovery.encode(pending);
            malformed.remove("DeliveryId");
            rejected = false;
            try { ForgeTransferRecovery.read(malformed); }
            catch (IllegalArgumentException expected) { rejected = true; }
            expect(rejected, "Current format accepted an uncertain intent without its attempt identity");
        } catch (IOException failure) { throw new IllegalStateException("Reconciliation filesystem fixture failed", failure); }
        finally {
            if (!root.getFileName().toString().startsWith("digitalstorage-forge-reconciliation-")) throw new IllegalStateException("Unexpected reconciliation cleanup root");
            try (var files = Files.walk(root)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
            catch (IOException failure) { throw new IllegalStateException("Could not clean reconciliation fixture", failure); }
        }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
