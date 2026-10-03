package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Single writer per running world; unflushed ownership survives a same-process server stop. */
public final class ForgeTransferSessions {
    private static final Map<MinecraftServer, Session> ACTIVE = new IdentityHashMap<>();
    private static final Map<Path, Session> RETAINED = new LinkedHashMap<>();
    private ForgeTransferSessions() { }
    public static synchronized void starting(MinecraftServer server) {
        if (ACTIVE.containsKey(server)) return;
        Path root = server.getWorldPath(LevelResource.ROOT).resolve("digitalstorage/forge-transfer").toAbsolutePath().normalize();
        if (ACTIVE.values().stream().anyMatch(session -> session.root.equals(root))) {
            throw new IllegalStateException("A Forge transfer writer already owns this world");
        }
        Session session = RETAINED.remove(root);
        if (session == null) session = new Session(root);
        else session.flush();
        ACTIVE.put(server, session);
        DigitalStorage.LOGGER.info("Forge transfer session opened: {}", session.diagnostics());
    }
    public static synchronized Session get(MinecraftServer server) {
        Session session = ACTIVE.get(server);
        if (session == null) throw new IllegalStateException("Forge transfer session is not running");
        return session;
    }
    static synchronized Session find(MinecraftServer server) { return ACTIVE.get(server); }
    public static void stopping(MinecraftServer server) { get(server).flush(); }
    public static synchronized void stopped(MinecraftServer server) {
        Session session = ACTIVE.remove(server);
        if (session == null) return;
        session.flush();
        if (session.hasUnflushed()) {
            RETAINED.put(session.root, session);
            DigitalStorage.LOGGER.error("Forge transfer ownership/observations remain only in memory at {}; retained for same-process retry, not durable across process exit", session.root);
        }
        DigitalStorage.LOGGER.info("Forge transfer session closed: {}", session.diagnostics());
    }
    static Session openForTest(Path root) { return new Session(root.toAbsolutePath().normalize(), true); }

    public static final class Session {
        private final Path root;
        private final boolean privateFixture;
        private ForgeTransferRecovery recovery;
        private ForgeTransferIncidents incidents;
        private ForgeHopperCustody hoppers;
        private boolean initialized;
        private Session(Path root) { this(root, false); }
        private Session(Path root, boolean privateFixture) {
            this.root = root; this.privateFixture = privateFixture; flush();
        }
        private void failure(String store, RuntimeException failure) {
            if (privateFixture) DigitalStorage.LOGGER.info("Forge transfer private fixture expected store failure [{}] at {}: {}",
                    store, root, failure.toString());
            else DigitalStorage.LOGGER.error("Forge {} flush failed at {}", store, root, failure);
        }
        public ForgeTransferRecovery recovery() {
            if (!initialized) throw new IllegalStateException("Forge transfer recovery initialization failed");
            return recovery;
        }
        public ForgeTransferIncidents incidents() {
            if (!initialized) throw new IllegalStateException("Forge transfer incident initialization failed");
            return incidents;
        }
        public ForgeHopperCustody hoppers() {
            if (hoppers == null) throw new IllegalStateException("Forge hopper custody initialization failed");
            return hoppers;
        }
        public boolean available() { return initialized && recovery.available() && recovery.inFlightCount() == 0
                && incidents.available() && hoppers.available(); }
        public ForgeInventoryTransferExecutor executor(UUID owner, UUID volume) {
            return executor(owner, volume, ForgeInventoryTransferExecutor.Origin.unknown());
        }
        public ForgeInventoryTransferExecutor executor(UUID owner, UUID volume, ForgeInventoryTransferExecutor.Origin origin) {
            if (!available()) throw new IllegalStateException("Forge transfer recovery requires reconciliation");
            return new ForgeInventoryTransferExecutor(owner, volume, recovery, incidents::record, this::available, origin);
        }
        public boolean flush() {
            boolean successful = true;
            // The hopper owner must exist even if another store cannot open.
            // Removal hooks can then retain returned instances before disk retry.
            if (hoppers == null) hoppers = new ForgeHopperCustody(root.resolve("hoppers"));
            try {
                if (recovery == null) recovery = new ForgeTransferRecovery(root.resolve("recovery"));
                recovery.flushUnsaved();
            } catch (RuntimeException failure) {
                successful = false;
                failure("transfer recovery", failure);
            }
            try {
                if (incidents == null) incidents = new ForgeTransferIncidents(root.resolve("incidents"));
                incidents.flushUnsaved();
            } catch (RuntimeException failure) {
                successful = false;
                failure("transfer incident", failure);
            }
            try {
                successful &= hoppers.flush();
            } catch (RuntimeException failure) {
                successful = false;
                failure("hopper custody", failure);
            }
            initialized = recovery != null && incidents != null && hoppers != null;
            return successful;
        }
        public boolean hasUnflushed() {
            return (recovery != null && recovery.unsavedCount() > 0) || (incidents != null && incidents.unsavedCount() > 0)
                    || (hoppers != null && hoppers.unsavedCount() > 0);
        }
        public String diagnostics() {
            if (!initialized) return "transfer stores unavailable; migration blocked; hoppers pending=" + hoppers.pendingCount()
                    + ", unsaved=" + hoppers.unsavedCount() + ", opened=" + hoppers.opened()
                    + ", opening failure=" + hoppers.openingFailure();
            return "transfer ready=" + available() + ", recovery pending=" + recovery.pendingCount()
                    + ", delivering=" + recovery.inFlightCount() + ", raw=" + recovery.uncapturedCount()
                    + ", unsaved=" + recovery.unsavedCount() + ", unreadable=" + recovery.unreadableFiles()
                    + "; incidents unresolved=" + incidents.unresolvedCount() + ", unsaved=" + incidents.unsavedCount()
                    + ", unreadable=" + incidents.unreadableFiles()
                    + "; hoppers pending=" + hoppers.pendingCount() + ", unsaved=" + hoppers.unsavedCount()
                    + ", unreadable=" + hoppers.unreadableFiles() + ", opened=" + hoppers.opened()
                    + ", opening failure=" + hoppers.openingFailure();
        }
    }
}
