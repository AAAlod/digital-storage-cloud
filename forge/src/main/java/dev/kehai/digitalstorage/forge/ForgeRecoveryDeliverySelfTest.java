package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import dev.kehai.digitalstorage.storage.StorageVolume;
import dev.kehai.digitalstorage.storage.VolumeLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;

public final class ForgeRecoveryDeliverySelfTest {
    private ForgeRecoveryDeliverySelfTest() { }
    public static void run(MinecraftServer server) {
        var source = server.createCommandSourceStack().withPermission(0);
        var rootCommand = server.getCommands().getDispatcher().getRoot().getChild("digitalstorage");
        expect(rootCommand.canUse(source) && rootCommand.getChild("recovery").canUse(source)
                && !rootCommand.getChild("selftest").canUse(source) && !rootCommand.getChild("diagnostics").canUse(source)
                && !rootCommand.getChild("flush").canUse(source) && !rootCommand.getChild("transferincident").canUse(source)
                && !rootCommand.getChild("recoveryadmin").canUse(source),
                "Recovery command tree leaked administrator permissions or blocked ordinary players");
        Path root;
        try { root = Files.createTempDirectory("digitalstorage-forge-delivery-").toAbsolutePath().normalize(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        var state = DigitalStorageState.get(server.overworld());
        int accountCount = state.accountCount();
        int volumeCount = state.volumeCount();
        UUID owner = UUID.randomUUID();
        StorageVolume volume = null;
        try {
            volume = state.createVolume(owner, "Forge recovery delivery selftest", 1).orElseThrow();
            var ledger = volume.record().storage();
            var store = new ForgeTransferRecovery(root.resolve("recovery"));
            var stone = ItemKey.of(Items.STONE);
            var held = store.hold(owner, volume.id(), stone, 64, "Actual owned fixture items");
            boolean denied = false;
            try { ForgeRecoveryDelivery.deliver(state, store, UUID.randomUUID(), held.id(), 16); }
            catch (IllegalArgumentException expected) { denied = true; }
            expect(denied && ledger.totalItemCount() == 0 && store.entry(held.id()).state() == ForgeTransferRecovery.State.HELD,
                    "Foreign recovery delivery changed target or ownership");
            UUID other = UUID.randomUUID();
            var foreignTarget = store.hold(other, volume.id(), stone, 4, "Owned fixture targeting another player's volume");
            denied = false;
            try { ForgeRecoveryDelivery.deliver(state, store, other, foreignTarget.id(), 4); }
            catch (IllegalArgumentException expected) { denied = true; }
            expect(denied && ledger.totalItemCount() == 0 && store.entry(foreignTarget.id()).state() == ForgeTransferRecovery.State.HELD,
                    "Recovery entry owner bypassed target volume ownership");
            var partial = ForgeRecoveryDelivery.deliver(state, store, owner, held.id(), 16);
            expect(partial.state() == ForgeRecoveryDelivery.State.PARTIAL && partial.settled() == 16 && partial.remaining() == 48
                    && ledger.amountOf(stone) == 16 && new ForgeTransferRecovery(root.resolve("recovery")).entry(held.id()).amount() == 48,
                    "Owner partial delivery did not flush or conserve remaining ownership");
            var finished = ForgeRecoveryDelivery.deliver(state, store, owner, held.id(), Long.MAX_VALUE);
            expect(finished.state() == ForgeRecoveryDelivery.State.DELIVERED && finished.settled() == 48
                    && finished.remaining() == 0 && ledger.amountOf(stone) == 64
                    && store.entry(held.id()).state() == ForgeTransferRecovery.State.DELIVERED,
                    "Completed delivery did not settle exact ownership");
            denied = false;
            try { ForgeRecoveryDelivery.deliver(state, store, owner, held.id(), 1); }
            catch (IllegalStateException expected) { denied = true; }
            expect(denied && ledger.amountOf(stone) == 64, "Completed receipt was delivered again");

            var forbidden = store.hold(owner, volume.id(), ItemKey.of(Items.IRON_PICKAXE), 1, "Owned unstackable fixture");
            var rejected = ForgeRecoveryDelivery.deliver(state, store, owner, forbidden.id(), 1);
            expect(rejected.state() == ForgeRecoveryDelivery.State.BLOCKED && rejected.settled() == 0
                    && store.entry(forbidden.id()).state() == ForgeTransferRecovery.State.HELD,
                    "Recovery delivery bypassed volume unstackable policy");

            try (var transaction = LedgerTransaction.open()) {
                ledger.insert(stone, Long.MAX_VALUE, transaction);
                transaction.commit();
            }
            var blocked = store.hold(owner, volume.id(), stone, 1, "Owned fixture at full target");
            var result = ForgeRecoveryDelivery.deliver(state, store, owner, blocked.id(), 1);
            expect(result.state() == ForgeRecoveryDelivery.State.BLOCKED && result.settled() == 0
                    && store.entry(blocked.id()).state() == ForgeTransferRecovery.State.HELD && store.inFlightCount() == 0,
                    "Full target consumed recovery ownership or persisted delivery intent");
            clear(ledger, stone);
            var uncertain = store.hold(owner, volume.id(), stone, 8, "Actual owned fixture before flush failure");
            result = ForgeRecoveryDelivery.deliver(store, uncertain, owner, 8, ledger,
                    () -> { throw new IllegalStateException("Injected target flush failure"); });
            expect(result.state() == ForgeRecoveryDelivery.State.UNCERTAIN && result.settled() == 8
                    && ledger.amountOf(stone) == 8 && new ForgeTransferRecovery(root.resolve("recovery")).entry(uncertain.id()).state()
                    == ForgeTransferRecovery.State.DELIVERING, "Target flush failure became automatic recovery replay");
            denied = false;
            try { ForgeRecoveryDelivery.deliver(state, store, owner, uncertain.id(), 8); }
            catch (IllegalStateException expected) { denied = true; }
            expect(denied && ledger.amountOf(stone) == 8, "Uncertain delivery duplicated target items");

            Path fault = root.resolve("intent-fault");
            var failedStore = new ForgeTransferRecovery(fault);
            var intent = failedStore.hold(owner, volume.id(), stone, 5, "Owned fixture before intent failure");
            // Leave the authoritative .dat readable, but prevent creation of
            // its write temporary. A missing directory fails during the earlier
            // ownership reread and cannot test delivery-intent write retention.
            Path temporary = fault.resolve(intent.id() + ".tmp");
            Files.createDirectory(temporary);
            long before = ledger.amountOf(stone);
            result = ForgeRecoveryDelivery.deliver(failedStore, intent, owner, 5, ledger, () -> { });
            expect(result.state() == ForgeRecoveryDelivery.State.UNCERTAIN && result.settled() == 0
                    && ledger.amountOf(stone) == before && failedStore.unsavedCount() == 1 && !failedStore.available(),
                    "Failed delivery intent left reservation or permitted another attempt");
            Files.delete(temporary);
            failedStore.flushUnsaved();
            expect(new ForgeTransferRecovery(fault).entry(intent.id()).state() == ForgeTransferRecovery.State.DELIVERING,
                    "Intent retry silently delivered or restored automatic eligibility");
        } catch (IOException failure) { throw new IllegalStateException("Delivery filesystem fixture failed", failure); }
        finally {
            if (volume != null) {
                clear(volume.record().storage(), ItemKey.of(Items.STONE));
                expect(state.deleteEmptyVolume(owner, volume.id()), "Could not remove delivery fixture volume");
                state.flushNow();
            }
            if (!root.getFileName().toString().startsWith("digitalstorage-forge-delivery-")) throw new IllegalStateException("Unexpected delivery cleanup root");
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException failure) { throw new IllegalStateException("Could not clean delivery fixture", failure); }
        }
        expect(state.accountCount() == accountCount && state.volumeCount() == volumeCount, "Delivery fixture left account/volume data");
    }
    private static void clear(VolumeLedger ledger, ItemKey key) {
        try (var transaction = LedgerTransaction.open()) { ledger.extract(key, Long.MAX_VALUE, transaction); transaction.commit(); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
