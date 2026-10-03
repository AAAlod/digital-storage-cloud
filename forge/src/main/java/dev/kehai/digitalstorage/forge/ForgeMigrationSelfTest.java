package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.forge.tom.ForgeTomTopology;
import dev.kehai.digitalstorage.optimization.MigrationTask;
import dev.kehai.digitalstorage.optimization.NetworkServices.StartResult;
import dev.kehai.digitalstorage.storage.DigitalStorageRecord;
import dev.kehai.digitalstorage.storage.DigitalStorageState;
import dev.kehai.digitalstorage.storage.ItemKey;
import dev.kehai.digitalstorage.storage.LedgerTransaction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/** Actual Forge source transfers with injected binding lifetime; world discovery is tested separately. */
public final class ForgeMigrationSelfTest {
    private ForgeMigrationSelfTest() { }
    public static void run(MinecraftServer server) {
        Path root;
        try { root = Files.createTempDirectory("digitalstorage-forge-migration-").toAbsolutePath().normalize(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        var state = DigitalStorageState.get(server.overworld());
        int accounts = state.accountCount();
        int volumes = state.volumeCount();
        UUID owner = UUID.randomUUID();
        var volume = state.createVolume(owner, "Forge migration selftest", 1).orElseThrow();
        var record = volume.record();
        var key = ItemKey.of(Items.STONE);
        var origin = new ForgeInventoryTransferExecutor.Origin(true, "minecraft:overworld", 0, 1);
        var binding = new AtomicReference<DigitalStorageRecord>(record);
        try {
            var session = ForgeTransferSessions.openForTest(root.resolve("normal"));
            var jobs = new ForgeMigrationManager.Jobs();
            var source = new Source(160, null);
            source.setStackInSlot(1, new ItemStack(Items.STONE, 10));
            var report = ForgeTomTopology.analyze(record, () -> source);
            expect(jobs.start(owner, volume.id(), report, session, origin, binding::get, 1) == StartResult.STARTED,
                    "Valid migration did not start");
            expect(jobs.start(owner, volume.id(), report, session, origin, binding::get, 1) == StartResult.ALREADY_RUNNING,
                    "Migration started twice for one target");
            jobs.tick(2, 2);
            expect(jobs.status(volume.id()).movedItems().equals("128") && jobs.status(volume.id()).scannedViews() == 2
                    && source.getStackInSlot(0).getCount() == 32 && source.getStackInSlot(1).getCount() == 10,
                    "Migration manager ignored batch budget or source continuation");
            jobs.tick(3, 2);
            jobs.tick(4, 2);
            expect(jobs.status(volume.id()).state() == MigrationTask.State.COMPLETE
                    && jobs.status(volume.id()).movedItems().equals("170") && record.storage().amountOf(key) == 170,
                    "Migration manager did not complete with conserved quantities");
            jobs.tick(1205, 2);
            expect(jobs.status(volume.id()).state() == MigrationTask.State.IDLE, "Finished status did not expire");
            clear(record, key);

            var cancelled = new ForgeMigrationManager.Jobs();
            var cancelSource = new Source(160, () -> cancelled.cancel(owner, volume.id(), 6, "cancelled"));
            cancelSource.setStackInSlot(1, new ItemStack(Items.STONE, 10));
            report = ForgeTomTopology.analyze(record, () -> cancelSource);
            cancelled.start(owner, volume.id(), report, session, origin, binding::get, 5);
            expect(!cancelled.cancel(UUID.randomUUID(), volume.id(), 5, "foreign"), "Foreign player cancelled an owned migration");
            cancelled.tick(6, 8);
            expect(cancelled.status(volume.id()).state() == MigrationTask.State.CANCELLED
                    && cancelled.status(volume.id()).movedItems().equals("64") && record.storage().amountOf(key) == 64
                    && cancelSource.getStackInSlot(0).getCount() == 96 && cancelSource.getStackInSlot(1).getCount() == 10,
                    "Reentrant cancellation lost settled count or continued actual extraction");
            clear(record, key);

            var changed = new ForgeMigrationManager.Jobs();
            var network = new AtomicReference<IItemHandler>();
            var changingSource = new Source(160, () -> network.set(null));
            changingSource.setStackInSlot(1, new ItemStack(Items.STONE, 10));
            network.set(changingSource);
            report = ForgeTomTopology.analyze(record, network::get);
            changed.start(owner, volume.id(), report, session, origin, binding::get, 7);
            changed.tick(8, 8);
            expect(changed.status(volume.id()).state() == MigrationTask.State.STOPPED
                    && changed.status(volume.id()).movedItems().equals("64") && record.storage().amountOf(key) == 64
                    && changingSource.getStackInSlot(0).getCount() == 96 && changingSource.getStackInSlot(1).getCount() == 10,
                    "Mid-tick topology change continued extraction or lost settled items");
            clear(record, key);

            var unloaded = new ForgeMigrationManager.Jobs();
            var untouched = new Source(20, null);
            report = ForgeTomTopology.analyze(record, () -> untouched);
            unloaded.start(owner, volume.id(), report, session, origin, binding::get, 9);
            binding.set(null);
            unloaded.tick(10, 8);
            expect(unloaded.status(volume.id()).state() == MigrationTask.State.STOPPED && untouched.getStackInSlot(0).getCount() == 20,
                    "Unavailable accessor binding allowed migration");
            binding.set(record);
            var logout = new ForgeMigrationManager.Jobs();
            logout.start(owner, volume.id(), report, session, origin, binding::get, 11);
            logout.cancelOwner(owner, 12);
            expect(logout.status(volume.id()).state() == MigrationTask.State.CANCELLED && untouched.getStackInSlot(0).getCount() == 20,
                    "Logout did not cancel without touching source");
            var stopping = new ForgeMigrationManager.Jobs();
            stopping.start(owner, volume.id(), report, session, origin, binding::get, 13);
            stopping.stopAll(14, "server stopping");
            expect(stopping.status(volume.id()).state() == MigrationTask.State.STOPPED && untouched.getStackInSlot(0).getCount() == 20,
                    "Server stopping did not release migration jobs");
            var stopCallback = new ForgeMigrationManager.Jobs();
            var stopSource = new Source(160, () -> stopCallback.stopAll(16, "server stopping"));
            var stopReport = ForgeTomTopology.analyze(record, () -> stopSource);
            stopCallback.start(owner, volume.id(), stopReport, session, origin, binding::get, 15);
            stopCallback.tick(16, 1);
            expect(stopCallback.status(volume.id()).state() == MigrationTask.State.STOPPED
                    && stopCallback.status(volume.id()).movedItems().equals("64") && stopSource.getStackInSlot(0).getCount() == 96,
                    "Reentrant server stop lost settled count or left a running status");
            expect(stopCallback.start(owner, volume.id(), stopReport, session, origin, binding::get, 17) == StartResult.NO_NETWORK,
                    "Stopping server accepted a new migration");
            clear(record, key);
            session.incidents().record(new ForgeInventoryTransferExecutor.Incident(owner, volume.id(), "fixture", 0, 0,
                    System.currentTimeMillis(), "Unresolved source observation"));
            var blocked = new ForgeMigrationManager.Jobs();
            expect(blocked.start(owner, volume.id(), report, session, origin, binding::get, 15) == StartResult.RECOVERY_REQUIRED,
                    "Pending recovery observation did not block migration startup");
        } finally {
            clear(record, key);
            expect(state.deleteEmptyVolume(owner, volume.id()), "Migration fixture volume could not be removed");
            state.flushNow();
            if (!root.getFileName().toString().startsWith("digitalstorage-forge-migration-")) throw new IllegalStateException("Unexpected migration cleanup root");
            try (var files = Files.walk(root)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
            catch (IOException failure) { throw new IllegalStateException("Could not clean migration fixture", failure); }
        }
        expect(state.accountCount() == accounts && state.volumeCount() == volumes, "Migration fixture left account/volume records");
    }
    private static void clear(DigitalStorageRecord record, ItemKey key) {
        try (var transaction = LedgerTransaction.open()) { record.storage().extract(key, Long.MAX_VALUE, transaction); transaction.commit(); }
    }
    private static final class Source extends ItemStackHandler {
        private final Runnable callback;
        private Source(int count, Runnable callback) { super(3); this.callback = callback; setStackInSlot(0, new ItemStack(Items.STONE, count)); }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            var returned = super.extractItem(slot, amount, simulate);
            if (!simulate && callback != null) callback.run();
            return returned;
        }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
