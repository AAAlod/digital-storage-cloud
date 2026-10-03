package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.storage.ItemKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ForgeRecoverySelfTest {
    private ForgeRecoverySelfTest() { }
    public static void run() {
        Path root = temporary();
        try {
            UUID owner = UUID.randomUUID();
            UUID volume = UUID.randomUUID();
            var store = new ForgeTransferRecovery(root);
            var entry = store.hold(owner, volume, ItemKey.of(Items.STONE), 64, "Actual extraction did not settle");
            var reopened = new ForgeTransferRecovery(root);
            expect(reopened.available() && reopened.entries(owner).size() == 1
                            && reopened.entry(entry.id()).equals(entry), "Held recovery ownership did not round-trip");
            boolean denied = false;
            try { reopened.beginDelivery(entry.id(), UUID.randomUUID(), 1); }
            catch (IllegalArgumentException expected) { denied = true; }
            expect(denied && reopened.entry(entry.id()).equals(entry), "Foreign player altered recovery ownership");
            reopened.beginDelivery(entry.id(), owner, 16);
            var interrupted = new ForgeTransferRecovery(root);
            expect(interrupted.entry(entry.id()).state() == ForgeTransferRecovery.State.DELIVERING,
                    "Interrupted delivery became automatically eligible");
            boolean replayRejected = false;
            try { interrupted.beginDelivery(entry.id(), owner, 16); }
            catch (IllegalStateException expected) { replayRejected = true; }
            expect(replayRejected, "Uncertain delivery was replayed");
            var remaining = reopened.finishDelivery(entry.id(), owner);
            expect(remaining.amount() == 48 && remaining.state() == ForgeTransferRecovery.State.HELD,
                    "Partial recovery delivery lost the remainder");
            reopened.beginDelivery(entry.id(), owner, 48);
            reopened.finishDelivery(entry.id(), owner);
            var complete = new ForgeTransferRecovery(root);
            expect(complete.pendingCount() == 0 && complete.entries(owner).isEmpty()
                            && complete.entry(entry.id()).state() == ForgeTransferRecovery.State.DELIVERED,
                    "Completed receipt became a pending recovery stack");
            boolean staleReplayRejected = false;
            try { store.beginDelivery(entry.id(), owner, 1); }
            catch (IllegalStateException expected) { staleReplayRejected = true; }
            expect(staleReplayRejected, "Stale recovery store replayed a completed receipt");

            var longEntry = store.hold(owner, volume, ItemKey.of(Items.DIRT), 2147483728L, "Long ownership amount");
            expect(new ForgeTransferRecovery(root).entry(longEntry.id()).amount() == 2147483728L,
                    "Recovery amount was narrowed to int");
            var future = ForgeTransferRecovery.encode(longEntry);
            future.putInt("SchemaVersion", 999);
            Path file = root.resolve(longEntry.id() + ".dat");
            NbtIo.writeCompressed(future, file.toFile());
            byte[] original = Files.readAllBytes(file);
            var blocked = new ForgeTransferRecovery(root);
            expect(!blocked.available() && blocked.unreadableFiles() == 1
                            && java.util.Arrays.equals(original, Files.readAllBytes(file)),
                    "Future recovery schema was discarded or rewritten");
            var badIdentity = ForgeTransferRecovery.encode(entry);
            badIdentity.getCompound("Variant").putString("item", "missing_mod:missing_item");
            boolean rejectedMissing = false;
            try { ForgeTransferRecovery.read(badIdentity); }
            catch (IllegalArgumentException expected) { rejectedMissing = true; }
            expect(rejectedMissing, "Missing mod recovery item was converted to an empty stack");
            badIdentity = ForgeTransferRecovery.encode(entry);
            badIdentity.getCompound("Variant").putString("attachments", "invalid");
            boolean rejectedPayload = false;
            try { ForgeTransferRecovery.read(badIdentity); }
            catch (IllegalArgumentException expected) { rejectedPayload = true; }
            expect(rejectedPayload, "Malformed platform payload was silently dropped");
            writeFailureRetainsOwnership(root.resolve("write-failure"), owner, volume);
        } catch (IOException failure) {
            throw new IllegalStateException("Forge recovery persistence regression failed", failure);
        } finally { remove(root); }
    }

    private static void writeFailureRetainsOwnership(Path directory, UUID owner, UUID volume) throws IOException {
        var store = new ForgeTransferRecovery(directory);
        // Rename the owned test directory and replace it with a file to produce
        // an actual filesystem write failure without relying on OS permissions.
        Path retained = directory.resolveSibling("retained-write-failure");
        Files.move(directory, retained);
        Files.writeString(directory, "test write failure");
        boolean failed = false;
        try { store.hold(owner, volume, ItemKey.of(Items.PAPER), 9, "Injected write failure"); }
        catch (IllegalStateException expected) { failed = true; }
        expect(failed && !store.available() && store.unsavedCount() == 1 && store.entries(owner).get(0).amount() == 9,
                "Failed recovery write discarded returned-item ownership or allowed more transfers");
        Files.delete(directory);
        Files.move(retained, directory);
        store.flushUnsaved();
        expect(store.available() && store.unsavedCount() == 0
                        && new ForgeTransferRecovery(directory).entries(owner).get(0).amount() == 9,
                "Retained failed-write ownership did not persist after filesystem recovery");
    }

    public static void capabilityRoundTrip(ItemStack stack) {
        Path root = temporary();
        try {
            var store = new ForgeTransferRecovery(root);
            var entry = store.hold(UUID.randomUUID(), UUID.randomUUID(), ItemKey.of(stack), stack.getCount(), "Capability settlement");
            var restored = new ForgeTransferRecovery(root).entry(entry.id());
            expect(restored.equals(entry) && ItemKey.of(restored.key().toStack(1)).equals(entry.key()),
                    "Persisted recovery ownership lost actual ForgeCaps");
        } finally { remove(root); }
    }

    private static Path temporary() {
        try { return Files.createTempDirectory("digitalstorage-forge-recovery-").toAbsolutePath().normalize(); }
        catch (IOException failure) { throw new IllegalStateException("Could not create recovery test directory", failure); }
    }
    private static void remove(Path directory) {
        if (!directory.getFileName().toString().startsWith("digitalstorage-forge-recovery-")) {
            throw new IllegalArgumentException("Unexpected recovery test cleanup root");
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failure) { throw new IllegalStateException("Could not clean recovery test directory", failure); }
    }
    private static void expect(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
