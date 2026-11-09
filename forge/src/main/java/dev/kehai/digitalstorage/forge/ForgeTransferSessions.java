package dev.kehai.digitalstorage.forge;

import dev.kehai.digitalstorage.DigitalStorage;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
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
        restoreDisplacedTransferRoot(root);
        Session session = RETAINED.remove(root);
        if (session == null) session = new Session(root);
        else { session.refreshLegacy(); session.flush(); session.reportLegacy(); }
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

    static void restoreDisplacedTransferRoot(Path root) {
        Path world = root.getParent().getParent();
        try (var paths = Files.list(world)) {
            var displaced = paths.filter(path -> path.getFileName().toString().startsWith("digitalstorage-legacy-"))
                    .filter(Files::isDirectory).map(path -> path.resolve("forge-transfer"))
                    .filter(Files::exists).toList();
            if (Files.exists(root)) {
                return;
            }
            if (displaced.size() > 1) throw new IllegalStateException("Multiple Forge transfer stores found at " + displaced
                    + "; stop the server, preserve all copies and manually reconcile their recovery, incident and hopper records before restarting");
            if (displaced.size() == 1) {
                Files.createDirectories(root.getParent());
                Files.move(displaced.get(0), root);
                DigitalStorage.LOGGER.warn("Restored displaced Forge transfer store from {}", displaced.get(0));
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Could not restore displaced Forge transfer store; preserving it for reconciliation", failure);
        }
    }

    private record LegacyInspection(Path root, int pending, int inFlight, int unreadable, int unsaved) {
        boolean clear() { return pending == 0 && inFlight == 0 && unreadable == 0 && unsaved == 0; }
        @Override public String toString() { return root + " [pending=" + pending + ", inFlight=" + inFlight + ", unreadable=" + unreadable + ", unsaved=" + unsaved + "]"; }
    }
    private static synchronized LegacyInspection inspectLegacy(Path root) {
        int pending = 0, inFlight = 0, unreadable = 0, unsaved = 0;
        try (var paths = Files.list(root)) {
            for (var path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!java.util.Set.of("recovery", "incidents", "hoppers").contains(name)
                        || !Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { unreadable++; continue; }
                try (var files = Files.list(path)) {
                    for (var file : files.toList()) if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                            || !(file.getFileName().toString().endsWith(".dat") || file.getFileName().toString().endsWith(".tmp"))) unreadable++;
                }
                if (name.equals("recovery")) {
                    var store = new ForgeTransferRecovery(path, true);
                    pending += store.pendingCount(); inFlight += store.inFlightCount(); unreadable += store.unreadableFiles(); unsaved += store.unsavedCount();
                } else if (name.equals("incidents")) {
                    var store = new ForgeTransferIncidents(path, true);
                    pending += store.unresolvedCount(); unreadable += store.unreadableFiles(); unsaved += store.unsavedCount();
                } else {
                    var store = new ForgeHopperCustody(path, true);
                    pending += store.pendingCount(); unreadable += store.unreadableFiles(); unsaved += store.unsavedCount();
                    if (!store.opened()) unreadable++;
                }
            }
        } catch (IOException | RuntimeException e) { unreadable++; }
        var held = RETAINED.get(root.toAbsolutePath().normalize());
        if (held != null && held.hasUnflushed()) {
            if (held.recovery != null) unsaved += held.recovery.unsavedCount();
            if (held.incidents != null) unsaved += held.incidents.unsavedCount();
            if (held.hoppers != null) unsaved += held.hoppers.unsavedCount();
        }
        for (var active : ACTIVE.values()) if (active.root.equals(root.toAbsolutePath().normalize()) && active.hasUnflushed()) {
            if (active.recovery != null) unsaved += active.recovery.unsavedCount();
            if (active.incidents != null) unsaved += active.incidents.unsavedCount();
            if (active.hoppers != null) unsaved += active.hoppers.unsavedCount();
        }
        return new LegacyInspection(root, pending, inFlight, unreadable, unsaved);
    }
    private static java.util.List<LegacyInspection> inspectDisplaced(Path root) {
        if (!Files.exists(root)) return java.util.List.of();
        Path world = root.getParent().getParent();
        try (var paths = Files.list(world)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("digitalstorage-legacy-"))
                    .map(path -> path.resolve("forge-transfer"))
                    .filter(path -> !Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .map(ForgeTransferSessions::inspectLegacy).toList();
        } catch (IOException | RuntimeException e) {
            return java.util.List.of(new LegacyInspection(world, 0, 0, 1, 0));
        }
    }

    public static final class Session {
        private final Path root;
        private final boolean privateFixture;
        private ForgeTransferRecovery recovery;
        private ForgeTransferIncidents incidents;
        private ForgeHopperCustody hoppers;
        private boolean initialized;
        private java.util.List<LegacyInspection> legacy;
        private boolean reconciliationRequired;
        private Session(Path root) { this(root, false); }
        private Session(Path root, boolean privateFixture) {
            this.root = root; this.privateFixture = privateFixture;
            refreshLegacy();
            flush();
            reportLegacy();
        }
        private void refreshLegacy() {
            this.legacy = inspectDisplaced(root);
            this.reconciliationRequired = legacy.stream().anyMatch(inspection -> !inspection.clear());
        }
        private void reportLegacy() {
            if (reconciliationRequired) DigitalStorage.LOGGER.error("Forge transfer reconciliation required: {}; preserve both stores and inspect all records", diagnostics());
            else if (!legacy.isEmpty()) DigitalStorage.LOGGER.warn("Forge transfer stores coexist: {}; no files were merged", diagnostics());
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
        public boolean available() { return !reconciliationRequired && initialized && recovery.available() && recovery.inFlightCount() == 0
                && incidents.available() && hoppers.available(); }
        public boolean recoveryAvailable() { return !reconciliationRequired && initialized && recovery.available() && incidents.available(); }
        public ForgeInventoryTransferExecutor executorAllowingPending(UUID owner, UUID volume, ForgeInventoryTransferExecutor.Origin origin) {
            if (!recoveryAvailable()) throw new IllegalStateException("recovery_required");
            return new ForgeInventoryTransferExecutor(owner, volume, recovery, incidents::record, this::recoveryAvailable, origin);
        }
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
            if (!initialized) return "transfer stores unavailable; root=" + root + ", reconciliationRequired=" + reconciliationRequired
                    + ", legacy=" + legacy + "; migration blocked; hoppers pending=" + hoppers.pendingCount()
                    + ", unsaved=" + hoppers.unsavedCount() + ", opened=" + hoppers.opened()
                    + ", opening failure=" + hoppers.openingFailure();
            return "transfer ready=" + available() + ", root=" + root + ", reconciliationRequired=" + reconciliationRequired + ", legacy=" + legacy + "; recovery pending=" + recovery.pendingCount()
                    + ", inFlight=" + recovery.inFlightCount() + ", raw=" + recovery.uncapturedCount()
                    + ", unsaved=" + recovery.unsavedCount() + ", unreadable=" + recovery.unreadableFiles()
                    + "; incidents unresolved=" + incidents.unresolvedCount() + ", unsaved=" + incidents.unsavedCount()
                    + ", unreadable=" + incidents.unreadableFiles()
                    + "; hoppers pending=" + hoppers.pendingCount() + ", unsaved=" + hoppers.unsavedCount()
                    + ", unreadable=" + hoppers.unreadableFiles() + ", opened=" + hoppers.opened()
                    + ", opening failure=" + hoppers.openingFailure();
        }
    }
}
