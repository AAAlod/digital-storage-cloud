package dev.kehai.digitalstorage.forge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;

public final class ForgeTransferSessionSelfTest {
    private ForgeTransferSessionSelfTest() { }
    public static void run(MinecraftServer server) {
        var active = ForgeTransferSessions.get(server);
        expect(active == ForgeTransferSessions.get(server) && active.available(), "Running server has no unique ready transfer session");
        Path root;
        try { root = Files.createTempDirectory("digitalstorage-forge-session-").toAbsolutePath().normalize(); }
        catch (IOException failure) { throw new IllegalStateException(failure); }
        try {
            var session = ForgeTransferSessions.openForTest(root.resolve("session"));
            var incident = new ForgeInventoryTransferExecutor.Incident(UUID.randomUUID(), UUID.randomUUID(),
                    "test.original.filtered.handler", 7, 0, System.currentTimeMillis(), "Source threw without returned ownership");
            UUID id = session.incidents().record(incident);
            expect(!session.available() && session.recovery().pendingCount() == 0
                    && session.incidents().unresolvedCount() == 1, "Incident became owned items or failed to block new transfers");
            var reopened = ForgeTransferSessions.openForTest(root.resolve("session"));
            expect(!reopened.available() && reopened.incidents().entries().get(0).incident().equals(incident),
                    "Incident observation did not persist across session reopen");
            UUID administrator = UUID.randomUUID();
            reopened.incidents().acknowledge(id, administrator, "External source inspected; no DSC-owned returned stack");
            expect(ForgeTransferSessions.openForTest(root.resolve("session")).available()
                    && reopened.recovery().pendingCount() == 0, "Acknowledgement changed ownership or lost its receipt");
            boolean staleRejected = false;
            try { session.incidents().acknowledge(id, administrator, "stale request"); }
            catch (IllegalStateException expected) { staleRejected = true; }
            expect(staleRejected, "Stale session overwrote acknowledgement evidence");

            var bound = ForgeTransferSessions.openForTest(root.resolve("bound"));
            var first = bound.executor(incident.owner(), incident.volume());
            var second = bound.executor(incident.owner(), incident.volume());
            var target = new dev.kehai.digitalstorage.storage.VolumeLedger(incident.volume(), () -> { }, 32);
            var stone = dev.kehai.digitalstorage.storage.ItemKey.of(net.minecraft.world.item.Items.STONE);
            var source = new net.minecraftforge.items.ItemStackHandler(1) {
                @Override public net.minecraft.world.item.ItemStack extractItem(int slot, int amount, boolean simulate) {
                    if (!simulate) throw new IllegalStateException("Source callback failed");
                    return super.extractItem(slot, amount, true);
                }
            };
            source.setStackInSlot(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 10));
            var view = new ForgeInventoryEndpoint.View(source, 0);
            var result = first.move(view, target, stone, 10);
            expect(result.moved() == 0 && !result.stopDetail().isEmpty() && bound.incidents().unresolvedCount() == 1
                    && ForgeTransferSessions.openForTest(root.resolve("bound")).incidents().unresolvedCount() == 1
                    && second.move(view, target, stone, 10).operations() == 0,
                    "Executor sink did not persist or block another already-created session executor");

            Path records = root.resolve("fault");
            var faulted = new ForgeTransferIncidents(records);
            Path backup = root.resolve("fault-backup");
            Files.move(records, backup);
            Files.writeString(records, "blocked directory");
            boolean failed = false;
            try { faulted.record(incident); }
            catch (IllegalStateException expected) { failed = true; }
            expect(failed && faulted.unsavedCount() == 1 && faulted.unresolvedCount() == 1 && !faulted.available(),
                    "Incident write failure lost observation");
            Files.delete(records);
            Files.move(backup, records);
            faulted.flushUnsaved();
            expect(faulted.unsavedCount() == 0 && new ForgeTransferIncidents(records).unresolvedCount() == 1,
                    "Incident did not persist after filesystem repair");

            var future = ForgeTransferIncidents.encode(faulted.entries().get(0));
            future.putInt("SchemaVersion", 999);
            Path file = records.resolve(faulted.entries().get(0).id() + ".dat");
            NbtIo.writeCompressed(future, file.toFile());
            byte[] original = Files.readAllBytes(file);
            var unknown = new ForgeTransferIncidents(records);
            expect(unknown.unreadableFiles() == 1 && !unknown.available()
                    && java.util.Arrays.equals(original, Files.readAllBytes(file)), "Future incident schema was silently overwritten");

            Path interrupted = root.resolve("interrupted");
            Files.createDirectories(interrupted);
            Path temp = interrupted.resolve(UUID.randomUUID() + ".tmp");
            Files.writeString(temp, "interrupted durable write");
            original = Files.readAllBytes(temp);
            expect(!new ForgeTransferRecovery(interrupted).available() && !new ForgeTransferIncidents(interrupted).available()
                    && java.util.Arrays.equals(original, Files.readAllBytes(temp)), "Interrupted temporary evidence was ignored or modified");
        } catch (IOException failure) { throw new IllegalStateException("Transfer session fixture failed", failure); }
        finally {
            if (!root.getFileName().toString().startsWith("digitalstorage-forge-session-")) throw new IllegalStateException("Unexpected session cleanup root");
            try (var files = Files.walk(root)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            } catch (IOException failure) { throw new IllegalStateException("Could not clean session fixture", failure); }
        }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
