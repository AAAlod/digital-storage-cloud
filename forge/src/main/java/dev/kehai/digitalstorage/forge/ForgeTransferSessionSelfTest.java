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
        expect(active == ForgeTransferSessions.get(server), "Running server has no unique transfer session");
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
            var origin = new ForgeInventoryTransferExecutor.Origin(true, "minecraft:overworld",
                    new net.minecraft.core.BlockPos(4, 70, 8).asLong(), new net.minecraft.core.BlockPos(5, 70, 8).asLong());
            var first = bound.executor(incident.owner(), incident.volume(), origin);
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
            var persisted = ForgeTransferSessions.openForTest(root.resolve("bound")).incidents().entries().get(0).incident();
            var observation = persisted.observation();
            expect(persisted.origin().equals(origin) && observation.known() && observation.maximum() == 10
                    && observation.observedKnown() && observation.observed() == 10 && observation.requested() == 10 && observation.reserved() == 10
                    && observation.actualStarted() && observation.stage() == ForgeInventoryTransferExecutor.Stage.EXTRACTION
                    && observation.expectedVariant().getString("item").equals("minecraft:stone")
                    && bound.recovery().pendingCount() == 0,
                    "Persisted source context or request was lost or interpreted as owned items");
            observation.expectedVariant().putString("item", "minecraft:dirt");
            expect(observation.expectedVariant().getString("item").equals("minecraft:stone"), "Incident identity exposed mutable NBT");
            var unread = ForgeTransferSessions.openForTest(root.resolve("unread"));
            var unreadSource = new net.minecraftforge.items.ItemStackHandler(1) {
                @Override public net.minecraft.world.item.ItemStack getStackInSlot(int slot) {
                    throw new IllegalStateException("Source unavailable before quantity read");
                }
            };
            var unreadResult = unread.executor(incident.owner(), incident.volume(), origin).move(
                    new ForgeInventoryEndpoint.View(unreadSource, 0), target, stone, 10);
            var unreadEntry = ForgeTransferSessions.openForTest(root.resolve("unread")).incidents().entries().get(0);
            var unreadObservation = unreadEntry.incident().observation();
            expect(!unreadResult.stopDetail().isEmpty() && unreadObservation.known() && !unreadObservation.observedKnown()
                    && unreadObservation.maximum() == 10 && unreadObservation.requested() == 0 && !unreadObservation.actualStarted()
                    && unreadObservation.stage() == ForgeInventoryTransferExecutor.Stage.PREFLIGHT
                    && unread.recovery().pendingCount() == 0,
                    "Failed source read invented a known zero quantity or owned recovery items");
            var schemaTwo = ForgeTransferIncidents.encode(bound.incidents().entries().get(0));
            schemaTwo.putInt("SchemaVersion", 2);
            schemaTwo.getCompound("Observation").remove("ObservedKnown");
            expect(ForgeTransferIncidents.read(schemaTwo).incident().observation().observedKnown(),
                    "Schema 2 actual extraction lost its valid source observation");
            schemaTwo = ForgeTransferIncidents.encode(unreadEntry);
            schemaTwo.putInt("SchemaVersion", 2);
            schemaTwo.getCompound("Observation").remove("ObservedKnown");
            expect(!ForgeTransferIncidents.read(schemaTwo).incident().observation().observedKnown(),
                    "Schema 2 preflight default was interpreted as a known empty source");
            var malformed = ForgeTransferIncidents.encode(unreadEntry);
            malformed.getCompound("Observation").remove("ObservedKnown");
            boolean malformedRejected = false;
            try { ForgeTransferIncidents.read(malformed); }
            catch (IllegalArgumentException expected) { malformedRejected = true; }
            expect(malformedRejected, "Schema 3 missing observation validity was silently inferred");
            var legacy = ForgeTransferIncidents.encode(reopened.incidents().entries().get(0));
            legacy.putInt("SchemaVersion", 1);
            legacy.remove("Origin");
            legacy.remove("Observation");
            var older = ForgeTransferIncidents.read(legacy);
            expect(!older.incident().origin().known() && !older.incident().observation().known()
                    && older.administrator().equals(administrator), "Legacy event invented request data or lost acknowledgement");

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

            var custodySession = ForgeTransferSessions.openForTest(root.resolve("custody"));
            UUID device = UUID.randomUUID();
            var token = new Object();
            boolean[] encodingFails = {true};
            var deviceState = net.minecraft.nbt.StringTag.valueOf("opaque stopped hopper state");
            expect(!custodySession.hoppers().retain(device, "minecraft:overworld", net.minecraft.core.BlockPos.ZERO,
                    token, () -> {
                        if (encodingFails[0]) throw new IllegalStateException("Injected device encoding failure");
                        return deviceState;
                    }) && custodySession.hasUnflushed() && !custodySession.available(),
                    "Session lost raw hopper ownership or allowed new transfer");
            expect(!custodySession.flush() && custodySession.hasUnflushed(), "Failed hopper flush reported success");
            encodingFails[0] = false;
            expect(custodySession.flush() && !custodySession.hasUnflushed() && !custodySession.available(),
                    "Durable hopper pending state should still block transfer");
            var custodyReopened = ForgeTransferSessions.openForTest(root.resolve("custody"));
            expect(!custodyReopened.available() && custodyReopened.hoppers().state(device).equals(deviceState)
                    && custodyReopened.diagnostics().contains("hoppers pending=1"), "Session reopen lost hopper custody");
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
